package com.acme.coderag.core.chunk;

import com.acme.coderag.core.model.ChunkKind;
import com.acme.coderag.core.model.SourceChunk;
import com.acme.coderag.core.model.SourceFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chunker is the one component whose bugs are invisible in production: a bad
 * boundary produces a slightly worse search result, never an error. So it is
 * tested tightly.
 */
class BraceStructuralChunkerTest {

    private final ChunkerRouter chunker = ChunkerRouter.withDefaults();

    private static SourceFile java(String content) {
        return new SourceFile("src/main/java/com/acme/Sample.java", "java", "blob-sha", content);
    }

    @Test
    @DisplayName("one chunk per method, javadoc and annotations included")
    void splitsMethodsAndKeepsDocumentation() {
        List<SourceChunk> chunks = chunker.chunk(java("""
                package com.acme;

                public class OrderService {

                    private final Clock clock;

                    /**
                     * Finds an order by its identifier.
                     */
                    @Transactional(readOnly = true)
                    public Order findById(Long id) {
                        return repository.findById(id).orElseThrow();
                    }

                    public void cancel(Long id) {
                        repository.cancel(id);
                    }
                }
                """));

        List<SourceChunk> methods = chunks.stream()
                .filter(c -> c.kind() == ChunkKind.METHOD)
                .toList();

        assertThat(methods).hasSize(2);
        assertThat(methods).extracting(SourceChunk::symbol)
                .containsExactly("OrderService#findById", "OrderService#cancel");

        SourceChunk findById = methods.getFirst();
        assertThat(findById.text())
                .contains("Finds an order by its identifier")
                .contains("@Transactional")
                .contains("repository.findById");
        assertThat(findById.signature()).contains("public Order findById(Long id)");
    }

    @Test
    @DisplayName("control-flow blocks are not mistaken for methods")
    void ignoresControlFlowAndLambdas() {
        List<SourceChunk> chunks = chunker.chunk(java("""
                public class Loops {
                    public void run(List<String> items) {
                        if (items.isEmpty()) {
                            return;
                        }
                        for (String item : items) {
                            while (item.length() > 3) {
                                item = item.substring(1);
                            }
                        }
                        items.forEach(item -> {
                            System.out.println(item);
                        });
                        Runnable r = new Runnable() {
                            public void run() {
                            }
                        };
                    }
                }
                """));

        assertThat(chunks).extracting(SourceChunk::symbol)
                .noneMatch(s -> s.endsWith("#if") || s.endsWith("#for")
                        || s.endsWith("#while") || s.endsWith("#forEach")
                        || s.endsWith("#Runnable"));
        assertThat(chunks).extracting(SourceChunk::symbol).contains("Loops#run");
    }

    @Test
    @DisplayName("braces inside strings, text blocks and comments do not shift boundaries")
    void survivesBracesInLiterals() {
        List<SourceChunk> chunks = chunker.chunk(java("""
                public class Tricky {
                    public String json() {
                        return "{\\"a\\": 1}";
                    }

                    public String block() {
                        return \"""
                               { "nested": { "deep": true } }
                               \""";
                    }

                    // a closing brace in a comment: }
                    public int last() {
                        return 42;
                    }
                }
                """));

        assertThat(chunks).extracting(SourceChunk::symbol)
                .contains("Tricky#json", "Tricky#block", "Tricky#last");
    }

    @Test
    @DisplayName("nested types get their own symbol path")
    void qualifiesNestedTypes() {
        List<SourceChunk> chunks = chunker.chunk(java("""
                public class Outer {
                    public static class Inner {
                        public void ping() {
                        }
                    }

                    public void outerMethod() {
                    }
                }
                """));

        assertThat(chunks).extracting(SourceChunk::symbol)
                .contains("Outer.Inner#ping", "Outer#outerMethod", "Outer.Inner", "Outer");
    }

    @Test
    @DisplayName("type chunk is a skeleton: fields kept, method bodies elided")
    void typeChunkIsASkeleton() {
        List<SourceChunk> chunks = chunker.chunk(java("""
                public class Config {
                    private static final int RETRIES = 3;
                    private final String name;

                    public void apply() {
                        doSomethingVeryDetailed();
                        andSomethingElse();
                    }
                }
                """));

        SourceChunk type = chunks.stream()
                .filter(c -> c.kind() == ChunkKind.TYPE)
                .findFirst()
                .orElseThrow();

        assertThat(type.text())
                .contains("RETRIES = 3")
                .contains("private final String name")
                .contains("public void apply()")
                .doesNotContain("doSomethingVeryDetailed");
    }

    @Test
    @DisplayName("identical text hashes identically regardless of trailing whitespace")
    void hashIsStableAcrossTrailingWhitespace() {
        String clean = """
                public class A {
                    public void go() {
                        call();
                    }
                }
                """;
        String withTrailingSpaces = clean.replace("call();", "call();   ");

        SourceChunk a = methodOf(chunker.chunk(java(clean)));
        SourceChunk b = methodOf(chunker.chunk(java(withTrailingSpaces)));

        assertThat(a.sha()).isEqualTo(b.sha());
    }

    @Test
    @DisplayName("non-brace files fall back to line windows")
    void windowsPlainText() {
        SourceFile yaml = new SourceFile("src/main/resources/application.yml", "yaml", "sha", """
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost:5432/db
                """);

        List<SourceChunk> chunks = chunker.chunk(yaml);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allMatch(c -> c.kind() == ChunkKind.TEXT);
    }

    @Test
    @DisplayName("excluded paths produce nothing")
    void skipsBuildOutput() {
        SourceFile compiled = new SourceFile("target/classes/com/acme/A.class", "java", "sha", "irrelevant");
        assertThat(chunker.chunk(compiled)).isEmpty();
    }

    private static SourceChunk methodOf(List<SourceChunk> chunks) {
        return chunks.stream()
                .filter(c -> c.kind() == ChunkKind.METHOD)
                .findFirst()
                .orElseThrow();
    }
}
