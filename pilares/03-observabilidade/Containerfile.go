# syntax=docker/dockerfile:1.7
# ==============================================================================
# PILAR 03: Observabilidade "Zero-Touch"
# App: "Qual a hora atual na timezone XYZ?" (Go 1.27 + Base Scratch Observável)
# Build (Podman):
#   podman build -f Containerfile.go -t tz-app-go:pilar03 .
# ==============================================================================

FROM docker.io/library/golang:1.27.3-trixie AS builder

WORKDIR /src

RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=bind,source=go.mod,target=go.mod \
    go mod download

# [Observabilidade] Continuous Profiling (Parca / Pyroscope) & eBPF (Grafana Beyla):
# Em vez de apagar toda a tabela de símbolos com '-s -w' (o que deixaria os Flamegraphs
# ilegíveis apenas com endereços hexadecimais), usamos '-compressdwarf=true'.
# Assim, agentes eBPF no nó do cluster resolvem o nome exato das funções Go com mínimo impacto no tamanho!
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    --mount=type=bind,source=.,target=. \
    CGO_ENABLED=0 GOOS=linux \
    go build -tags="timetzdata,netgo,osusergo" \
    -trimpath \
    -ldflags="-compressdwarf=true -extldflags '-static'" \
    -o /out/tz-server .


# --- ESTÁGIO FINAL: Scratch com Observabilidade de Fora para Dentro ---
FROM scratch AS runtime

WORKDIR /app

COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder --chown=65532:65532 /out/tz-server /app/tz-server
COPY --chown=65532:65532 ./template.html /app/template.html

# [Observabilidade] Padronização OpenTelemetry (OTel) e Logs Estruturados (JSON em stdout):
# A aplicação usa 'log/slog' nativo emitindo JSON para stdout com 'trace_id' e 'span_id'
# e exporta telemetria via OTLP ou é instrumentada externamente via eBPF (Beyla/Cilium).
ENV LOG_FORMAT="json" \
    LOG_OUTPUT="stdout" \
    OTEL_SERVICE_NAME="tz-app-go" \
    OTEL_TRACES_EXPORTER="otlp" \
    OTEL_METRICS_EXPORTER="otlp" \
    OTEL_LOGS_EXPORTER="otlp" \
    OTEL_EXPORTER_OTLP_PROTOCOL="grpc" \
    OTEL_RESOURCE_ATTRIBUTES="service.namespace=demo-pilares,deployment.environment=production"

USER 65532:65532
EXPOSE 8080

# [Observabilidade] Sonda de Saúde Sem Shell (Native Probe):
# Como a imagem 'scratch' não possui '/bin/sh', 'curl' ou 'wget', usamos a sintaxe EXEC (JSON array)
# invocando a flag '-healthcheck' embutida no próprio binário Go para testar a porta 8080.
HEALTHCHECK --interval=10s --timeout=2s --start-period=2s --retries=3 \
    CMD ["/app/tz-server", "-healthcheck"]

ENTRYPOINT ["/app/tz-server"]
