# syntax=docker/dockerfile:1.7
# ==============================================================================
# PILAR 01: Arquitetura Leve (Redução de Superfície, Zero Bloat & Velocidade)
# App: "Qual a hora atual na timezone XYZ?" (Java 25 LTS Moderna + jlink + Debian Trixie-Slim)
# Build (Podman):
#   podman build -f Containerfile.java -t tz-app-java:pilar01 .
# Exportação/Push com compressão zstd rápida no K8s:
#   podman push --compression-format=zstd localhost/tz-app-java:pilar01 docker://seu-registry/tz-app-java:pilar01
# ==============================================================================

# [Arquitetura Leve] Estágio de compilação com JDK 25 LTS fixado na versão de patch estrita
FROM docker.io/library/eclipse-temurin:25-jdk AS builder
COPY --from=docker.io/library/maven:3.9 /usr/share/maven /usr/share/maven
ENV MAVEN_HOME=/usr/share/maven \
    PATH="/usr/share/maven/bin:$PATH"

WORKDIR /build

# [Arquitetura Leve] Cache Mount para o repositório Maven (.m2) e Bind Mount do pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=bind,source=pom.xml,target=pom.xml \
    sh -c "if [ -f mvnw ]; then ./mvnw dependency:go-offline -B; else mvn dependency:go-offline -B; fi"

COPY ./src ./src

RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=bind,source=pom.xml,target=pom.xml \
    sh -c "if [ -f mvnw ]; then ./mvnw package -DskipTests -B; else mvn package -DskipTests -B; fi" && \
    mv target/*.jar /build/app.jar

# [Arquitetura Leve] jdeps + jlink (Java 25 LTS):
# Em vez de embarcar uma JRE genérica de ~200MB, analisamos os módulos exatos usados pelo
# app de fuso horário (java.base, jdk.httpserver, etc.) e geramos um runtime customizado (~38MB)
# sem símbolos de debug (--strip-debug) e sem man pages (--no-man-pages).
RUN jdeps --ignore-missing-deps -q --recursive --multi-release 25 \
    --print-module-deps --class-path 'target/lib/*' /build/app.jar > /tmp/modules.txt && \
    jlink \
      --add-modules "$(cat /tmp/modules.txt),java.desktop,java.management,java.naming,java.security.jgss,java.security.sasl,java.sql,jdk.unsupported,java.instrument" \
      --strip-debug \
      --no-man-pages \
      --no-header-files \
      --compress=zip-6 \
      --output /opt/jre-minimal


# --- ESTÁGIO FINAL: Debian 13 (Trixie-Slim) + Custom JRE 25 ---
FROM docker.io/library/debian:trixie-slim AS runtime

ENV DEBIAN_FRONTEND=noninteractive

# [Arquitetura Leve] Apenas tzdata e ca-certificates na imagem final
RUN apt-get update && apt-get install -y --no-install-recommends \
    tzdata \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* \
    && useradd -u 65532 -r -g nogroup -d /app -s /sbin/nologin nonroot

WORKDIR /app

COPY --from=builder /opt/jre-minimal /opt/jre
COPY --from=builder /build/app.jar /app/app.jar

ENV PATH="/opt/jre/bin:$PATH"

USER 65532:65532
EXPOSE 8080

# [Arquitetura Leve] JEP 483 / Compact Object Headers no Java 25 LTS para inicialização rápida
ENTRYPOINT ["java", "-XX:+UseCompactObjectHeaders", "-jar", "/app/app.jar"]
