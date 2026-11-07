# syntax=docker/dockerfile:1.7
# ==============================================================================
# PILAR 03: Observabilidade "Zero-Touch"
# App: "Qual a hora atual na timezone XYZ?" (Java 25 LTS Moderna + OTel Java Agent + Trixie-Slim)
# Build (Podman):
#   podman build -f Containerfile.java -t tz-app-java:pilar03 .
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

# [Observabilidade] Na geração da JRE customizada via jlink para o Java 25 LTS,
# incluímos 'jdk.instrument', 'java.management' e 'jdk.jfr' (Java Flight Recorder)
# para suportar o Java Agent do OpenTelemetry e Continuous Profiling sem ferramentas externas.
RUN jdeps --ignore-missing-deps -q --recursive --multi-release 25 \
    --print-module-deps /build/app.jar > /tmp/modules.txt && \
    jlink \
      --add-modules "$(cat /tmp/modules.txt),jdk.instrument,java.management,jdk.jfr,java.net.http" \
      --no-man-pages \
      --no-header-files \
      --compress=zip-6 \
      --output /opt/jre-observability

# [Observabilidade] Baixa a versão de release exata do OpenTelemetry Java Agent (v2.33.0) para auto-instrumentação
ADD --chmod=0444 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.33.0/opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar


# --- ESTÁGIO FINAL: Debian 13 (Trixie-Slim) + Auto-Instrumentação OTel & eBPF ---
FROM docker.io/library/debian:trixie-slim AS runtime

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    tzdata \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* \
    && useradd -u 65532 -r -g nogroup -d /app -s /sbin/nologin nonroot

WORKDIR /app

COPY --from=builder --chown=65532:65532 /opt/jre-observability /opt/jre
COPY --from=builder --chown=65532:65532 /opt/otel/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY --from=builder --chown=65532:65532 /build/app.jar /app/app.jar

# [Observabilidade]
# - javaagent: Injeta spans, métricas RED e trace_id/span_id no MDC dos logs JSON em stdout.
# - -XX:+PreserveFramePointer & -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints:
#   Permite que agentes de Continuous Profiling via eBPF no nó (Parca / Pyroscope) reconstruam
#   a stack trace do JIT da JVM 25 perfeitamente de fora para dentro (< 1% de overhead).
ENV PATH="/opt/jre/bin:$PATH" \
    JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar -XX:+PreserveFramePointer -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints" \
    OTEL_SERVICE_NAME="tz-app-java" \
    OTEL_TRACES_EXPORTER="otlp" \
    OTEL_METRICS_EXPORTER="otlp" \
    OTEL_LOGS_EXPORTER="otlp" \
    LOG_FORMAT="json"

USER 65532:65532
EXPOSE 8080

# [Observabilidade] Sonda de Saúde Sem Shell e Sem Curl (Native Probe):
# Invoca uma classe utilitária interna ou modo '--healthcheck' do próprio JAR sem precisar de /bin/sh.
HEALTHCHECK --interval=10s --timeout=2s --start-period=5s --retries=3 \
    CMD ["/opt/jre/bin/java", "-XX:TieredStopAtLevel=1", "-cp", "/app/app.jar", "io.kirimure.tz.HealthProbe"]

ENTRYPOINT ["/opt/jre/bin/java", "-jar", "/app/app.jar"]
