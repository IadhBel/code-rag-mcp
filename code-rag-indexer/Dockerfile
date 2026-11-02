# Build context is the repository root:
#   docker build -f code-rag-indexer/Dockerfile -t code-rag-indexer .
#
# ---------------------------------------------------------------------------
# Stage 1 -- build
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build

# Dependency resolution is cached separately from source so that editing code
# does not re-download the world on every build.
COPY pom.xml ./
COPY code-rag-core/pom.xml code-rag-core/
COPY code-rag-indexer/pom.xml code-rag-indexer/
COPY code-rag-mcp/pom.xml code-rag-mcp/
RUN mvn -q -B -pl code-rag-indexer -am dependency:go-offline -DskipTests

COPY code-rag-core/src code-rag-core/src
COPY code-rag-indexer/src code-rag-indexer/src
RUN mvn -q -B -pl code-rag-indexer -am package -DskipTests

# Explode the layered jar so Docker can cache dependencies apart from our code.
RUN cp code-rag-indexer/target/code-rag-indexer-*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------
# Stage 2 -- runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-noble AS runtime

# git is a hard runtime requirement: the job clones the repository itself.
RUN apt-get update \
 && apt-get install -y --no-install-recommends git ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Never run as root. Cloud Run does not require it, and a job that clones
# arbitrary repositories has no business owning its own filesystem.
RUN useradd --system --create-home --uid 10001 --shell /usr/sbin/nologin coderag
USER 10001
WORKDIR /app

COPY --from=builder --chown=10001:10001 /build/extracted/dependencies/ ./
COPY --from=builder --chown=10001:10001 /build/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=10001:10001 /build/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=10001:10001 /build/extracted/application/ ./

# Container-aware heap sizing: Cloud Run Jobs memory is a hard limit, and the OOM
# killer gives no stack trace.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
