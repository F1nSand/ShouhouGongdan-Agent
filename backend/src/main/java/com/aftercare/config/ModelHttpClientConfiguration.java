package com.aftercare.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;

@Configuration
@Profile("live")
class ModelHttpClientConfiguration {
    @Bean
    RestClientCustomizer modelRestClientTimeoutCustomizer(
            @Value("${app.model.http.connect-timeout:3s}") Duration connectTimeout,
            @Value("${app.model.http.read-timeout:30s}") Duration readTimeout) {
        return builder -> {
            var httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
            var requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(readTimeout);
            builder.requestFactory(requestFactory);
        };
    }
}
