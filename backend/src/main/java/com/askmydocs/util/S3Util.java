package com.askmydocs.util;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;

@Component
@RequiredArgsConstructor
public class S3Util {

    private static final Logger log = LoggerFactory.getLogger(S3Util.class);

    private final S3Client s3Client;

    @Value("${app.s3.bucket}")
    private String bucket;

    @Value("${app.s3.endpoint-url:}")
    private String endpointUrl;

    @Value("${app.s3.region}")
    private String region;

    public String upload(byte[] bytes, String key, String contentType) {
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build(),
            RequestBody.fromBytes(bytes)
        );
        String url = buildUrl(key);
        log.info("S3 upload complete key={} url={}", key, url);
        return url;
    }

    public byte[] download(String s3Url) {
        String key = extractKey(s3Url);
        return s3Client.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key(key).build()
        ).asByteArray();
    }

    public void delete(String s3Url) {
        String key = extractKey(s3Url);
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        log.info("S3 delete complete key={}", key);
    }

    public String documentKey(String docId, String filename) {
        return "documents/" + docId + "/" + filename;
    }

    private String buildUrl(String key) {
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            return endpointUrl + "/" + bucket + "/" + key;
        }
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + key;
    }

    private String extractKey(String s3Url) {
        URI uri = URI.create(s3Url);
        String path = uri.getPath().replaceFirst("^/", "");
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            // MinIO: path = bucket/key — strip the bucket prefix
            int slash = path.indexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
        return path;
    }
}
