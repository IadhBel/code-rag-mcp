# code-rag

Commit-scoped semantic search over your source code, exposed to IDEs as a remote
MCP server.

The problem it solves: an AI agent asked about a codebase reads files to find out
what is in them, and pays for those files in context tokens on every prompt. This
indexes each commit once, then answers "where is X implemented" with a path, a line
range and eighteen lines of code.

---

## Two deployables, one shared library

```
code-rag/
├── code-rag-core/      chunking, hashing, embedding models, vector store  (library)
├── code-rag-indexer/   WRITE side — Cloud Run Job, triggered by the pipeline
└── code-rag-mcp/       READ side  — remote MCP server, wired into the IDE
```

They are separate containers with separate service accounts and separate lifecycles.
They live in **one repository** on purpose: the writer and the reader must agree
byte-for-byte on how code is chunked and hashed, and on which embedding model and
dimension are in use. Split them into two repositories and the first time those
drift, retrieval quality degrades with no error anywhere. Sharing the code makes that
impossible; `SchemaGuard` catches the configuration half at start-up.

**Data flow**

```
GitHub Actions workflow (application repo)
        │  gcloud run jobs execute   ← only permission it holds
        ▼
code-rag-indexer  ──clones the commit──►  chunks ──► embeds (Vertex AI) ──► pgvector
        │                                                                      │
        │  owns the Flyway migrations                                          │
        ▼                                                                      ▼
    Cloud SQL (private IP, no public address) ◄──── code-rag-mcp (read-only role)
                                                            │
                                                            ▼
                                                    IDE / Claude Code
```

No GitHub runner ever holds a database credential, a Vertex AI credential, or a
long-lived service account key. Authentication to Google Cloud is keyless via OIDC.

---

## How "no duplication" actually works

Three filters, each cheaper than the work it avoids.

**1. File level — the git blob SHA.** `git ls-tree` hands us the object id of every
file for free. Unchanged id, and the file is not read from disk, let alone chunked or
embedded. On a normal commit this eliminates nearly everything.

**2. Chunk level — the content hash.** Chunks are methods, not fixed windows, and each
carries the SHA-256 of its normalised text. Editing one method in a forty-method class
invalidates one chunk. And because the type chunk is a *skeleton* — declaration,
fields, and member signatures with bodies elided — changing a method body does not even
invalidate the class chunk.

**3. Global level — content-addressed vectors.** Embeddings live in their own table
keyed by `(chunk_sha, model_id)`, with `INSERT … ON CONFLICT DO NOTHING`. The Vertex AI
call happens only when the vector is genuinely absent, so the same generated DTO copied
into three repositories is embedded once, ever.

Source text is stored exactly once, in `code_file.content`. Chunks store line ranges,
not text. Snippets and `read_lines` are sliced server-side inside Postgres, so a
fifteen-line preview transfers fifteen lines.

Every run writes an `index_run` row with `chunks_embedded` against `chunks_reused`, so
the saving is a number you can query rather than a claim in a README. The integration
test asserts it: a one-line body edit re-embeds exactly one chunk.

---

## The tools, and what they cost

| Tool | Returns | Approximate cost |
|---|---|---|
| `list_repositories` | indexed repos + the commit each is at | ~100 tokens |
| `search_code` | ranked `path:start-end`, symbol, signature, snippet | ~150 tokens/hit |
| `get_file_outline` | every declaration and line range, no bodies | ~200 tokens for a 600-line class |
| `read_lines` | exact server-sliced range | what you ask for |
| `find_symbol` | fuzzy declaration lookup, no code | ~50 tokens/hit |

The intended path is *locate → outline → read the two ranges that matter*, which costs
roughly an order of magnitude less than reading the files.

**Search is hybrid.** Dense vectors handle intent ("where do we retry failed
payments"); Postgres full-text handles exact identifiers. Neither alone is enough on
source code — embeddings routinely miss a method the user named precisely. Each half is
a LangChain4j `ContentRetriever` — the dense one is the framework's
`EmbeddingStoreContentRetriever` over our `EmbeddingStore`, the lexical one is a
`tsvector` query — and the two result lists are fused by LangChain4j's
`DefaultContentAggregator`, which is Reciprocal Rank Fusion. Ranks rather than scores,
because cosine distance and `ts_rank_cd` are on unrelated scales. Identifiers are
word-split on both sides (`findByAccountId` → `find By Account Id`) so a query for
"find account" reaches it.

Consequence worth knowing: a `search_code` hit carries a **rank**, not a score. There is
no meaningful number to expose — RRF's output is a fused ordering, and the two inputs'
own scores are not comparable to each other.

**No LLM at query time.** No agent inside the MCP server, no `ask_codebase`. Retrieval
is deterministic, free per call, auditable and replayable — and the agent asking the
questions is already an agent. `CodeSearchService` leaves a clean seam if that ever
changes.

---

## Running locally

No Google Cloud project needed. The `local` profile uses hash-derived embeddings
(`EmbeddingModels.deterministic`) — they exercise the schema, the deduplication and the
SQL end to end, and produce **meaningless search rankings**. That is the point: it is
for verifying plumbing, not quality. It is a plain LangChain4j `EmbeddingModel`, so
nothing downstream knows the difference; `SchemaGuard` refuses to serve such an index
unless explicitly allowed.

```bash
docker compose -f local/compose.yaml up -d

# Index something
REPO_URL=https://github.com/acme/example.git COMMIT_SHA=<sha> \
  mvn -pl code-rag-indexer -am spring-boot:run -Dspring-boot.run.profiles=local

# Serve it
mvn -pl code-rag-mcp -am spring-boot:run -Dspring-boot.run.profiles=local
```

Tests:

```bash
mvn test        # unit: the chunker, offline, seconds
mvn verify      # + integration: Testcontainers pgvector, needs Docker
```

Production setup — Cloud SQL, Cloud Run, Workload Identity Federation, IDE wiring —
is in [docs/GCP-SETUP.md](docs/GCP-SETUP.md).

---

## Versions

Pinned in one place, the parent `pom.xml` `<properties>` block.

| | Version | Why |
|---|---|---|
| Java | 25 (LTS) | current LTS; Spring Boot 4.1 supports 17–26 |
| Spring Boot | 4.1.0 | the line Spring AI 2.0 targets |
| Spring AI | 2.0.0 | `@McpTool`, streamable HTTP. **MCP protocol layer only** |
| LangChain4j | 1.11.0-beta19 | embeddings, vector store, retrieval, rank fusion |
| PostgreSQL | 17 + pgvector | HNSW, `tsvector`, `pg_trgm` |

**Why two AI frameworks, and where the line is.** LangChain4j serves MCP over stdio
only, which cannot be deployed — Cloud Run supports streamable HTTP and not stdio. So
the split is by layer, and neither framework is used for the other's job:

- **Spring AI — the protocol edge.** `@McpTool`, `@McpToolParam`, JSON schema generated
  from method signatures, streamable HTTP transport, DI and configuration binding. None
  of its model or vector-store abstractions are used.
- **LangChain4j — retrieval.** `EmbeddingModel` (`VertexAiEmbeddingModel` for real
  vectors), `EmbeddingStore<TextSegment>`, the `Filter` algebra for narrowing,
  `EmbeddingStoreContentRetriever`, and `DefaultContentAggregator` for fusion.

There is no adapter class between them: the `@McpTool` methods call a service that
speaks LangChain4j types directly.

**Four LangChain4j abstractions were deliberately not adopted**, each for a reason
written into the javadoc at the point of the decision:

| Not used | Because |
|---|---|
| `EmbeddingStoreIngestor` | no seam between "embed" and "store", so it cannot skip a chunk whose vector already exists — and skipping is the whole point |
| `PgVectorEmbeddingStore` | duplicates source text, filters via JSONB, and drops both the `tsvector` half and content-addressed vectors |
| a caching `EmbeddingModel` decorator | the contract is one vector returned per input, so cache hits would have to be read back out of Postgres (~300 MB on a 100k-chunk re-index) |
| `DocumentSplitters.recursive` | trims segments, so line ranges drift and `read_lines` returns the wrong code; and it extracts no symbols, which would kill `get_file_outline` and `find_symbol` |

Writes to `CodeEmbeddingStore` throw: the interface cannot carry a blob SHA, file
content or commit, so only `code-rag-indexer` writes the corpus.

**LangGraph4j is not used.** The five tools are pure retrieval — SQL and pgvector, no
LLM, no state, no cycles. There is nothing to orchestrate. A graph would only earn its
place with a server-side agentic `ask_codebase`, which was rejected above.

---

## Verification status

Honest accounting of what has and has not been executed. `mvn test` is green
(8/8, Maven 3.9.16, JDK 25); `mvn verify` has never run.

**Proved by a real build.** `mvn test` resolves every dependency from Maven Central and
compiles all four modules plus test sources, which settles the API surface the framework
refactor leans on. Confirmed present and used as written: `EmbeddingStore<TextSegment>`
and its full abstract method set (satisfied by `CodeEmbeddingStore`),
`EmbeddingSearchRequest` / `EmbeddingSearchResult` / `EmbeddingMatch`, the `Filter`
algebra (`And`, `Or`, `Not`, `IsEqualTo`, `IsNotEqualTo`, `IsIn`, `IsNotIn`) and
`MetadataFilterBuilder.metadataKey`, `ContentRetriever` /
`EmbeddingStoreContentRetriever.builder()` / `Content` / `Query`, `ContentAggregator` and
`DefaultContentAggregator`, `VertexAiEmbeddingModel.builder().taskType(...)`, and Spring
AI's `@McpTool` / `@McpToolParam` from `org.springframework.ai.mcp.annotation`.

**Proved by running it.** The chunker: one chunk per method with javadoc and annotations
attached, a class skeleton with bodies elided, nested-type symbol paths
(`Outer.Inner#method`), no bogus symbols from `if`/`for`/lambdas/anonymous classes,
braces inside string literals and text blocks not shifting boundaries, deterministic
output, and a hash stable across trailing-whitespace changes. Identifier
word-splitting including acronym runs (`parseHTTPResponse` → `parse HTTP Response`).

**What the first build found**, none of which was reachable by inspection — four real
defects, now fixed:

1. `langchain4j-bom:1.11.0-beta19` does not exist. The stable line is `1.19.0`; its BOM
   maps the beta-track integration modules onto `1.19.0-beta29`, so the parent pins the
   stable number only.
2. Testcontainers 2.x renamed its modules: `testcontainers-postgresql` /
   `testcontainers-junit-jupiter`, not `postgresql` / `junit-jupiter`.
3. Spring Boot 4 moved `FlywayConfigurationCustomizer` to
   `org.springframework.boot.flyway.autoconfigure`, in `spring-boot-starter-flyway`
   rather than `spring-boot-autoconfigure`.
4. An XML comment in `code-rag-core/pom.xml` contained `--`, which is illegal and made
   the POM unparseable.

It also turned two chunker tests red, which was a genuine bug rather than a regression:
`minChunkTokens` was folding short methods into the enclosing type chunk. Since
`get_file_outline` and `find_symbol` read declarations out of `code_chunk`, a folded
method is invisible to the two cheapest tools in the server. The floor now applies to
unstructured leftovers only — see `ChunkingOptions.minChunkTokens`.

**Never executed: everything that needs a database.** Docker is unavailable here, so
`mvn verify` has not run, which leaves untested `V1__init.sql`, the hybrid search
queries, `MetadataFilterSql`'s generated predicates, the `string_to_array` slicing, and
the deduplication accounting that `IndexServiceIT` asserts (it compiles). No call has
been made to Vertex AI. Run `mvn verify` on a machine with Docker first; that
integration test is the fastest way to find anything left in this list.

## Known limitations

- **Filtered vector search post-filters.** pgvector applies a repository filter inside
  the HNSW scan, which costs recall. Mitigated by fetching 3× candidates when a filter
  is present and by `hnsw.ef_search = 120` on every pooled connection. AlloyDB's inline
  filtering is the proper fix at scale.
- **Type chunks are not contiguous.** A class skeleton elides method bodies, so its
  snippet — sliced from the file — shows the raw class head rather than the elided form
  that was embedded. Matching is unaffected; only the preview differs.
- **Structural chunking is brace-based, not a parser.** Good for Java, Kotlin,
  TypeScript, Go, C#. A JavaScript regex literal containing an unbalanced brace can
  still shift a boundary. Swapping in JavaParser for Java specifically is the upgrade
  path, at the cost of a dependency and per-language work.
- **Branches other than the default are not indexed.** Indexing every feature branch
  would multiply the corpus and dilute retrieval with code that may never merge.
