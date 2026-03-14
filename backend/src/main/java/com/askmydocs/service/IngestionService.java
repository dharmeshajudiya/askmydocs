package com.askmydocs.service;

import com.askmydocs.entity.Document;
import com.askmydocs.repository.DocumentRepository;
import com.askmydocs.util.S3Util;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import lombok.RequiredArgsConstructor;
import org.openapitools.db_data.client.model.UpsertResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;
    private static final int EMBED_BATCH_SIZE = 100;

    private final DocumentRepository documentRepository;
    private final OpenAiEmbeddingModel embeddingModel;
    private final S3Util s3Util;
    private final Pinecone pineconeClient;

    @Value("${app.pinecone.index-host}")
    private String pineconeIndexHost;

    @Async("ingestionExecutor")
    @Transactional
    public void ingest(UUID docId) {
        Document document = documentRepository.findById(docId)
            .orElseThrow(() -> new IllegalArgumentException("Document not found: " + docId));

        String logPrefix = "docId=" + docId + " userId=" + document.getUserId();
        log.info("{} ingestion_started", logPrefix);

        // Set processing BEFORE any work — prevents duplicate jobs on crash/restart
        document.setStatus("processing");
        documentRepository.save(document);

        try {
            // 1. Download from S3
            byte[] fileBytes = s3Util.download(document.getS3Url());
            log.info("{} s3_download_complete bytes={}", logPrefix, fileBytes.length);

            // 2. Extract text with page numbers
            List<PagedText> pages = extractText(fileBytes, document.getFilename());
            log.info("{} text_extracted pages={}", logPrefix, pages.size());

            // 3. Chunk text
            List<Chunk> chunks = chunkPages(pages, docId.toString(), document.getUserId().toString());
            log.info("{} chunking_complete total_chunks={}", logPrefix, chunks.size());

            // 4 & 5. Embed in batches and upsert to Pinecone
            String namespace = "user_" + document.getUserId();
            Index index = pineconeClient.getIndexConnection(pineconeIndexHost);

            for (int i = 0; i < chunks.size(); i += EMBED_BATCH_SIZE) {
                List<Chunk> batch = chunks.subList(i, Math.min(i + EMBED_BATCH_SIZE, chunks.size()));
                List<String> texts = batch.stream().map(Chunk::text).toList();

                float[] flatEmbeddings = embeddingModel.embed(texts.get(0));
                // Batch embed all texts
                List<float[]> embeddings = texts.stream()
                    .map(embeddingModel::embed)
                    .toList();

                // Build Pinecone upsert vectors
                List<com.google.protobuf.Struct> metadataList = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                List<List<Float>> vectors = new ArrayList<>();

                for (int j = 0; j < batch.size(); j++) {
                    Chunk chunk = batch.get(j);
                    ids.add(chunk.id());

                    List<Float> vector = new ArrayList<>();
                    for (float v : embeddings.get(j)) vector.add(v);
                    vectors.add(vector);

                    var metaBuilder = com.google.protobuf.Struct.newBuilder()
                        .putFields("doc_id", stringValue(chunk.docId()))
                        .putFields("chunk_index", numberValue(chunk.chunkIndex()))
                        .putFields("page_number", numberValue(chunk.pageNumber()))
                        .putFields("char_start", numberValue(chunk.charStart()))
                        .putFields("char_end", numberValue(chunk.charEnd()))
                        .putFields("user_id", stringValue(chunk.userId()))
                        .putFields("text", stringValue(chunk.text()));
                    metadataList.add(metaBuilder.build());
                }

                index.upsert(ids, vectors, null, null, metadataList, namespace);
                log.info("{} batch_upserted batch_start={} size={}", logPrefix, i, batch.size());
            }

            // 6. Mark ready
            document.setStatus("ready");
            document.setTotalChunks(chunks.size());
            documentRepository.save(document);
            log.info("{} ingestion_complete total_chunks={}", logPrefix, chunks.size());

        } catch (Exception e) {
            log.error("{} ingestion_failed error={}", logPrefix, e.getMessage(), e);
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            document.setStatus("error");
            document.setErrorLog(sw.toString());
            documentRepository.save(document);
        }
    }

    private List<PagedText> extractText(byte[] fileBytes, String filename) {
        String ext = filename.toLowerCase();
        if (ext.endsWith(".pdf")) {
            return extractPdf(fileBytes);
        }
        // DOCX and TXT via Tika
        return extractWithTika(fileBytes, filename);
    }

    private List<PagedText> extractPdf(byte[] fileBytes) {
        var resource = new ByteArrayResource(fileBytes) {
            @Override public String getFilename() { return "document.pdf"; }
        };
        var config = PdfDocumentReaderConfig.builder()
            .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())
            .withPagesPerDocument(1)
            .build();
        var reader = new PagePdfDocumentReader(resource, config);
        List<PagedText> pages = new ArrayList<>();
        for (var doc : reader.get()) {
            int page = Integer.parseInt(
                doc.getMetadata().getOrDefault("page_number", "1").toString()
            );
            pages.add(new PagedText(doc.getContent(), page));
        }
        return pages;
    }

    private List<PagedText> extractWithTika(byte[] fileBytes, String filename) {
        var resource = new ByteArrayResource(fileBytes) {
            @Override public String getFilename() { return filename; }
        };
        var reader = new TikaDocumentReader(resource);
        List<PagedText> pages = new ArrayList<>();
        for (var doc : reader.get()) {
            pages.add(new PagedText(doc.getContent(), 1));
        }
        return pages;
    }

    private List<Chunk> chunkPages(List<PagedText> pages, String docId, String userId) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkIndex = 0;

        for (PagedText page : pages) {
            String text = page.text();
            List<String> splits = splitText(text);
            int cursor = 0;

            for (String split : splits) {
                int charStart = text.indexOf(split, cursor);
                int charEnd = charStart + split.length();
                cursor = charEnd;

                chunks.add(new Chunk(
                    docId + "_" + chunkIndex,
                    split,
                    docId,
                    userId,
                    chunkIndex,
                    page.pageNumber(),
                    charStart,
                    charEnd
                ));
                chunkIndex++;
            }
        }
        return chunks;
    }

    private List<String> splitText(String text) {
        List<String> result = new ArrayList<>();
        if (text.length() <= CHUNK_SIZE) {
            if (!text.isBlank()) result.add(text);
            return result;
        }

        String[] separators = {"\n\n", "\n", ". ", " "};
        for (String sep : separators) {
            if (text.contains(sep)) {
                int start = 0;
                while (start < text.length()) {
                    int end = Math.min(start + CHUNK_SIZE, text.length());
                    // Walk back to find a clean break
                    if (end < text.length()) {
                        int breakAt = text.lastIndexOf(sep, end);
                        if (breakAt > start) end = breakAt + sep.length();
                    }
                    String chunk = text.substring(start, end).strip();
                    if (!chunk.isBlank()) result.add(chunk);
                    start = end - CHUNK_OVERLAP;
                    if (start >= text.length()) break;
                }
                return result;
            }
        }
        // Hard split as last resort
        for (int i = 0; i < text.length(); i += CHUNK_SIZE - CHUNK_OVERLAP) {
            String chunk = text.substring(i, Math.min(i + CHUNK_SIZE, text.length())).strip();
            if (!chunk.isBlank()) result.add(chunk);
        }
        return result;
    }

    private com.google.protobuf.Value stringValue(String s) {
        return com.google.protobuf.Value.newBuilder().setStringValue(s).build();
    }

    private com.google.protobuf.Value numberValue(int n) {
        return com.google.protobuf.Value.newBuilder().setNumberValue(n).build();
    }

    private record PagedText(String text, int pageNumber) {}

    private record Chunk(
        String id, String text, String docId, String userId,
        int chunkIndex, int pageNumber, int charStart, int charEnd
    ) {}
}
