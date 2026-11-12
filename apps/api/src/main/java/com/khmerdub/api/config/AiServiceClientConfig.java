package com.khmerdub.api.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AiServiceClientConfig {

    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    /** HTTP client for the local Python AI service. Every request carries the shared internal token. */
    @Bean
    RestClient aiServiceRestClient(RestClient.Builder builder, KhmerDubProperties props) {
        var settings = HttpClientSettings.defaults()
                .withTimeouts(props.aiService().connectTimeout(), props.aiService().readTimeout());
        return builder
                .baseUrl(props.aiService().url().toString())
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
                .defaultHeader(INTERNAL_TOKEN_HEADER, props.internalToken())
                .build();
    }
}
