package com.askmydocs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class AskMyDocsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AskMyDocsApplication.class, args);
    }
}
