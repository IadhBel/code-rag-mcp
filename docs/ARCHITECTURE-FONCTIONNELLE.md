# Architecture fonctionnelle — code-rag

> Document de référence pour comprendre la structure, les responsabilités et les choix
> technologiques du projet **code-rag**.

---

## 1. Problème résolu

Un agent IA qui explore un codebase lit souvent des fichiers entiers à chaque question,
ce qui consomme des tokens de contexte de façon répétée et coûteuse.

**code-rag** indexe chaque commit une seule fois, puis répond à « où est implémenté X ? »
avec :

- le chemin du fichier ;
- la plage de lignes ;
- un extrait court (≈ 18 lignes).

L'agent IDE n'a plus besoin de lire des fichiers entiers pour localiser du code.

---

## 2. Vue d'ensemble

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         REPOSITORY GitHub (app métier)                      │
│  push sur branche default → job CI `.code-rag-index`                        │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │ OIDC → Workload Identity Federation
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    code-rag-indexer (Cloud Run Job)                         │
│  clone commit → chunk → embed (Vertex AI) → écriture pgvector                 │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│              Cloud SQL PostgreSQL 17 + pgvector (VPC privée)                │
│  code_repository │ code_file │ code_chunk │ code_embedding │ index_run      │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │ SELECT only (rôle lecture)
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    code-rag-mcp (Cloud Run Service)                         │
│  Spring AI MCP (HTTP streamable) → 5 outils de recherche                    │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │ MCP over HTTPS + Bearer token
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         IDE / Claude Code / Cursor                          │
│  Agent appelle search_code → get_file_outline → read_lines                  │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Modules Maven

| Module | Rôle | Déploiement |
|---|---|---|
| **code-rag-core** | Bibliothèque partagée : chunking, hashing, modèles d'embedding, `EmbeddingStore` | JAR interne |
| **code-rag-indexer** | Côté **ÉCRITURE** : indexe un commit, écrit en base | Cloud Run **Job** (batch) |
| **code-rag-mcp** | Côté **LECTURE** : expose la recherche via MCP | Cloud Run **Service** (HTTP) |

### Pourquoi un seul repo ?

L'indexeur et le serveur MCP doivent être **byte-for-byte alignés** sur :

- la stratégie de chunking ;
- le calcul des hashes ;
- le modèle d'embedding et sa dimension.

Un drift silencieux entre deux repos dégraderait la qualité de recherche sans erreur visible.
Le module `code-rag-core` partagé rend ce drift impossible ; `SchemaGuard` vérifie la
configuration au démarrage du MCP.

---

## 4. Séparation CQRS (Read / Write)

| Aspect | Indexer (write) | MCP (read) |
|---|---|---|
| Container | Cloud Run Job | Cloud Run Service |
| Service account GCP | Accès Vertex AI + DB writer | Accès Vertex AI (query) + DB reader |
| Rôle PostgreSQL | INSERT/UPDATE/DELETE + Flyway | SELECT uniquement |
| Durée de vie | Démarre, indexe, termine (exit code) | Service long-running |
| LLM à l'exécution | Non (embeddings seulement) | Non (retrieval pur) |

Aucun runner GitHub ne détient de credentials DB, Vertex AI ou clé de service account.
L'authentification GCP est **keyless** via OIDC (Workload Identity Federation).

---

## 5. Couche données (PostgreSQL + pgvector)

### Tables principales

```
code_repository ──< code_file ──< code_chunk >── code_embedding
                      │
                      └── content (texte source unique)
```

| Table | Contenu | Rôle |
|---|---|---|
| `code_repository` | slug, dernier commit indexé, stats | Registre des repos |
| `code_file` | contenu source, blob SHA git, langage | **Une seule copie** du texte source |
| `code_chunk` | plage de lignes, symbole, signature, `tsvector` lexical | Unité retrievable |
| `code_embedding` | vecteur 768-dim, clé `(chunk_sha, model_id)` | Vecteurs content-addressed |
| `index_run` | stats par exécution (`chunks_embedded` vs `chunks_reused`) | Observabilité |

### Index

- **HNSW** sur `code_embedding.vector` (similarité cosinus)
- **GIN** sur `code_chunk.lexical` (full-text PostgreSQL)
- **GIN trigram** sur `code_chunk.symbol` (recherche floue de symboles)

Les snippets et `read_lines` sont découpés **côté serveur** dans Postgres à partir de
`code_file.content` — jamais stockés en double.

---

## 6. Pipeline d'indexation

```
git clone (shallow, commit exact)
    │
    ▼
git ls-tree → liste fichiers + blob SHA
    │
    ▼
Filtre 1 : extensions / taille / dossiers exclus (node_modules, target, etc.)
    │
    ▼
Filtre 2 : blob SHA inchangé → fichier ignoré (pas lu du disque)
    │
    ▼
Chunking structurel (BraceStructuralChunker)
    │  METHOD  : une méthode/constructeur par chunk
    │  TYPE    : squelette de classe (corps éliminés)
    │  TEXT    : package/imports et restes
    │
    ▼
Filtre 3 : chunk_sha déjà embeddé pour ce model_id → skip Vertex AI
    │
    ▼
Vertex AI gemini-embedding-001 (RETRIEVAL_DOCUMENT)
    │
    ▼
INSERT … ON CONFLICT DO NOTHING → écriture atomique
```

### Déduplication à trois niveaux

1. **Fichier** — blob SHA git : fichier inchangé = zero I/O
2. **Chunk** — SHA-256 du texte normalisé : modifier une méthode = un seul re-embed
3. **Global** — vecteurs keyed par `(chunk_sha, model_id)` : code identique dans N repos = un embed

---

## 7. Couche recherche (MCP)

### Transport MCP

- **Protocole** : Spring AI MCP over HTTP streamable (`/mcp`)
- **Sécurité externe** : Cloud Run IAM (`--no-allow-unauthenticated`)
- **Sécurité interne** : `BearerTokenFilter` avec `MCP_READ_TOKEN`
- **Garde-fou** : `SchemaGuard` refuse de démarrer si le modèle d'embedding ne correspond pas à l'index

### Les 5 outils MCP

| Outil | Retour | Coût approx. |
|---|---|---|
| `list_repositories` | Repos indexés + commit SHA | ~100 tokens |
| `search_code` | Hits hybrides (path, symbole, snippet, rank) | ~150 tokens/hit |
| `get_file_outline` | Déclarations + plages de lignes, sans corps | ~20 tokens/décl |
| `read_lines` | Tranche exacte de lignes (max 400) | proportionnel |
| `find_symbol` | Lookup flou de symbole (pg_trgm) | ~50 tokens/hit |

**Parcours recommandé pour l'agent** :

```
search_code / find_symbol  →  get_file_outline  →  read_lines (2-3 plages)
```

Coût ≈ 10× inférieur à la lecture de fichiers entiers.

### Recherche hybride

```
                    Query utilisateur
                          │
            ┌─────────────┴─────────────┐
            ▼                           ▼
   Dense (pgvector HNSW)        Lexical (tsvector)
   EmbeddingStoreContentRetriever   LexicalContentRetriever
   intent: "où retry les paiements"  identifiants: "findByAccountId"
            │                           │
            └─────────────┬─────────────┘
                          ▼
              DefaultContentAggregator
              (Reciprocal Rank Fusion)
                          │
                          ▼
                   List<SearchHit>
                   (rank, pas score)
```

**Pourquoi RRF et pas un score fusionné ?** Les distances cosinus et `ts_rank_cd` ne sont
pas sur la même échelle. La fusion par rang est la bonne approche.

**Pas de LLM à la requête** — retrieval déterministe, auditable, reproductible.

---

## 8. Répartition Spring AI vs LangChain4j

```
┌──────────────────────────────────────────────────────────────┐
│                    code-rag-mcp                              │
│  ┌────────────────────────┐  ┌─────────────────────────────┐ │
│  │     Spring AI          │  │      LangChain4j            │ │
│  │  ─────────────────     │  │  ───────────────────────    │ │
│  │  @McpTool              │  │  EmbeddingModel             │ │
│  │  @McpToolParam         │  │  EmbeddingStore<TextSegment>│ │
│  │  JSON schema auto      │  │  ContentRetriever           │ │
│  │  HTTP streamable MCP   │  │  ContentAggregator (RRF)    │ │
│  │  DI / config Spring    │  │  Filter algebra → SQL       │ │
│  └────────────────────────┘  └─────────────────────────────┘ │
│           │                              ▲                     │
│           │   CodeRagTools → CodeSearchService                 │
│           └──────────────────────────────┘                     │
└──────────────────────────────────────────────────────────────┘
```

| Framework | Couche | Abstractions utilisées |
|---|---|---|
| **Spring AI 2.0** | Protocole MCP | `@McpTool`, transport HTTP streamable |
| **LangChain4j 1.19** | Retrieval RAG | `EmbeddingModel`, `EmbeddingStore`, `ContentRetriever`, `DefaultContentAggregator` |

**Pas d'adaptateur entre les deux** : les méthodes `@McpTool` appellent directement des
services qui parlent LangChain4j.

### Abstractions LangChain4j volontairement non adoptées

| Non utilisé | Raison |
|---|---|
| `EmbeddingStoreIngestor` | Pas de seam pour skip les chunks déjà embeddés |
| `PgVectorEmbeddingStore` | Duplique le texte, filtre via JSONB, perd le lexical |
| Decorator cache `EmbeddingModel` | Le cache est déjà dans Postgres (content-addressed) |
| `DocumentSplitters.recursive` | Perd les plages de lignes et les symboles |

---

## 9. Chunking structurel

Basé sur les accolades (pas un parser AST complet) :

- **Langages supportés** : Java, Kotlin, TypeScript, Go, C#
- **Kinds** :
  - `METHOD` — méthode/constructeur avec javadoc et annotations
  - `TYPE` — squelette de classe (signatures, corps remplacés par `// ...`)
  - `TEXT` — imports, package, restes non structurés

Le chunk `TYPE` est un squelette : modifier le corps d'une méthode n'invalide **pas** le
chunk de classe — seulement le chunk `METHOD`.

---

## 10. Modèle d'embedding

| Contexte | Task type Vertex AI | Instance |
|---|---|---|
| Indexation | `RETRIEVAL_DOCUMENT` | `code-rag-indexer` |
| Recherche | `RETRIEVAL_QUERY` | `code-rag-mcp` |

- Modèle : `gemini-embedding-001`
- Dimension : 768 (L2-normalisé via `NormalizedEmbeddingModel`)
- Profil local : embeddings déterministes (hash-based) pour tester le plumbing sans GCP

---

## 11. Sécurité

```
Couche 1 : Cloud Run IAM          → seuls les appelants autorisés atteignent le service
Couche 2 : Bearer MCP_READ_TOKEN  → token applicatif interne
Couche 3 : Rôle DB read-only      → le MCP ne peut pas modifier l'index
Couche 4 : VPC privée             → Cloud SQL sans IP publique
Couche 5 : OIDC keyless           → pas de clé SA sur les runners GitHub
```

---

## 12. Évaluation de la qualité du projet

### Points forts

- **Architecture claire** : séparation write/read, responsabilités bien définies
- **Frontière framework intelligente** : Spring AI pour MCP (stdio impossible en Cloud Run),
  LangChain4j pour le retrieval (son domaine)
- **Déduplication à trois niveaux** : design mature, mesurable via `index_run`
- **Recherche hybride** : dense + lexical + RRF, adaptée au code source
- **Sécurité en profondeur** : OIDC, IAM, bearer token, rôle DB read-only
- **Documentation inline** : javadoc explique chaque choix de design
- **Content-addressed storage** : même code dans N repos = un seul embed

### Points d'attention

| Sujet | Détail |
|---|---|
| Tests d'intégration | `mvn verify` (Testcontainers) jamais exécuté — SQL et dedup non validés en CI |
| Vertex AI prod | Aucun appel réel testé end-to-end |
| Branches feature | Seule la branche default est indexée |
| Chunking brace-based | Pas un vrai parser — edge cases possibles (regex JS, etc.) |
| pgvector filtered search | Post-filtrage dans HNSW → recall réduit ; mitigé par 3× candidates |
| Pas de webhook | Déclenchement CI uniquement, pas de re-index manuel via API |
| Index stale | Détection manuelle via `list_repositories` + comparaison commit SHA |

### Verdict

Le projet est **bien conçu et bien documenté**. L'utilisation de Spring AI et LangChain4j
est **correcte et intentionnelle** — chaque framework couvre exactement la couche où il
excelle, sans duplication ni glue inutile. Le principal risque est l'écart entre « compile
et unit tests passent » et « intégration + prod Vertex AI validés ».

---

## 13. Fichiers clés

| Fichier | Rôle |
|---|---|
| `code-rag-core/.../chunk/BraceStructuralChunker.java` | Chunking structurel |
| `code-rag-core/.../embed/EmbeddingModels.java` | Factory Vertex AI / deterministic |
| `code-rag-core/.../store/CodeEmbeddingStore.java` | `EmbeddingStore` custom sur pgvector |
| `code-rag-indexer/.../index/IndexService.java` | Orchestration indexation |
| `code-rag-indexer/.../store/IndexWriteRepository.java` | Écritures DB |
| `code-rag-mcp/.../tools/CodeRagTools.java` | Surface MCP (5 outils) |
| `code-rag-mcp/.../search/CodeSearchService.java` | Recherche hybride + RRF |
| `code-rag-mcp/.../config/SchemaGuard.java` | Validation modèle au startup |
| `.github/workflows/code-rag-index.yml` | Workflow GitHub réutilisable |
| `code-rag-indexer/.../db/migration/V1__init.sql` | Schéma PostgreSQL |
