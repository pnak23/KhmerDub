package com.khmerdub.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS is enabled for exactly one endpoint: the movie upload. The browser sends multi-GB files straight to
 * Spring Boot instead of through the Nuxt proxy (which would buffer them). Everything else stays same-origin.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final KhmerDubProperties props;

    public WebConfig(KhmerDubProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/projects/*/upload")
                .allowedOrigins(props.allowedOrigins().toArray(String[]::new))
                .allowedMethods("POST")
                .allowedHeaders("Content-Type", "X-File-Name")
                .maxAge(600);
    }
}
