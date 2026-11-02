# Google Cloud setup

Everything below is one-time platform setup. Once done, onboarding a new
application repository is three lines of YAML — see the bottom of this page.

Placeholders used throughout: `PROJECT_ID`, `REGION` (e.g. `europe-west1`),
`PROJECT_NUMBER`.

---

## 1. Database — Cloud SQL for PostgreSQL

```bash
gcloud sql instances create coderag-db \
  --database-version=POSTGRES_17 \
  --region=REGION \
  --tier=db-custom-2-8192 \
  --storage-auto-increase \
  --availability-type=ZONAL \
  --no-assign-ip \
  --network=projects/PROJECT_ID/global/networks/default

gcloud sql databases create coderag --instance=coderag-db
```

`--no-assign-ip` is deliberate: the database has no public address. Both services
reach it over Direct VPC egress, and no GitHub runner can reach it at all.

### Why Cloud SQL and not AlloyDB

A source-code corpus is small in vector terms — a large monorepo lands in the low
millions of chunks, where pgvector's HNSW is entirely adequate. AlloyDB's ScaNN
index becomes the right answer above roughly ten million vectors, or when filtered
vector queries become the bottleneck (its inline filtering is genuinely better than
pgvector's post-filtering, which is the known weak spot of the search here). ScaNN
is pgvector-compatible, so that migration is an index change, not a rewrite. Start
on Cloud SQL; move when a number tells you to.

### Extensions and roles

Connect as the admin user and run:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Writer: owns the schema, runs the migrations.
CREATE ROLE coderag_indexer LOGIN PASSWORD 'from-secret-manager';
GRANT ALL ON SCHEMA public TO coderag_indexer;
ALTER DATABASE coderag OWNER TO coderag_indexer;

-- Reader: the MCP server. SELECT and nothing else, so a bug on the read path
-- cannot corrupt the index no matter what.
CREATE ROLE coderag_mcp LOGIN PASSWORD 'from-secret-manager';
GRANT CONNECT ON DATABASE coderag TO coderag_mcp;
GRANT USAGE ON SCHEMA public TO coderag_mcp;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO coderag_mcp;
ALTER DEFAULT PRIVILEGES FOR ROLE coderag_indexer IN SCHEMA public
  GRANT SELECT ON TABLES TO coderag_mcp;
```

The `ALTER DEFAULT PRIVILEGES` line matters: without it, tables created by a future
migration are invisible to the reader and the MCP server starts failing after a
deploy that adds a table.

---

## 2. Secrets

```bash
for name in coderag-indexer-db-password coderag-mcp-db-password coderag-mcp-read-token; do
  gcloud secrets create "$name" --replication-policy=automatic
done

printf '%s' "$(openssl rand -base64 32)" | \
  gcloud secrets versions add coderag-mcp-read-token --data-file=-
```

The read token is what IDE clients present. Rotating it is a new secret version
plus a Cloud Run revision; nothing else changes.

---

## 3. Service accounts

```bash
# Indexer: reads Vertex AI, writes the database.
gcloud iam service-accounts create coderag-indexer
gcloud projects add-iam-policy-binding PROJECT_ID \
  --member=serviceAccount:coderag-indexer@PROJECT_ID.iam.gserviceaccount.com \
  --role=roles/aiplatform.user
gcloud projects add-iam-policy-binding PROJECT_ID \
  --member=serviceAccount:coderag-indexer@PROJECT_ID.iam.gserviceaccount.com \
  --role=roles/cloudsql.client

# MCP server: embeds queries, reads the database.
gcloud iam service-accounts create coderag-mcp
gcloud projects add-iam-policy-binding PROJECT_ID \
  --member=serviceAccount:coderag-mcp@PROJECT_ID.iam.gserviceaccount.com \
  --role=roles/aiplatform.user
gcloud projects add-iam-policy-binding PROJECT_ID \
  --member=serviceAccount:coderag-mcp@PROJECT_ID.iam.gserviceaccount.com \
  --role=roles/cloudsql.client

# Pipeline identity for application repositories: may only start an execution.
gcloud iam service-accounts create coderag-trigger
gcloud run jobs add-iam-policy-binding code-rag-indexer --region=REGION \
  --member=serviceAccount:coderag-trigger@PROJECT_ID.iam.gserviceaccount.com \
  --role=roles/run.invoker
```

Note what `coderag-trigger` deliberately cannot do: touch the database, call
Vertex AI, or read any secret. A compromised application pipeline can start an
indexing run and nothing more.

---

## 4. Artifact Registry

```bash
gcloud artifacts repositories create code-rag \
  --repository-format=docker --location=REGION
```

---

## 5. Indexer — Cloud Run Job

```bash
gcloud run jobs create code-rag-indexer \
  --region=REGION \
  --image=REGION-docker.pkg.dev/PROJECT_ID/code-rag/code-rag-indexer:latest \
  --service-account=coderag-indexer@PROJECT_ID.iam.gserviceaccount.com \
  --network=default --subnet=default --vpc-egress=private-ranges-only \
  --task-timeout=30m --max-retries=1 --memory=2Gi --cpu=1 \
  --set-env-vars=GCP_PROJECT=PROJECT_ID,GCP_LOCATION=REGION,DB_URL='jdbc:postgresql://PRIVATE_IP:5432/coderag',DB_USERNAME=coderag_indexer \
  --set-secrets=DB_PASSWORD=coderag-indexer-db-password:latest
```

`--vpc-egress=private-ranges-only` keeps database traffic inside the VPC while
still allowing the Vertex AI and GitHub calls to leave.

Per-commit variables (`REPO_SLUG`, `REPO_URL`, `COMMIT_SHA`, `GIT_TOKEN`, …) are
supplied at execution time by the pipeline, not baked into the job.

First run, by hand, to create the schema:

```bash
gcloud run jobs execute code-rag-indexer --region=REGION --wait \
  --update-env-vars="REPO_SLUG=acme/order-service,REPO_URL=https://github.com/YOUR_GITHUB_USER/order-service.git,REPO_BRANCH=main,COMMIT_SHA=<sha>,GIT_USERNAME=<user>,GIT_TOKEN=<token>"
```

---

## 6. MCP server — Cloud Run service

```bash
gcloud run deploy code-rag-mcp \
  --region=REGION \
  --image=REGION-docker.pkg.dev/PROJECT_ID/code-rag/code-rag-mcp:latest \
  --service-account=coderag-mcp@PROJECT_ID.iam.gserviceaccount.com \
  --network=default --subnet=default --vpc-egress=private-ranges-only \
  --no-allow-unauthenticated \
  --min-instances=1 --max-instances=5 --memory=1Gi --cpu=1 \
  --session-affinity \
  --set-env-vars=GCP_PROJECT=PROJECT_ID,GCP_LOCATION=REGION,DB_URL='jdbc:postgresql://PRIVATE_IP:5432/coderag',DB_USERNAME=coderag_mcp \
  --set-secrets=DB_PASSWORD=coderag-mcp-db-password:latest,MCP_READ_TOKEN=coderag-mcp-read-token:latest
```

Three flags worth explaining:

- **`--no-allow-unauthenticated`** — Cloud Run IAM is the outer gate. Without it the
  whole corpus is one URL away from anyone.
- **`--min-instances=1`** — the JVM cold start plus schema validation is several
  seconds. An IDE tool call that times out is a tool an engineer stops using.
- **`--session-affinity`** — streamable HTTP keeps a session per client. Affinity is
  best-effort on Cloud Run, so if you scale out and see session errors, switch
  `spring.ai.mcp.server.protocol` to `STATELESS`: every tool here is stateless, so
  nothing is lost.

Grant yourself invoker access:

```bash
gcloud run services add-iam-policy-binding code-rag-mcp --region=REGION \
  --member="user:you@acme.com" --role=roles/run.invoker
```

---

## 7. GitHub → Google Cloud, keyless

No service account keys. GitHub Actions presents an OIDC token, Google exchanges it for
short-lived credentials.

```bash
gcloud iam workload-identity-pools create github \
  --location=global --display-name="GitHub"

gcloud iam workload-identity-pools providers create-oidc github-oidc \
  --location=global --workload-identity-pool=github \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_owner=assertion.repository_owner,attribute.ref=assertion.ref" \
  --attribute-condition="assertion.repository_owner == 'YOUR_GITHUB_USER' && assertion.ref == 'refs/heads/main'"
```

**The attribute condition is the security control, not the audience.** Omit it and
any GitHub repository on the internet can assume your service account. Pin the
owner, and require the default branch so a fork or a feature branch cannot
trigger indexing.

```bash
POOL="projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github"

gcloud iam service-accounts add-iam-policy-binding \
  coderag-trigger@PROJECT_ID.iam.gserviceaccount.com \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/$POOL/attribute.repository_owner/YOUR_GITHUB_USER"
```

Then set these as **GitHub Actions variables** (Settings → Secrets and variables →
Actions → Variables) in each repository that calls the workflow, and the `code-rag`
repository itself (`GCP_PROJECT`, `ARTIFACT_REGISTRY`, `WIF_PROVIDER`,
`DEPLOY_SERVICE_ACCOUNT` for its own CI):

| Variable | Value |
|---|---|
| `CODE_RAG_GCP_PROJECT` | `PROJECT_ID` |
| `CODE_RAG_GCP_REGION` | `REGION` |
| `CODE_RAG_WIF_PROVIDER` | `projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github/providers/github-oidc` |
| `CODE_RAG_SERVICE_ACCOUNT` | `coderag-trigger@PROJECT_ID.iam.gserviceaccount.com` |

---

## 8. Connecting an IDE

The service requires IAM, so clients go through the local Cloud Run proxy, which
handles Google authentication:

```bash
gcloud run services proxy code-rag-mcp --region=REGION --port=8080
```

**Claude Code:**

```bash
claude mcp add code-rag --transport http \
  --header "Authorization: Bearer $MCP_READ_TOKEN" \
  http://127.0.0.1:8080/mcp
```

**IntelliJ IDEA** — add an MCP server of type *streamable HTTP* pointing at
`http://127.0.0.1:8080/mcp`, with header `Authorization: Bearer <token>`.

Verify with `list_repositories`: it should return each indexed repository and the
commit it was indexed at. If the commit is not the one you are working on, the
index is stale and results describe older code.

---

## Onboarding a new application repository

In that project, add `.github/workflows/code-rag.yml`:

```yaml
name: code-rag
on:
  push:
    branches: [main]
permissions:
  id-token: write
  contents: read
jobs:
  index:
    uses: YOUR_GITHUB_USER/code-rag/.github/workflows/code-rag-index.yml@main
    with:
      repo_slug: YOUR_GITHUB_USER/order-service   # optional
    secrets: inherit
```

That is all. No credentials to provision, no database access to grant, no new
infrastructure. The workflow runs on the default branch, hands the commit to the Cloud
Run Job, and the indexer does the rest inside the VPC.
