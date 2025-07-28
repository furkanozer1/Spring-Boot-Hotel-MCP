package com.mcp.spring_boot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${api.base-url}")
    private String baseUrl;

    @Value("${api.auth-token}")
    private String bearerToken;

    @Value("${api.accept-language}")
    private String acceptLanguage;

    @Value("${api.currency}")
    private String currency;

    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + bearerToken)
                .defaultHeader("Accept-Language", acceptLanguage)
                .defaultHeader("X-Currency", currency)
                .defaultHeader("Content-Type", "application/json")
                .filter(logRequest()) // Optional: log requests
                .build();
    }

    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            System.out.println("Request: " + clientRequest.method() + " " + clientRequest.url());
            clientRequest.headers().forEach((name, values) -> 
                values.forEach(value -> System.out.println(name + ": " + value))
            );
            return reactor.core.publisher.Mono.just(clientRequest);
        });
    }
}
