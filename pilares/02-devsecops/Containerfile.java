# syntax=docker/dockerfile:1.7
# ==============================================================================
# PILAR 02: DevSecOps (Blindagem do Build ao Runtime)
# App: "Qual a hora atual na timezone XYZ?" (Java 25 LTS Moderna + Debian Trixie-Slim)
# Build (Podman com Secret Mount):
#   podman build --secret id=maven_settings,src=settings.xml -f Containerfile.java -t tz-app-java:pilar02 .
# ==============================================================================

# [DevSecOps] Pinning estrito por tag de patch + digest SHA256 nas bases de compilação e runtime
FROM docker.io/library/eclipse-temurin:25-jdk@sha256:3ab7fc8d0b4ade8fef96903fb36767c1dbb4fd1535dcfad85b59c17afbbb5857 AS builder

# [DevSecOps] Regra Hadolint DL4006: falha imediata em pipes
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

WORKDIR /build

# [DevSecOps] Secret Mount: O arquivo settings.xml contendo tokens do Nexus/Artifactory privado
# é montado em memória apenas durante a resolução de dependências e nunca persiste na imagem.
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml,mode=0600,required=false \
    --mount=type=cache,target=/root/.m2/repository \
    --mount=type=bind,source=pom.xml,target=pom.xml \
    sh -c "if [ -f mvnw ]; then ./mvnw dependency:go-offline -B; else mvn dependency:go-offline -B; fi"

COPY ./src ./src

# [DevSecOps] Empacotamento + geração de SBOM CycloneDX no momento do build para inspeção pelo Grype/Trivy
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml,mode=0600,required=false \
    --mount=type=cache,target=/root/.m2/repository \
    --mount=type=bind,source=pom.xml,target=pom.xml \
    sh -c "if [ -f mvnw ]; then ./mvnw package org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom -DskipTests -B; else mvn package org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom -DskipTests -B; fi" && \
    mv target/*.jar /build/app.jar

# [DevSecOps] JRE customizada via jlink: remove ferramentas de diagnóstico e binários extras (jcmd, jmap)
# que poderiam ser explorados por um atacante em pós-exploração (Living-off-the-Land).
RUN jdeps --ignore-missing-deps -q --recursive --multi-release 25 \
    --print-module-deps /build/app.jar > /tmp/modules.txt && \
    jlink \
      --add-modules "$(cat /tmp/modules.txt)" \
      --strip-debug \
      --no-man-pages \
      --no-header-files \
      --compress=zip-6 \
      --output /opt/jre-minimal

# [DevSecOps] Permissões estritamente Read-Only (0550 diretórios/executáveis, 0440 arquivos)
RUN chmod -R u=rX,g=rX,o= /opt/jre-minimal /build/app.jar


# --- ESTÁGIO FINAL: Debian 13 (Trixie-Slim) Hardened ---
FROM docker.io/library/debian:trixie-slim@sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4 AS runtime

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

ARG GIT_COMMIT="unknown"
ARG BUILD_DATE="unknown"
ARG VERSION="1.0.0"

LABEL org.opencontainers.image.title="tz-app-java" \
      org.opencontainers.image.description="Consulta de fuso horário em Java 25 LTS" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.source="https://github.com/kirimure/demo-apps" \
      security.hardening.level="trixie-slim-custom-jre25-ro"

ENV DEBIAN_FRONTEND=noninteractive

# [DevSecOps] Remoção de bits SUID/SGID e criação do usuário não-privilegiado UID 65532
RUN apt-get update && apt-get install -y --no-install-recommends \
    tzdata \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* \
    && find / -xdev -perm /6000 -type f -exec chmod a-s {} + 2>/dev/null || true \
    && useradd -u 65532 -r -g nogroup -d /app -s /sbin/nologin nonroot

WORKDIR /app

COPY --from=builder --chown=65532:65532 /opt/jre-minimal /opt/jre
COPY --from=builder --chown=65532:65532 /build/app.jar /app/app.jar
# Embarca o SBOM gerado para auditoria instantânea por scanners de imagem (Syft/Trivy)
COPY --from=builder --chown=65532:65532 /build/target/bom.json /app/sbom.cdx.json

ENV PATH="/opt/jre/bin:$PATH"

USER 65532:65532
EXPOSE 8080

# [DevSecOps] Desabilita o mecanismo de Attach dinâmico da JVM (-XX:+DisableAttachMechanism)
# e direciona arquivos temporários para /tmp (montado como emptyDir em memória no K8s com readOnlyRootFilesystem)
ENTRYPOINT ["java", \
            "-XX:+DisableAttachMechanism", \
            "-Djava.io.tmpdir=/tmp", \
            "-jar", "/app/app.jar"]
