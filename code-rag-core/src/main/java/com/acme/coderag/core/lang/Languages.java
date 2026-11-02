package com.acme.coderag.core.lang;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a repository path to a language, and decides what is worth indexing.
 *
 * <p>The exclusion rules matter more than the language table: indexing build
 * output, vendored dependencies or lock files inflates the corpus, dilutes
 * retrieval and costs embedding calls for text nobody will ever ask about.
 */
public final class Languages {

    /**
     * @param id              stable identifier stored in {@code language} columns
     * @param braceStructured whether the brace-based structural chunker applies
     */
    public record Spec(String id, boolean braceStructured) {
    }

    public static final String UNKNOWN = "unknown";

    private static final Spec TEXTUAL = new Spec("text", false);

    private static final Map<String, Spec> BY_EXTENSION = Map.ofEntries(
            Map.entry("java", new Spec("java", true)),
            Map.entry("kt", new Spec("kotlin", true)),
            Map.entry("kts", new Spec("kotlin", true)),
            Map.entry("groovy", new Spec("groovy", true)),
            Map.entry("scala", new Spec("scala", true)),
            Map.entry("ts", new Spec("typescript", true)),
            Map.entry("tsx", new Spec("typescript", true)),
            Map.entry("js", new Spec("javascript", true)),
            Map.entry("jsx", new Spec("javascript", true)),
            Map.entry("mjs", new Spec("javascript", true)),
            Map.entry("go", new Spec("go", true)),
            Map.entry("rs", new Spec("rust", true)),
            Map.entry("cs", new Spec("csharp", true)),
            Map.entry("c", new Spec("c", true)),
            Map.entry("h", new Spec("c", true)),
            Map.entry("cpp", new Spec("cpp", true)),
            Map.entry("hpp", new Spec("cpp", true)),
            Map.entry("sql", new Spec("sql", false)),
            Map.entry("xml", new Spec("xml", false)),
            Map.entry("yaml", new Spec("yaml", false)),
            Map.entry("yml", new Spec("yaml", false)),
            Map.entry("json", new Spec("json", false)),
            Map.entry("properties", new Spec("properties", false)),
            Map.entry("md", new Spec("markdown", false)),
            Map.entry("adoc", new Spec("asciidoc", false)),
            Map.entry("sh", new Spec("shell", false)),
            Map.entry("bash", new Spec("shell", false)),
            Map.entry("tf", new Spec("terraform", false)),
            Map.entry("proto", new Spec("protobuf", false)),
            Map.entry("html", new Spec("html", false)),
            Map.entry("css", new Spec("css", false)),
            Map.entry("scss", new Spec("css", false)));

    /** Files whose exact name is meaningful even without an extension. */
    private static final Map<String, Spec> BY_FILENAME = Map.of(
            "Dockerfile", new Spec("dockerfile", false),
            "Makefile", new Spec("make", false),
            "Jenkinsfile", new Spec("groovy", true));

    /** Any path containing one of these segments is skipped outright. */
    private static final List<String> EXCLUDED_SEGMENTS = List.of(
            "/target/", "/build/", "/out/", "/bin/", "/dist/",
            "/node_modules/", "/.git/", "/.idea/", "/.gradle/", "/.mvn/wrapper/",
            "/vendor/", "/__pycache__/", "/.venv/", "/generated/", "/generated-sources/");

    /** Extensions that are either binary or machine-generated noise. */
    private static final List<String> EXCLUDED_EXTENSIONS = List.of(
            "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "pdf",
            "zip", "tar", "gz", "tgz", "jar", "war", "ear", "class", "so", "dll", "dylib",
            "exe", "bin", "woff", "woff2", "ttf", "eot", "mp4", "mp3", "avi",
            "lock", "sum", "pem", "key", "p12", "jks", "keystore");

    /** Files above this size are skipped: almost always generated or vendored. */
    public static final int MAX_INDEXABLE_BYTES = 512 * 1024;

    private Languages() {
    }

    /** {@code true} when the path is worth reading and embedding. */
    public static boolean isIndexable(String path) {
        String normalized = "/" + path.replace('\\', '/');
        for (String segment : EXCLUDED_SEGMENTS) {
            if (normalized.contains(segment)) {
                return false;
            }
        }
        String ext = extensionOf(path);
        if (EXCLUDED_EXTENSIONS.contains(ext)) {
            return false;
        }
        // Lock files and minified bundles carry no answerable information.
        String fileName = fileNameOf(path);
        return !fileName.endsWith("-lock.json")
                && !fileName.endsWith(".min.js")
                && !fileName.endsWith(".min.css")
                && (!ext.isEmpty() || BY_FILENAME.containsKey(fileName));
    }

    /** Language for a path, falling back to a generic textual spec. */
    public static Spec of(String path) {
        Spec byName = BY_FILENAME.get(fileNameOf(path));
        if (byName != null) {
            return byName;
        }
        return Optional.ofNullable(BY_EXTENSION.get(extensionOf(path))).orElse(TEXTUAL);
    }

    public static String extensionOf(String path) {
        String fileName = fileNameOf(path);
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? ""
                : fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    public static String fileNameOf(String path) {
        int slash = path.replace('\\', '/').lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
