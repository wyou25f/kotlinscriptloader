package ru.privateserver.ksl.intellij.inspection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative source-level checks for common Bukkit main-thread-only APIs
 * called from KSL asynchronous scheduler lambdas. This is a heuristic warning,
 * not a Kotlin type checker or a replacement for Paper's API documentation.
 */
public final class KslAsyncThreadSafetyAnalyzer {
    private static final Pattern ASYNC_BLOCK = Pattern.compile(
        "\\b(runAsync|afterAsync|repeatAsync)\\b\\s*(?:\\([^{}]*\\))?\\s*\\{"
    );
    private static final Pattern UNSAFE_API = Pattern.compile(
        "\\bBukkit\\s*\\.\\s*(getPlayer|getOnlinePlayers|broadcastMessage|getWorld|getServer)\\b"
            + "|\\.\\s*(sendMessage|teleport|openInventory|setType|getBlockAt|getInventory)\\s*\\("
    );

    private KslAsyncThreadSafetyAnalyzer() {
    }

    public record Diagnostic(int startOffset, int endOffset, String method, String asyncFunction) {
    }

    public static List<Diagnostic> analyze(String source) {
        if (source == null || source.isEmpty() || source.length() > KslPreflightAnalyzer.MAX_SOURCE_CHARS) {
            // KslPreflightAnalyzer emits an explicit analysis-limit diagnostic for oversized files.
            return List.of();
        }

        String code = maskCommentsAndStrings(source);
        Matcher asyncMatcher = ASYNC_BLOCK.matcher(code);
        List<Diagnostic> diagnostics = new ArrayList<>();
        Set<Integer> reportedOffsets = new HashSet<>();

        while (asyncMatcher.find()) {
            int openBrace = asyncMatcher.end() - 1;
            int closeBrace = matchingBrace(code, openBrace);
            if (closeBrace < 0) {
                continue;
            }

            String asyncFunction = asyncMatcher.group(1);
            Matcher apiMatcher = UNSAFE_API.matcher(code);
            apiMatcher.region(openBrace + 1, closeBrace);
            while (apiMatcher.find()) {
                int group = apiMatcher.start(1) >= 0 ? 1 : 2;
                int start = apiMatcher.start(group);
                if (reportedOffsets.add(start)) {
                    diagnostics.add(new Diagnostic(
                        start,
                        apiMatcher.end(group),
                        apiMatcher.group(group),
                        asyncFunction
                    ));
                }
            }
        }

        return List.copyOf(diagnostics);
    }

    private static int matchingBrace(String code, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < code.length(); i++) {
            char current = code.charAt(i);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Replaces comments and string/character literals with spaces while
     * preserving offsets and newlines, so matches remain aligned with PSI.
     */
    private static String maskCommentsAndStrings(String source) {
        char[] result = source.toCharArray();
        int blockDepth = 0;
        boolean lineComment = false;
        boolean string = false;
        boolean tripleString = false;
        boolean character = false;
        boolean escaped = false;

        for (int i = 0; i < result.length; i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            char third = i + 2 < source.length() ? source.charAt(i + 2) : '\0';

            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                } else {
                    blank(result, i);
                }
                continue;
            }

            if (blockDepth > 0) {
                blank(result, i);
                if (c == '/' && next == '*') {
                    blank(result, ++i);
                    blockDepth++;
                } else if (c == '*' && next == '/') {
                    blank(result, ++i);
                    blockDepth--;
                }
                continue;
            }

            if (tripleString) {
                blank(result, i);
                if (c == '"' && next == '"' && third == '"') {
                    blank(result, ++i);
                    blank(result, ++i);
                    tripleString = false;
                }
                continue;
            }

            if (string || character) {
                blank(result, i);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if ((string && c == '"') || (character && c == '\'')) {
                    string = false;
                    character = false;
                }
                continue;
            }

            if (c == '/' && next == '/') {
                blank(result, i);
                blank(result, ++i);
                lineComment = true;
            } else if (c == '/' && next == '*') {
                blank(result, i);
                blank(result, ++i);
                blockDepth = 1;
            } else if (c == '"' && next == '"' && third == '"') {
                blank(result, i);
                blank(result, ++i);
                blank(result, ++i);
                tripleString = true;
            } else if (c == '"') {
                blank(result, i);
                string = true;
            } else if (c == '\'') {
                blank(result, i);
                character = true;
            }
        }

        return new String(result);
    }

    private static void blank(char[] chars, int index) {
        if (chars[index] != '\n' && chars[index] != '\r') {
            chars[index] = ' ';
        }
    }
}
