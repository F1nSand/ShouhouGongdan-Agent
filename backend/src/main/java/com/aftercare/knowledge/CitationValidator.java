package com.aftercare.knowledge;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class CitationValidator {
    private CitationValidator() {
    }

    public static ValidatedAnswer validate(AssistantAnswer answer, PolicyEvidence evidence) {
        if (answer == null || answer.answer() == null || answer.answer().isBlank()) {
            throw new InvalidCitationException("Answer content is empty");
        }
        Map<java.util.UUID, PolicyEvidence.Chunk> allowed = evidence.chunks().stream()
                .collect(Collectors.toMap(PolicyEvidence.Chunk::chunkId, Function.identity(), (first, ignored) -> first));
        for (var citation : answer.citations()) {
            var chunk = allowed.get(citation.chunkId());
            if (chunk == null || citation.quote() == null || citation.quote().isBlank()
                    || !chunk.content().contains(citation.quote())) {
                throw new InvalidCitationException("Citation does not match retrieved evidence");
            }
        }
        return new ValidatedAnswer(answer.answer(), answer.citations(), answer.citations().stream()
                .map(citation -> allowed.get(citation.chunkId())).toList());
    }

    public record ValidatedAnswer(String answer, List<AssistantAnswer.Citation> citations,
            List<PolicyEvidence.Chunk> citedEvidence) {
        public ValidatedAnswer {
            citations = List.copyOf(citations);
            citedEvidence = List.copyOf(citedEvidence);
        }
    }

    public static final class InvalidCitationException extends RuntimeException {
        public InvalidCitationException(String message) {
            super(message);
        }
    }
}
