package com.jcq.springaialibabaagent.mcp.client.config;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class McpConfiguration {

    @Bean
    public McpSyncHttpClientRequestCustomizer mcpSyncHttpClientRequestCustomizer() {

        Map<String,String> headers = new HashMap<>();
        String token = System.getenv("MCP_AUTH_TOKEN");
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        return new HeaderSyncHttpRequestCustomizer(headers);
    }
}
