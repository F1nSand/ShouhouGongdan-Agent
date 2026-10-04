package com.aftercare.knowledge;

import java.util.ArrayList;
import java.util.List;

public final class TextChunker {
    private static final int TARGET_CODE_POINTS = 500;
    private static final int MAX_CODE_POINTS = 800;
    private static final int OVERLAP_CODE_POINTS = 100;

    private TextChunker() {
    }

    public static List<Chunk> chunk(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n').strip();
        var contents = new ArrayList<String>();
        StringBuilder current = new StringBuilder();

        for (String paragraph : normalized.split("\\n\\s*\\n")) {
            String block = paragraph.strip();
            if (block.isEmpty()) {
                continue;
            }
            int size = codePointCount(block);
            if (size > MAX_CODE_POINTS) {
                flush(current, contents);
                contents.addAll(splitLongBlock(block));
                continue;
            }

            int combinedSize = current.isEmpty() ? size : codePointCount(current.toString()) + 2 + size;
            if (!current.isEmpty() && (combinedSize > MAX_CODE_POINTS || combinedSize >= TARGET_CODE_POINTS)) {
                flush(current, contents);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(block);
        }
        flush(current, contents);

        var chunks = new ArrayList<Chunk>(contents.size());
        for (int index = 0; index < contents.size(); index++) {
            chunks.add(new Chunk(index, contents.get(index)));
        }
        return List.copyOf(chunks);
    }

    private static List<String> splitLongBlock(String block) {
        int[] points = block.codePoints().toArray();
        var chunks = new ArrayList<String>();
        int start = 0;
        while (start < points.length) {
            int target = Math.min(start + TARGET_CODE_POINTS, points.length);
            int maximum = Math.min(start + MAX_CODE_POINTS, points.length);
            int end = findBoundary(points, target, maximum);
            if (end <= start) {
                end = target;
            }
            chunks.add(new String(points, start, end - start));
            if (end == points.length) {
                break;
            }
            start = end - OVERLAP_CODE_POINTS;
        }
        return chunks;
    }

    private static int findBoundary(int[] points, int target, int maximum) {
        for (int index = target; index < maximum; index++) {
            if (isBoundary(points[index - 1])) {
                return index;
            }
        }
        return target;
    }

    private static boolean isBoundary(int codePoint) {
        return codePoint == '\n' || codePoint == '。' || codePoint == '！' || codePoint == '？'
                || codePoint == '!' || codePoint == '?' || codePoint == ';' || codePoint == '；';
    }

    private static int codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private static void flush(StringBuilder current, List<String> target) {
        if (!current.isEmpty()) {
            target.add(current.toString());
            current.setLength(0);
        }
    }

    public record Chunk(int index, String text) {
    }
}
