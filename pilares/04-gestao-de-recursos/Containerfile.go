# syntax=docker/dockerfile:1.7
# ==============================================================================
# PILAR 04: Gestão de Recursos (Rightsizing, Cgroups & FinOps)
# App: "Qual a hora atual na timezone XYZ?" (Go 1.27 + Base Scratch Cgroup-Aware)
# Build (Podman):
#   podman build -f Containerfile.go -t tz-app-go:pilar04 .
# Run com limite estrito de memória (simulando Requests == Limits = 64MiB):
#   podman run --rm -m 64m --memory-swap 64m -p 8080:8080 tz-app-go:pilar04
# ==============================================================================

FROM docker.io/library/golang:1.27.3-trixie AS builder

WORKDIR /src

RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=bind,source=go.mod,target=go.mod \
    go mod download

# [Gestão de Recursos] Compilação estática com suporte opcional a PGO (Profile-Guided Optimization).
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    --mount=type=bind,source=.,target=. \
    CGO_ENABLED=0 GOOS=linux \
    go build -tags="timetzdata,netgo,osusergo" \
    -pgo=auto -trimpath \
    -ldflags="-s -w -extldflags '-static'" \
    -o /out/tz-server .


# --- ESTÁGIO FINAL: Scratch Alinhado aos Limites do Cgroups v2 ---
FROM scratch AS runtime

WORKDIR /app

COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder --chown=65532:65532 /out/tz-server /app/tz-server
COPY --chown=65532:65532 ./template.html /app/template.html

# [Gestão de Recursos & FinOps] Consciência de Cgroups no Runtime do Go:
# 1. GOMEMLIMIT=56MiB: Para um container dimensionado com 'Requests == Limits = 64MiB',
#    reservamos ~88% para o runtime Go. O Garbage Collector passa a rodar de forma mais
#    agressiva antes de atingir 64MiB, erradicando mortes por 'OOMKilled' (Exit Code 137).
# 2. GOGC=100: Mantém ciclos de coleta equilibrados em regime constante (Steady-State),
#    enquanto o GOMEMLIMIT atua como teto de segurança em picos de requisições.
# 3. Consciência de CPU (Cgroups v2): O runtime do Go 1.27 lê automaticamente a quota de CPU
#    do cgroup (/sys/fs/cgroup/cpu.max) ou via uber-go/automaxprocs, evitando criar dezenas
#    de threads do SO (M) baseadas nos núcleos físicos do nó hospedeiro (evita CFS Throttling).
ENV GOMEMLIMIT="56MiB" \
    GOGC="100"

USER 65532:65532
EXPOSE 8080

ENTRYPOINT ["/app/tz-server"]
