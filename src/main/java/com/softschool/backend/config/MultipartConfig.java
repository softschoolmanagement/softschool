package com.softschool.backend.config;

import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import jakarta.servlet.MultipartConfigElement;

/**
 * Guarantees the servlet container accepts photo/B-Form uploads up to a
 * reasonable size regardless of what (if anything) is set in
 * application.properties. If you'd rather control this from
 * application.properties instead, you can delete this class and set:
 *
 *   spring.servlet.multipart.max-file-size=10MB
 *   spring.servlet.multipart.max-request-size=10MB
 *
 * (Spring Boot's own multipart auto-configuration backs off automatically
 * once a MultipartConfigElement bean like this one is present, so only one
 * of the two — this class or the properties — is needed.)
 */
@Configuration
public class MultipartConfig {

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofMegabytes(10));
        factory.setMaxRequestSize(DataSize.ofMegabytes(10));
        return factory.createMultipartConfig();
    }
}
