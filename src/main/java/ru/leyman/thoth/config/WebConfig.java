package ru.leyman.thoth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS для Mini App, если фронтенд раздаётся не с того же домена, что и API.
 * Список доменов: thoth.cors.allowed-origins. Пустой список — CORS выключен.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ThothProperties properties;

    public WebConfig(ThothProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var origins = properties.cors().allowedOrigins();
        if (origins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
