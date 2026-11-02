package com.acme.coderag.core.model;

import java.util.Objects;

/**
 * One file as it exists at a specific commit.
 *
 * @param path      repository-relative path, always with {@code /} separators
 * @param language  language identifier from {@link com.acme.coderag.core.lang.Languages}
 * @param blobSha   the git blob SHA, taken straight from {@code git ls-tree}.
 *                  This is the first-level deduplication key: if it is unchanged
 *                  the file is skipped without being read, chunked or embedded.
 * @param content   full file text, normalised to {@code \n} line endings
 */
public record SourceFile(String path, String language, String blobSha, String content) {

    public SourceFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(blobSha, "blobSha");
        Objects.requireNonNull(content, "content");
    }

    /** Number of lines, counting a trailing newline as terminating the last line. */
    public int lineCount() {
        if (content.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        // A trailing newline terminates the last line rather than opening a new one.
        return content.charAt(content.length() - 1) == '\n' ? lines - 1 : lines;
    }
}
