package com.app.modules.recommendation.config;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client wiring for the Gorse recommender REST API. Sends the API key on every request and
 * always requests API version 2 so score-carrying responses are returned.
 */
@Configuration
public class GorseClientConfig {

    @Bean
    RestClient gorseRestClient(GorseProperties properties, RestClient.Builder restClientBuilder) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .connectTimeout(properties.getConnectTimeout())
                                .build());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        // Boot's auto-configured builder carries ObservationRestClientCustomizer, so calls through
        // this client get a CLIENT span; the static RestClient.builder() this replaced did not.
        return restClientBuilder
                .clone()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("X-API-Key", properties.getApiKey())
                // Version 2 returns [{Id, Score}] instead of bare id arrays (verified v0.5.11)
                .defaultHeader("X-API-Version", "2")
                .requestFactory(requestFactory)
                .build();
    }
}
