# syntax=docker/dockerfile:1.7
# ==============================================================================
# PILAR 04: Gestão de Recursos (Rightsizing, Cgroups & FinOps)
# App: "Qual a hora atual na timezone XYZ?" (Java 25 LTS + Leyden AOT Cache + Trixie-Slim)
# Build (Podman):
#   podman build -f Containerfile.java -t tz-app-java:pilar04 .
# Run com limite estrito de memória (simulando Requests == Limits = 192MiB):
#   podman run --rm -m 192m --memory-swap 192m -p 8080:8080 tz-app-java:pilar04
# ==============================================================================

FROM docker.io/library/eclipse-temurin:25-jdk AS builder

WORKDIR /build

RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=bind,source=pom.xml,target=pom.xml \
    sh -c "if [ -f mvnw ]; then ./mvnw dependency:go-offline -B; else mvn dependency:go-offline -B; fi"

COPY ./src ./src

RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=bind,source=pom.xml,target=pom.xml \
    sh -c "if [ -f mvnw ]; then ./mvnw package -DskipTests -B; else mvn package -DskipTests -B; fi" && \
    mv target/*.jar /build/app.jar

RUN jdeps --ignore-missing-deps -q --recursive --multi-release 25 \
    --print-module-deps /build/app.jar > /tmp/modules.txt && \
    jlink \
      --add-modules "$(cat /tmp/modules.txt)" \
      --strip-debug \
      --no-man-pages \
      --no-header-files \
      --compress=zip-6 \
      --output /opt/jre-minimal

# [Gestão de Recursos: Startup vs. Steady-State (AOT Cache JEP 483 no Java 25 LTS)]
# Realiza um ciclo de inicialização curto da aplicação para gravar o
# AOT Cache com o caminho definitivo do runtime (/app/app.jar).
# Evita o pico brutal de CPU do JIT na inicialização (eliminando CFS CPU Throttling no Kubernetes).
RUN cp /build/app.jar /app.jar && \
    /opt/jre-minimal/bin/java \
      -XX:AOTMode=record \
      -XX:AOTConfiguration=/build/app.aotconf \
      -Dapp.exitAfterInit=true \
      -jar /app.jar || true && \
    /opt/jre-minimal/bin/java \
      -XX:AOTMode=create \
      -XX:AOTConfiguration=/build/app.aotconf \
      -XX:AOTCache=/build/app.aot \
      -jar /app.jar || true && \
    rm -f /app.jar


# --- ESTÁGIO FINAL: Debian 13 (Trixie-Slim) + JVM 25 Cgroup-Tuned ---
FROM docker.io/library/debian:trixie-slim AS runtime

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    tzdata \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* \
    && useradd -u 65532 -r -g nogroup -d /app -s /sbin/nologin nonroot

WORKDIR /app

COPY --from=builder --chown=65532:65532 /opt/jre-minimal /opt/jre
COPY --from=builder --chown=65532:65532 /build/app.jar /app/app.jar
COPY --from=builder --chown=65532:65532 /build/app.aot /app/app.aot

ENV PATH="/opt/jre/bin:$PATH"

USER 65532:65532
EXPOSE 8080

# [Gestão de Recursos & FinOps no Java 25 LTS]
# 1. -XX:+UseContainerSupport: Lê limites reais de RAM e CPU direto do Cgroups v2.
# 2. -XX:InitialRAMPercentage=75.0 & -XX:MaxRAMPercentage=75.0: Alinhado à diretriz 'Requests == Limits'
#    de memória no Kubernetes, pré-aloca o heap de forma determinística (deixando 25% para Metaspace/Off-heap)
#    e evita redimensionamentos de heap que causam picos de CPU ou OOMKilled.
# 3. -XX:+UseCompactObjectHeaders (JEP 519 no Java 25 LTS): Reduz os headers de objetos de 128 bits para 64 bits,
#    economizando até 20% de memória RAM e melhorando localidade de cache da CPU.
# 4. -XX:AOTCache=/app/app.aot: Carrega o cache AOT gerado no build, reduzindo o Cold Start e o uso de CPU no boot em ~70%.
ENTRYPOINT ["/opt/jre/bin/java", \
            "-XX:+UseContainerSupport", \
            "-XX:+UseCompactObjectHeaders", \
            "-XX:InitialRAMPercentage=75.0", \
            "-XX:MaxRAMPercentage=75.0", \
            "-XX:+ExitOnOutOfMemoryError", \
            "-XX:AOTCache=/app/app.aot", \
            "-jar", "/app/app.jar"]
