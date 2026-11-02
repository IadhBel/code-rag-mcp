# Build context is the repository root:
#   docker build -f code-rag-mcp/Dockerfile -t code-rag-mcp .
#
# ---------------------------------------------------------------------------
# Stage 1 -- build
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build

COPY pom.xml ./
COPY code-rag-core/pom.xml code-rag-core/
COPY code-rag-indexer/pom.xml code-rag-indexer/
COPY code-rag-mcp/pom.xml code-rag-mcp/
RUN mvn -q -B -pl code-rag-mcp -am dependency:go-offline -DskipTests

COPY code-rag-core/src code-rag-core/src
COPY code-rag-mcp/src code-rag-mcp/src
RUN mvn -q -B -pl code-rag-mcp -am package -DskipTests

RUN cp code-rag-mcp/target/code-rag-mcp-*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------
# Stage 2 -- runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-noble AS runtime

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# No git here, unlike the indexer: this service never touches a repository.
RUN useradd --system --create-home --uid 10001 --shell /usr/sbin/nologin coderag
USER 10001
WORKDIR /app

COPY --from=builder --chown=10001:10001 /build/extracted/dependencies/ ./
COPY --from=builder --chown=10001:10001 /build/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=10001:10001 /build/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=10001:10001 /build/extracted/application/ ./

EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
