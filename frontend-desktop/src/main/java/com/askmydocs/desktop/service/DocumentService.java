package com.askmydocs.desktop.service;

import com.askmydocs.desktop.model.Document;
import com.askmydocs.desktop.util.TokenStore;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.File;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

public class DocumentService {

    public List<Document> list() throws Exception {
        var response = ApiClient.get("/documents", String.class);
        return ApiClient.MAPPER.readValue(response, new TypeReference<>() {});
    }

    public Document upload(File file) throws Exception {
        String boundary = UUID.randomUUID().toString();
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        String contentType = detectContentType(file.getName());

        // Build multipart/form-data body
        String partHeader = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"\r\n"
            + "Content-Type: " + contentType + "\r\n\r\n";
        String partFooter = "\r\n--" + boundary + "--\r\n";

        byte[] header  = partHeader.getBytes();
        byte[] footer  = partFooter.getBytes();
        byte[] body    = new byte[header.length + fileBytes.length + footer.length];
        System.arraycopy(header,    0, body, 0,                              header.length);
        System.arraycopy(fileBytes, 0, body, header.length,                  fileBytes.length);
        System.arraycopy(footer,    0, body, header.length + fileBytes.length, footer.length);

        var request = HttpRequest.newBuilder()
            .uri(ApiClient.uri("/documents"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Authorization", "Bearer " + TokenStore.get().accessToken())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();

        var httpResponse = ApiClient.http().send(request, HttpResponse.BodyHandlers.ofString());
        if (httpResponse.statusCode() >= 400) {
            throw new ApiClient.ApiException(httpResponse.statusCode(), httpResponse.body());
        }
        return ApiClient.MAPPER.readValue(httpResponse.body(), Document.class);
    }

    public Document getStatus(String docId) throws Exception {
        return ApiClient.get("/documents/" + docId + "/status", Document.class);
    }

    public void delete(String docId) throws Exception {
        ApiClient.delete("/documents/" + docId);
    }

    private String detectContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return "text/plain";
    }
}
