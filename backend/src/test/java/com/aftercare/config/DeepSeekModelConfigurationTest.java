package com.aftercare.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.aftercare.agent.AgentPlan;
import com.aftercare.agent.ChatProvider;
import com.aftercare.agent.LiveChatProvider;
import com.aftercare.agent.MockChatProvider;
import com.aftercare.common.ApiException;
import com.aftercare.knowledge.EmbeddingProvider;
import com.aftercare.knowledge.MockEmbeddingProvider;
import com.aftercare.knowledge.SpringAiEmbeddingProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.retry.support.RetryTemplate;

class DeepSeekModelConfigurationTest {
    private static final String CHAT_KEY = "test-chat-key";
    private static final String EMBEDDING_KEY = "test-embedding-key";
    private static final String CHAT_MODEL = "deepseek-flash";
    private static final String EMBEDDING_MODEL = "Qwen/Qwen3-Embedding-0.6B";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void bindsSeparateLiveModelsAndCallsBothSpringAiClientsThroughLocalStub() throws Exception {
        var requests = new CopyOnWriteArrayList<CapturedRequest>();
        HttpServer server = startStubServer(requests);
        try (var liveContext = liveContext(server.getAddress().getPort());
                var mockContext = mockContext()) {
            assertThat(liveContext.getBeansOfType(ChatProvider.class)).hasSize(1);
            assertThat(liveContext.getBeansOfType(EmbeddingProvider.class)).hasSize(1);
            assertThat(liveContext.getEnvironment().getActiveProfiles())
                    .contains("live", "deepseek");
            assertThat(mockContext.getBeansOfType(ChatProvider.class)).hasSize(1);
            assertThat(mockContext.getBeansOfType(EmbeddingProvider.class)).hasSize(1);

            var chatProvider = liveContext.getBean(ChatProvider.class);
            AgentPlan plan = chatProvider.plan("请识别这个售后问题");
            assertThat(plan.intents()).containsExactly(AgentPlan.Intent.UNKNOWN);

            var embeddingProvider = liveContext.getBean(EmbeddingProvider.class);
            float[] vector = embeddingProvider.embedQuery("售后政策查询");
            assertThat(vector).hasSize(1024);
            assertThat(embeddingProvider.modelName()).isEqualTo(EMBEDDING_MODEL);

            CapturedRequest chat = requests.stream()
                    .filter(request -> request.path().equals("/deepseek/v1/chat/completions"))
                    .findFirst().orElseThrow();
            CapturedRequest embedding = requests.stream()
                    .filter(request -> request.path().equals("/siliconflow/v1/embeddings"))
                    .findFirst().orElseThrow();

            assertThat(chat.authorization()).isEqualTo("Bearer " + CHAT_KEY);
            assertThat(chat.body().path("model").asText()).isEqualTo(CHAT_MODEL);
            assertThat(chat.body().path("response_format").path("type").asText())
                    .isEqualTo("json_object");
            JsonNode chatBody = chat.body();
            JsonNode thinking = chatBody.path("extra_body").path("thinking");
            if (thinking.isMissingNode()) {
                thinking = chatBody.path("thinking");
            }
            assertThat(thinking.path("type").asText()).isEqualTo("disabled");

            assertThat(embedding.authorization()).isEqualTo("Bearer " + EMBEDDING_KEY);
            assertThat(embedding.body().path("model").asText()).isEqualTo(EMBEDDING_MODEL);
            assertThat(embedding.body().path("dimensions").asInt()).isEqualTo(1024);
            assertThat(requests).hasSize(2);
        }
        finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 401, 429 })
    void mapsProviderAuthAndRateLimitFailuresWithoutRetrying(int status) throws Exception {
        var requests = new CopyOnWriteArrayList<CapturedRequest>();
        HttpServer server = startStubServer(requests, status, "{\"error\":\"stubbed provider failure\"}");
        try (var context = liveContext(server.getAddress().getPort())) {
            assertThatThrownBy(() -> context.getBean(ChatProvider.class).plan("查询订单"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(failure -> assertThat(((ApiException) failure).code()).isEqualTo("MODEL_UNAVAILABLE"));
            assertThat(requests).hasSize(1);
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void mapsInvalidModelPlanToUnavailableAfterOneRequest() throws Exception {
        var requests = new CopyOnWriteArrayList<CapturedRequest>();
        HttpServer server = startStubServer(requests, 200, chatResponse("not-json"));
        try (var context = liveContext(server.getAddress().getPort())) {
            assertThatThrownBy(() -> context.getBean(ChatProvider.class).plan("查询订单"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(failure -> assertThat(((ApiException) failure).code()).isEqualTo("MODEL_UNAVAILABLE"));
            assertThat(requests).hasSize(1);
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsWrongEmbeddingVectorDimensionsAfterOneRequest() throws Exception {
        var requests = new CopyOnWriteArrayList<CapturedRequest>();
        HttpServer server = startStubServer(requests, 200, embeddingResponse(3));
        try (var context = liveContext(server.getAddress().getPort())) {
            assertThatThrownBy(() -> context.getBean(EmbeddingProvider.class).embedQuery("退款政策"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("wrong dimension");
            assertThat(requests).hasSize(1);
        }
        finally {
            server.stop(0);
        }
    }

    private AnnotationConfigApplicationContext liveContext(int port) throws IOException {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("live", "deepseek");
        context.getEnvironment().getPropertySources().addLast(
                new YamlPropertySourceLoader().load("deepseek", new ClassPathResource("application-deepseek.yml"))
                        .getFirst());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("stub-model-settings", Map.of(
                "DEEPSEEK_API_KEY", CHAT_KEY,
                "DEEPSEEK_BASE_URL", "http://localhost:" + port,
                "DEEPSEEK_CHAT_MODEL", CHAT_MODEL,
                "SILICONFLOW_API_KEY", EMBEDDING_KEY,
                "SILICONFLOW_BASE_URL", "http://localhost:" + port,
                "SILICONFLOW_EMBEDDING_MODEL", EMBEDDING_MODEL,
                "EMBEDDING_DIMENSIONS", "1024",
                "spring.ai.openai.chat.completions-path", "/deepseek/v1/chat/completions",
                "spring.ai.openai.embedding.embeddings-path", "/siliconflow/v1/embeddings")));

        Binder binder = Binder.get(context.getEnvironment());
        OpenAiChatProperties chatProperties = binder.bind("spring.ai.openai.chat",
                Bindable.of(OpenAiChatProperties.class)).orElseThrow(
                        () -> new IllegalStateException("OpenAI chat properties were not bound"));
        OpenAiEmbeddingProperties embeddingProperties = binder.bind("spring.ai.openai.embedding",
                Bindable.of(OpenAiEmbeddingProperties.class)).orElseThrow(
                        () -> new IllegalStateException("OpenAI embedding properties were not bound"));

        var chatRestClientBuilder = org.springframework.web.client.RestClient.builder();
        new ModelHttpClientConfiguration().modelRestClientTimeoutCustomizer(
                java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(30)).customize(chatRestClientBuilder);
        OpenAiApi chatApi = OpenAiApi.builder()
                .baseUrl(chatProperties.getBaseUrl())
                .apiKey(chatProperties.getApiKey())
                .completionsPath(chatProperties.getCompletionsPath())
                .restClientBuilder(chatRestClientBuilder)
                .build();
        OpenAiApi embeddingApi = OpenAiApi.builder()
                .baseUrl(embeddingProperties.getBaseUrl())
                .apiKey(embeddingProperties.getApiKey())
                .embeddingsPath(embeddingProperties.getEmbeddingsPath())
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(chatApi)
                .defaultOptions(chatProperties.getOptions())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
        var embeddingModel = new OpenAiEmbeddingModel(embeddingApi, MetadataMode.EMBED,
                embeddingProperties.getOptions());

        context.registerBean("embeddingProperties", EmbeddingProperties.class,
                () -> new EmbeddingProperties(1024));
        context.registerBean("chatClientBuilder", ChatClient.Builder.class,
                () -> ChatClient.builder(chatModel));
        context.registerBean("embeddingModel", org.springframework.ai.embedding.EmbeddingModel.class,
                () -> embeddingModel);
        context.registerBean("objectMapper", ObjectMapper.class, () -> objectMapper);
        context.register(LiveChatProvider.class, SpringAiEmbeddingProvider.class);
        context.refresh();
        return context;
    }

    private AnnotationConfigApplicationContext mockContext() {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("mock");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("mock-settings", Map.of(
                "app.embedding.dimensions", "1024")));
        context.registerBean("embeddingProperties", EmbeddingProperties.class,
                () -> new EmbeddingProperties(1024));
        context.register(MockChatProvider.class, MockEmbeddingProvider.class);
        context.refresh();
        return context;
    }

    private HttpServer startStubServer(List<CapturedRequest> requests) throws IOException {
        return startStubServer(requests, 200, null);
    }

    private HttpServer startStubServer(List<CapturedRequest> requests, int status, String responseBody)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new CapturedRequest(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), objectMapper.readTree(body)));
            String response = responseBody != null ? responseBody
                    : exchange.getRequestURI().getPath().contains("embeddings") ? embeddingResponse() : chatResponse();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private String chatResponse() throws IOException {
        return chatResponse("{\"intents\":[\"UNKNOWN\"]}");
    }

    private String chatResponse(String content) throws IOException {
        return objectMapper.writeValueAsString(Map.of(
                "id", "chatcmpl-local-stub",
                "object", "chat.completion",
                "created", 1,
                "model", CHAT_MODEL,
                "choices", List.of(Map.of(
                        "index", 0,
                        "message", Map.of("role", "assistant", "content", content),
                        "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
    }

    private String embeddingResponse() throws IOException {
        return embeddingResponse(1024);
    }

    private String embeddingResponse(int dimensions) throws IOException {
        List<Float> vector = java.util.Collections.nCopies(dimensions, 0.01f);
        return objectMapper.writeValueAsString(Map.of(
                "object", "list",
                "model", EMBEDDING_MODEL,
                "data", List.of(Map.of("object", "embedding", "index", 0, "embedding", vector)),
                "usage", Map.of("prompt_tokens", 2, "total_tokens", 2)));
    }

    private record CapturedRequest(String path, String authorization, JsonNode body) { }
}
