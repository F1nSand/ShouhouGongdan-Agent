package com.aftercare.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ModelRequestBudgetIT {
    @Container
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("budget_test")
            .withUsername("budget")
            .withPassword("budget");

    private final List<String> requestPaths = new CopyOnWriteArrayList<>();
    private JdbcTemplate jdbc;
    private HttpServer server;
    private int requestPort;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(),
                DATABASE.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS model_request_budget");
        jdbc.execute("DROP TABLE IF EXISTS model_request_budget_daily");
        jdbc.execute("""
                CREATE TABLE model_request_budget (
                    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
                    attempts BIGINT NOT NULL CHECK (attempts >= 0),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.update("INSERT INTO model_request_budget(singleton_id, attempts) VALUES (1, 0)");
        jdbc.execute("""
                CREATE TABLE model_request_budget_daily (
                    budget_date DATE PRIMARY KEY,
                    attempts SMALLINT NOT NULL CHECK (attempts BETWEEN 0 AND 50),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        requestPaths.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            requestPaths.add(path);
            boolean failedChat = path.contains("chat/completions");
            byte[] body = (failedChat ? "{}" : embeddingResponse()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(failedChat ? 500 : 200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        requestPort = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void actualSpringAiClientsShareBudgetAndRejectedAttemptSendsNoBytes() {
        var budget = new ModelRequestBudget(jdbc, 50);
        var retryOnce = RetryTemplate.builder().maxAttempts(1).build();

        OpenAiApi chatApi = OpenAiApi.builder()
                .baseUrl("http://127.0.0.1:" + requestPort)
                .apiKey("test-chat-key")
                .completionsPath("/deepseek/v1/chat/completions")
                .restClientBuilder(restClientBuilder(budget))
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(chatApi)
                .defaultOptions(OpenAiChatOptions.builder().model("deepseek-flash").build())
                .retryTemplate(retryOnce)
                .build();
        OpenAiApi embeddingApi = OpenAiApi.builder()
                .baseUrl("http://127.0.0.1:" + requestPort)
                .apiKey("test-embedding-key")
                .embeddingsPath("/siliconflow/v1/embeddings")
                .restClientBuilder(restClientBuilder(budget))
                .build();
        OpenAiEmbeddingModel embeddingModel = new OpenAiEmbeddingModel(embeddingApi, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model("Qwen/Qwen3-Embedding-0.6B").dimensions(1024).build(),
                retryOnce);

        assertThatThrownBy(() -> chatModel.call(new Prompt("loopback failure")))
                .isInstanceOf(RuntimeException.class);
        assertThat(requestPaths).containsExactly("/deepseek/v1/chat/completions");
        assertThat(attempts()).isEqualTo(1);

        assertThat(embeddingModel.embed("loopback success")).hasSize(1024);
        assertThat(requestPaths).containsExactly("/deepseek/v1/chat/completions", "/siliconflow/v1/embeddings");
        assertThat(attempts()).isEqualTo(2);

        for (int i = 0; i < 48; i++) {
            budget.reserveAttempt();
        }
        assertThat(attempts()).isEqualTo(50);
        assertThatThrownBy(() -> embeddingModel.embed("must not be sent"))
                .isInstanceOf(RuntimeException.class);
        assertThat(requestPaths).hasSize(2);
        assertThat(attempts()).isEqualTo(50);
    }

    @Test
    void cumulativeCountSurvivesBudgetServiceRecreation() {
        var originalService = new ModelRequestBudget(jdbc, 2);
        assertThat(originalService.reserveAttempt()).isEqualTo(1);
        assertThat(originalService.reserveAttempt()).isEqualTo(2);

        var recreatedService = new ModelRequestBudget(jdbc, 2);
        assertThatThrownBy(recreatedService::reserveAttempt)
                .isInstanceOf(ModelRequestBudget.ModelRequestBudgetExceededException.class);
        assertThat(attempts()).isEqualTo(2);
    }

    @Test
    void concurrentReservationsCannotExceedConfiguredLimit() throws Exception {
        var budget = new ModelRequestBudget(jdbc, 50);
        try (ExecutorService executor = Executors.newFixedThreadPool(12)) {
            List<Future<Boolean>> reservations = new java.util.ArrayList<>();
            for (int i = 0; i < 100; i++) {
                reservations.add(executor.submit(() -> {
                    try {
                        budget.reserveAttempt();
                        return true;
                    }
                    catch (ModelRequestBudget.ModelRequestBudgetExceededException exhausted) {
                        return false;
                    }
                }));
            }
            long allowed = 0;
            for (Future<Boolean> reservation : reservations) {
                if (reservation.get()) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(50);
            assertThat(attempts()).isEqualTo(50);
        }
    }

    @Test
    void dailyCountSurvivesRecreationAndResetsAtShanghaiMidnight() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate yesterday = LocalDate.of(2026, 9, 30);
        Clock beforeMidnight = Clock.fixed(Instant.parse("2026-09-30T15:59:59Z"), zone);
        var firstService = new ModelRequestBudget(jdbc, 50, ModelRequestBudgetMode.DAILY, beforeMidnight, zone);
        assertThat(firstService.reserveAttempt()).isEqualTo(1);
        assertThat(new ModelRequestBudget(jdbc, 50, ModelRequestBudgetMode.DAILY, beforeMidnight, zone)
                .reserveAttempt()).isEqualTo(2);

        Clock afterMidnight = Clock.fixed(Instant.parse("2026-09-30T16:00:00Z"), zone);
        assertThat(new ModelRequestBudget(jdbc, 50, ModelRequestBudgetMode.DAILY, afterMidnight, zone)
                .reserveAttempt()).isEqualTo(1);
        assertThat(dailyAttempts(yesterday)).isEqualTo(2);
        assertThat(dailyAttempts(yesterday.plusDays(1))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_request_budget_daily", Integer.class))
                .isEqualTo(2);
        assertThat(attempts()).isZero();
    }

    @Test
    void concurrentDailyReservationsCannotExceedConfiguredLimit() throws Exception {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Clock clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), zone);
        var budget = new ModelRequestBudget(jdbc, 50, ModelRequestBudgetMode.DAILY, clock, zone);
        try (ExecutorService executor = Executors.newFixedThreadPool(12)) {
            List<Future<Boolean>> reservations = new java.util.ArrayList<>();
            for (int i = 0; i < 100; i++) {
                reservations.add(executor.submit(() -> {
                    try {
                        budget.reserveAttempt();
                        return true;
                    }
                    catch (ModelRequestBudget.ModelRequestBudgetExceededException exhausted) {
                        return false;
                    }
                }));
            }
            long allowed = 0;
            for (Future<Boolean> reservation : reservations) {
                if (reservation.get()) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(50);
            assertThat(dailyAttempts(LocalDate.of(2026, 9, 30))).isEqualTo(50);
            assertThat(attempts()).isZero();
        }
    }

    @Test
    void dailyLimitBlocksTheNextActualModelRequestAndReportsResetTime() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Clock clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), zone);
        var budget = new ModelRequestBudget(jdbc, 1, ModelRequestBudgetMode.DAILY, clock, zone);
        var retryOnce = RetryTemplate.builder().maxAttempts(1).build();
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl("http://127.0.0.1:" + requestPort)
                .apiKey("test-chat-key")
                .completionsPath("/deepseek/v1/chat/completions")
                .restClientBuilder(restClientBuilder(budget))
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("deepseek-flash").build())
                .retryTemplate(retryOnce)
                .build();

        assertThatThrownBy(() -> chatModel.call(new Prompt("first request fails at the stub")))
                .isInstanceOf(RuntimeException.class);
        assertThat(requestPaths).containsExactly("/deepseek/v1/chat/completions");
        assertThatThrownBy(() -> chatModel.call(new Prompt("budget blocks this request")))
                .isInstanceOf(ModelRequestBudget.ModelRequestBudgetExceededException.class);
        assertThat(requestPaths).containsExactly("/deepseek/v1/chat/completions");
        assertThat(dailyAttempts(LocalDate.of(2026, 9, 30))).isEqualTo(1);
        assertThatThrownBy(budget::reserveAttempt)
                .isInstanceOf(ModelRequestBudget.ModelRequestBudgetExceededException.class)
                .satisfies(failure -> assertThat(((ModelRequestBudget.ModelRequestBudgetExceededException) failure)
                        .resetAt()).isEqualTo(Instant.parse("2026-09-30T16:00:00Z")));
    }

    private long attempts() {
        return jdbc.queryForObject("SELECT attempts FROM model_request_budget WHERE singleton_id = 1", Long.class);
    }

    private long dailyAttempts(LocalDate day) {
        Long attempts = jdbc.query("SELECT attempts FROM model_request_budget_daily WHERE budget_date = ?",
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null, day);
        return attempts == null ? 0 : attempts;
    }

    private RestClient.Builder restClientBuilder(ModelRequestBudget budget) {
        RestClient.Builder builder = RestClient.builder();
        new ModelRequestBudgetConfiguration().modelBudgetRestClientCustomizer(budget).customize(builder);
        return builder;
    }

    private String embeddingResponse() {
        var values = Collections.nCopies(1024, 0.01f);
        var vector = values.stream().map(value -> value.toString()).collect(java.util.stream.Collectors.joining(","));
        return "{\"object\":\"list\",\"model\":\"Qwen/Qwen3-Embedding-0.6B\",\"data\":["
                + "{\"object\":\"embedding\",\"index\":0,\"embedding\":[" + vector + "]}],"
                + "\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";
    }
}
