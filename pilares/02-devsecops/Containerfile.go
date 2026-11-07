# syntax=docker/dockerfile:1.7
# ==============================================================================
# PILAR 02: DevSecOps (Blindagem do Build ao Runtime)
# App: "Qual a hora atual na timezone XYZ?" (Go 1.27 + Base Scratch Blindada)
# Build (Podman com Secret Mount):
#   podman build --secret id=netrc_creds,src=.netrc -f Containerfile.go -t tz-app-go:pilar02 .
# ==============================================================================

# [DevSecOps] Pinning estrito por tag de patch + digest SHA256 (Imutabilidade contra ataques de Supply Chain)
FROM docker.io/library/golang:1.27.3-trixie@sha256:c65ecfe613b539ac74c62a4c3b2c27e8f8a6076384c8f27e8a3c0f3be17cc385 AS builder

# [DevSecOps] Regra Hadolint DL4006: falha imediata em qualquer etapa de pipe no build
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

WORKDIR /src

# [DevSecOps] Secret Mount: Credenciais para baixar módulos internos nunca usam ARG/ENV.
# São montadas em tmpfs apenas neste passo e jamais ficam no 'podman history'.
RUN --mount=type=secret,id=netrc_creds,target=/root/.netrc,mode=0600 \
    --mount=type=cache,target=/go/pkg/mod \
    --mount=type=bind,source=go.mod,target=go.mod \
    go mod download && go mod verify

COPY ./main.go ./
COPY ./template.html ./
COPY ./go.mod ./

# [DevSecOps] Compilação estática determinística (-trimpath remove caminhos locais do host,
# impedindo vazamento de metadados de diretórios da esteira de CI no binário).
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=linux \
    go build -tags="timetzdata,netgo,osusergo" \
    -trimpath -buildvcs=false \
    -ldflags="-s -w -extldflags '-static'" \
    -o /out/tz-server .

# [DevSecOps] Hardening de permissões (0550 no binário, 0440 no template HTML).
# Impede auto-modificação mesmo pelo próprio UID dono do processo em caso de RCE.
RUN chmod 0550 /out/tz-server && \
    chmod 0440 /src/template.html


# --- ESTÁGIO FINAL: Scratch Blindado (Superfície de Ataque Zero) ---
FROM scratch AS runtime

# [DevSecOps] Metadados injetados pelo CI/CD para vínculo com SBOM (Syft/Trivy) e assinatura Cosign
ARG GIT_COMMIT="unknown"
ARG BUILD_DATE="unknown"
ARG VERSION="1.0.0"

LABEL org.opencontainers.image.title="tz-app-go" \
      org.opencontainers.image.description="Consulta de fuso horário em Go sobre Scratch" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.source="https://github.com/kirimure/demo-apps" \
      security.hardening.level="scratch-immutable"

WORKDIR /app

# Certificados raiz e artefatos copiados com ownership estrito do UID 65532 (nonroot)
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder --chown=65532:65532 /out/tz-server /app/tz-server
COPY --from=builder --chown=65532:65532 /src/template.html /app/template.html

# [DevSecOps] UID:GID numérico (> 10000) exigido pelo Kubernetes 'runAsNonRoot: true'
# sem necessidade de /etc/passwd dentro do container scratch.
USER 65532:65532

# Porta não-privilegiada (> 1024) permitindo 'cap_drop: [ALL]' e Seccomp RuntimeDefault
EXPOSE 8080

ENTRYPOINT ["/app/tz-server"]
