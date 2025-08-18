package com.mcp.spring_boot.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${etscore.api.base-url}")
    private String etscoreBaseUrl;

    @Value("${etscore.api.auth-token}")
    private String etscoreBearerToken;

    @Value("${etscore.api.accept-language}")
    private String etscoreAcceptLanguage;

    @Value("${etscore.api.currency}")
    private String etscoreCurrency;


    @Bean
    @Primary
    @Qualifier("etscoreWebClient")
    public WebClient etscoreWebClient() {
        return WebClient.builder()
                .baseUrl(etscoreBaseUrl)
                .defaultHeader("Authorization", "Bearer " + etscoreBearerToken)
                .defaultHeader("Accept-Language", etscoreAcceptLanguage)
                .defaultHeader("X-Currency", etscoreCurrency)
                .defaultHeader("Content-Type", "application/json")
                .filter(logRequest("ETS-API")) // Optional: log requests
                .build();
    }


    private ExchangeFilterFunction logRequest(String apiName) {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            System.out.println("[" + apiName + "] Request: " + clientRequest.method() + " " + clientRequest.url());
            clientRequest.headers().forEach((name, values) -> 
                values.forEach(value -> System.out.println("[" + apiName + "] " + name + ": " + value))
            );
            return reactor.core.publisher.Mono.just(clientRequest);
        });
    }
}
