package com.acme.coderag.indexer.git;

/** A git command exited non-zero, timed out, or produced unusable output. */
public class GitException extends RuntimeException {

    public GitException(String message) {
        super(message);
    }

    public GitException(String message, Throwable cause) {
        super(message, cause);
    }
}
