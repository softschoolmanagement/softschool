package com.softschool.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS used to be configured here with allowedOrigins("*"). It is now handled
 * in one place only - security/CorsHeaderFilter - which enforces the
 * app.allowed-origins allowlist. Do not add CORS mappings or @CrossOrigin
 * annotations back: they would run alongside that filter and could re-open
 * the API to every website.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
}
