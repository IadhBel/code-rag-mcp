package com.acme.coderag.core.model;

/**
 * What a chunk represents structurally.
 *
 * <p>Stored as text in {@code code_chunk.kind} rather than a database enum so
 * adding a kind never requires a migration.
 */
public enum ChunkKind {

    /**
     * A type declaration: its signature, javadoc, annotations and the members
     * that are not themselves methods (fields, constants, static initialisers).
     * This is what answers "what is this class for".
     */
    TYPE,

    /**
     * A single method or constructor body, together with its signature, javadoc
     * and annotations. The primary retrieval unit for source code.
     */
    METHOD,

    /**
     * A window of lines from a file with no brace structure to exploit: SQL,
     * YAML, Markdown, properties, Dockerfiles.
     */
    TEXT
}
