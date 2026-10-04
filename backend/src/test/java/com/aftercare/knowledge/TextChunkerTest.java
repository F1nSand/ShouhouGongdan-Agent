package com.aftercare.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {
    @Test
    void chunksLongUnicodeTextWithOneHundredCodePointOverlap() {
        String pattern = "甲乙丙丁戊己庚辛壬癸";
        String text = pattern.repeat(150);

        var chunks = TextChunker.chunk(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.text().codePointCount(0, chunk.text().length())).isLessThanOrEqualTo(800));
        for (int index = 1; index < chunks.size(); index++) {
            String previous = chunks.get(index - 1).text();
            String current = chunks.get(index).text();
            assertThat(lastCodePoints(previous, 100)).isEqualTo(firstCodePoints(current, 100));
        }

        StringBuilder rebuilt = new StringBuilder(chunks.getFirst().text());
        chunks.stream().skip(1).forEach(chunk -> rebuilt.append(dropFirstCodePoints(chunk.text(), 100)));
        assertThat(rebuilt.toString()).isEqualTo(text);
    }

    @Test
    void prefersParagraphBoundariesWhenTheWholeParagraphFits() {
        String first = "A".repeat(400);
        String second = "B".repeat(400);

        var chunks = TextChunker.chunk(first + "\n\n" + second);

        assertThat(chunks).extracting(TextChunker.Chunk::text).containsExactly(first, second);
    }

    private static String firstCodePoints(String value, int count) {
        return value.substring(0, value.offsetByCodePoints(0, count));
    }

    private static String lastCodePoints(String value, int count) {
        return value.substring(value.offsetByCodePoints(value.length(), -count));
    }

    private static String dropFirstCodePoints(String value, int count) {
        return value.substring(value.offsetByCodePoints(0, count));
    }
}
