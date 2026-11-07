#!/usr/bin/env bash
# ==============================================================================
# Script Utilitário: Inspeção e Atualização de SHA256 Digests Imutáveis
# Consulta as imagens upstream oficiais via skopeo ou podman e exibe
# os digests canônicos para fixação nos Containerfiles de DevSecOps (Pilar 02).
# ==============================================================================
set -euo pipefail

IMAGES=(
    "docker.io/library/golang:1.27.3-trixie"
    "docker.io/library/debian:trixie-slim"
    "ghcr.io/astral-sh/uv:0.13.6"
    "docker.io/library/eclipse-temurin:25-jdk"
)

echo "=== Inspecionando Digests SHA256 Reais das Imagens Base ==="

for img in "${IMAGES[@]}"; do
    echo -n "Consultando $img ... "
    if command -v skopeo >/dev/null 2>&1; then
        DIGEST=$(skopeo inspect "docker://$img" --format '{{.Digest}}' 2>/dev/null || echo "falha")
    elif command -v podman >/dev/null 2>&1; then
        DIGEST=$(podman manifest inspect "$img" 2>/dev/null | grep -m1 -o 'sha256:[a-f0-9]\{64\}' || echo "falha")
    else
        echo "ERRO: skopeo ou podman não encontrados."
        exit 1
    fi

    if [[ "$DIGEST" != "falha" && -n "$DIGEST" ]]; then
        echo "OK"
        echo "  -> $img@$DIGEST"
    else
        echo "Aviso: Sem acesso à rede para consultar $img."
    fi
done

echo ""
echo "Para fixar os digests no Pilar 02, substitua os hashes em 02-devsecops/Containerfile.*"
echo "com a saída obtida acima."
