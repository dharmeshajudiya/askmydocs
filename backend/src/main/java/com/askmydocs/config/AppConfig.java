package com.askmydocs.config;

import io.pinecone.clients.Pinecone;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
public class AppConfig {

    @Value("${app.pinecone.api-key}")
    private String pineconeApiKey;

    @Value("${app.s3.access-key}")
    private String s3AccessKey;

    @Value("${app.s3.secret-key}")
    private String s3SecretKey;

    @Value("${app.s3.region}")
    private String s3Region;

    @Value("${app.s3.endpoint-url:}")
    private String s3EndpointUrl;

    @Bean
    public Pinecone pineconeClient() {
        return new Pinecone.Builder(pineconeApiKey).build();
    }

    @Bean
    public S3Client s3Client() {
        var credentials = AwsBasicCredentials.create(s3AccessKey, s3SecretKey);
        var builder = S3Client.builder()
                .region(Region.of(s3Region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials));

        if (s3EndpointUrl != null && !s3EndpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(s3EndpointUrl))
                   .forcePathStyle(true);  // required for MinIO
        }

        return builder.build();
    }

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }
}
