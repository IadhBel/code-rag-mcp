package com.acme.coderag.core.store;

import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotIn;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Not;
import dev.langchain4j.store.embedding.filter.logical.Or;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Translates a LangChain4j {@link Filter} into a SQL predicate over
 * {@code code_chunk}.
 *
 * <p>This exists so narrowing goes through the framework's own filter algebra --
 * which is what {@code EmbeddingStoreContentRetriever} and every other LangChain4j
 * component speak -- rather than a bespoke filter type only our own code
 * understands.
 *
 * <p><strong>Keys are whitelisted, values are always bound.</strong> A metadata key
 * is looked up in a fixed map to a column name; anything else is rejected. Nothing a
 * caller supplies is ever concatenated into SQL. This matters more than usual here:
 * on the MCP server, filter values arrive from an LLM's tool call.
 *
 * <p>Only the operators that make sense over these columns are supported. Range
 * comparisons are rejected rather than half-implemented: an agent filtering
 * {@code start_line > 40} is a mistake worth reporting, not a query worth serving.
 */
public final class MetadataFilterSql {

    private static final Map<String, String> COLUMNS = Map.of(
            CodeChunks.REPO, "repo_slug",
            CodeChunks.PATH, "path",
            CodeChunks.CHUNK_SHA, "chunk_sha",
            CodeChunks.KIND, "kind",
            CodeChunks.SYMBOL, "symbol",
            CodeChunks.LANGUAGE, "language",
            CodeChunks.COMMIT_SHA, "commit_sha",
            CodeChunks.CHUNK_INDEX, "chunk_index");

    private MetadataFilterSql() {
    }

    /**
     * @param sql    a parenthesised boolean expression, never blank
     * @param params positional parameters in the order they appear in {@code sql}
     */
    public record Predicate(String sql, List<Object> params) {
    }

    /** @return {@code null} when {@code filter} is null, meaning "no narrowing" */
    public static Predicate translate(Filter filter, String alias) {
        if (filter == null) {
            return null;
        }
        List<Object> params = new ArrayList<>();
        String sql = render(filter, alias, params);
        return new Predicate(sql, List.copyOf(params));
    }

    private static String render(Filter filter, String alias, List<Object> params) {
        return switch (filter) {
            case And and -> "(" + render(and.left(), alias, params) + " AND "
                    + render(and.right(), alias, params) + ")";
            case Or or -> "(" + render(or.left(), alias, params) + " OR "
                    + render(or.right(), alias, params) + ")";
            case Not not -> "(NOT " + render(not.expression(), alias, params) + ")";
            case IsEqualTo eq -> equality(eq.key(), eq.comparisonValue(), alias, params, true);
            case IsNotEqualTo ne -> equality(ne.key(), ne.comparisonValue(), alias, params, false);
            case IsIn in -> membership(in.key(), in.comparisonValues(), alias, params, true);
            case IsNotIn nin -> membership(nin.key(), nin.comparisonValues(), alias, params, false);
            default -> throw new IllegalArgumentException("unsupported filter "
                    + filter.getClass().getSimpleName() + "; this store supports "
                    + "isEqualTo, isNotEqualTo, isIn, isNotIn and their and/or/not combinations");
        };
    }

    private static String equality(String key, Object value, String alias,
                                   List<Object> params, boolean positive) {
        if (CodeChunks.PATH_PREFIX.equals(key)) {
            params.add(likePrefix(String.valueOf(value)));
            return alias + ".path " + (positive ? "LIKE" : "NOT LIKE") + " ? ESCAPE '\\'";
        }
        params.add(value);
        return alias + "." + column(key) + (positive ? " = ?" : " <> ?");
    }

    private static String membership(String key, Collection<?> values, String alias,
                                     List<Object> params, boolean positive) {
        if (values.isEmpty()) {
            // An empty IN list is illegal SQL, and its meaning is unambiguous.
            return positive ? "false" : "true";
        }
        if (CodeChunks.PATH_PREFIX.equals(key)) {
            throw new IllegalArgumentException(
                    CodeChunks.PATH_PREFIX + " supports isEqualTo only");
        }
        StringBuilder sql = new StringBuilder(alias).append('.').append(column(key))
                .append(positive ? " IN (" : " NOT IN (");
        for (int i = 0; i < values.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        params.addAll(values);
        return sql.append(')').toString();
    }

    private static String column(String key) {
        String column = COLUMNS.get(key);
        if (column == null) {
            throw new IllegalArgumentException("unknown metadata key '" + key
                    + "'; filterable keys are " + COLUMNS.keySet()
                    + " plus " + CodeChunks.PATH_PREFIX);
        }
        return column;
    }

    /**
     * A path containing an underscore is extremely common, and unescaped it would
     * match any character. Escaped with a backslash, declared via {@code ESCAPE}
     * because PostgreSQL's default depends on {@code standard_conforming_strings}.
     */
    private static String likePrefix(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
