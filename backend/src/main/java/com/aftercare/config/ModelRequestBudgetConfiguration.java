package com.aftercare.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

@Configuration(proxyBeanMethods = false)
@Profile("live & deepseek")
@ConditionalOnProperty(prefix = "app.model-request-budget", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ModelRequestBudgetProperties.class)
public class ModelRequestBudgetConfiguration {

    @Bean
    ModelRequestBudget modelRequestBudget(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
            ModelRequestBudgetProperties properties, java.time.Clock clock) {
        return new ModelRequestBudget(jdbcTemplate, properties.getMaxAttempts(), properties.getMode(),
                clock, java.time.ZoneId.of(properties.getDailyTimeZone()));
    }

    @Bean
    RestClientCustomizer modelBudgetRestClientCustomizer(ModelRequestBudget budget) {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            budget.reserveAttempt();
            return execution.execute(request, body);
        });
    }

    @Bean
    WebClientCustomizer modelBudgetWebClientCustomizer(ModelRequestBudget budget) {
        return builder -> builder.filter(ExchangeFilterFunction.ofRequestProcessor(request -> {
            budget.reserveAttempt();
            return reactor.core.publisher.Mono.just(request);
        }));
    }
}
