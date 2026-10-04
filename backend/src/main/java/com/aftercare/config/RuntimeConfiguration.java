package com.aftercare.config;

import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ExecutionProperties.class)
public class RuntimeConfiguration {

    @Bean
    Clock utcClock() {
        return Clock.systemUTC();
    }

    @Bean("agentTaskExecutor")
    ThreadPoolTaskExecutor agentTaskExecutor(ExecutionProperties properties) {
        if (properties.getCoreSize() < 1
                || properties.getMaxSize() < properties.getCoreSize()
                || properties.getQueueCapacity() < 1) {
            throw new IllegalArgumentException("app.execution limits must define a positive bounded pool");
        }

        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCoreSize());
        executor.setMaxPoolSize(properties.getMaxSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("aftersales-agent-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }

    @Bean("modelCallExecutor")
    ThreadPoolTaskExecutor modelCallExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("aftersales-model-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean("toolCallExecutor")
    ThreadPoolTaskExecutor toolCallExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("aftersales-tool-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean
    JdbcTemplate boundedJdbcTemplate(DataSource dataSource) {
        var jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setQueryTimeout(3);
        return jdbcTemplate;
    }
}
