package com.aftercare.knowledge;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CitationValidatorTest {
    private static final UUID KNOWN_CHUNK = UUID.fromString("80000000-0000-0000-0000-000000000001");
    private static final UUID UNKNOWN_CHUNK = UUID.fromString("80000000-0000-0000-0000-000000000002");
    private static final String SOURCE = "用户在签收后七日内可申请退货，商品应保持完好。";
    private static final PolicyEvidence EVIDENCE = new PolicyEvidence(List.of(
            new PolicyEvidence.Chunk(KNOWN_CHUNK, "退货政策", 3, 1, SOURCE)));

    @Test
    void acceptsOnlyQuotesFromChunksRetrievedForThisAnswer() {
        var answer = new AssistantAnswer("可在七日内申请退货。",
                List.of(new AssistantAnswer.Citation(KNOWN_CHUNK, "签收后七日内可申请退货")));

        var validated = CitationValidator.validate(answer, EVIDENCE);

        assertThat(validated.citations()).containsExactlyElementsOf(answer.citations());
    }

    @Test
    void rejectsUnknownChunkAndFabricatedQuote() {
        var unknownChunk = new AssistantAnswer("政策允许退货。",
                List.of(new AssistantAnswer.Citation(UNKNOWN_CHUNK, SOURCE)));
        var fabricatedQuote = new AssistantAnswer("随时都能退款。",
                List.of(new AssistantAnswer.Citation(KNOWN_CHUNK, "无论何时均可全额退款")));

        assertThatThrownBy(() -> CitationValidator.validate(unknownChunk, EVIDENCE))
                .isInstanceOf(CitationValidator.InvalidCitationException.class);
        assertThatThrownBy(() -> CitationValidator.validate(fabricatedQuote, EVIDENCE))
                .isInstanceOf(CitationValidator.InvalidCitationException.class);
    }
}
