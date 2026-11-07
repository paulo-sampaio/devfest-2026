# syntax=docker/dockerfile:1.7
# ==============================================================================
# PILAR 01: Arquitetura Leve (Redução de Superfície, Zero Bloat & Velocidade)
# App: "Qual a hora atual na timezone XYZ?" (Go 1.27 + Base Scratch)
# Build (Podman):
#   podman build -f Containerfile.go -t tz-app-go:pilar01 .
# Exportação/Push com compressão zstd rápida no K8s:
#   podman push --compression-format=zstd localhost/tz-app-go:pilar01 docker://seu-registry/tz-app-go:pilar01
# ==============================================================================

# [Arquitetura Leve] Multistage Build usando Debian 13 (Trixie) com Go na versão de patch estrita
FROM docker.io/library/golang:1.27.3-trixie AS builder

WORKDIR /src

# [Arquitetura Leve] Cache Mount + Bind Mount:
# Baixa módulos usando cache persistente do Buildah/Podman sem criar camada extra de COPY
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=bind,source=go.mod,target=go.mod \
    go mod download

# [Arquitetura Leve] Compilação 100% estática (CGO_ENABLED=0) e enxuta (-s -w).
# A tag 'timetzdata' embute a base IANA de fusos horários direto no binário (~450KB),
# permitindo consultar qualquer timezone ("America/Sao_Paulo", "Asia/Tokyo", etc.)
# em uma imagem 'scratch' (0 bytes de SO) sem depender de arquivos do sistema operacional.
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    --mount=type=bind,source=.,target=. \
    CGO_ENABLED=0 GOOS=linux \
    go build -tags="timetzdata,netgo,osusergo" \
    -trimpath -ldflags="-s -w -extldflags '-static'" \
    -o /out/tz-server .


# --- ESTÁGIO FINAL: Scratch (0 Bytes de Sistema Operacional) ---
# [Arquitetura Leve] Sem shell (/bin/sh), sem gerenciador de pacotes (apt), sem libc.
FROM scratch AS runtime

WORKDIR /app

# Certificados CA da base Debian Trixie caso a app consulte APIs NTP/Time externas
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/

# [Arquitetura Leve] Apenas o binário estático e o template HTML da página web
COPY --from=builder /out/tz-server /app/tz-server
COPY ./template.html /app/template.html

USER 65532:65532
EXPOSE 8080

ENTRYPOINT ["/app/tz-server"]
