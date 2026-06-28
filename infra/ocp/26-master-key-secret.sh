#!/usr/bin/env bash
# *** LABORATÓRIO APENAS ***
# Cria o Secret com a CHAVE MESTRA estática usada pelo interceptor de credenciais
# (HMAC-SHA256 — DESIGN 6.1). O valor abaixo é fixo só para montagem de lab/CRC.
# NUNCA usar esta chave fora de laboratório: em produção a chave mestra vem de um
# Secret gerado/gerido pelos responsáveis (SecureRandom, fora do repositório).
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

# Chave mestra: hex 64 (32 bytes). ESTÁTICA — somente laboratório.
MASTER_KEY="2bef13770b52fd168b371eaf68855468eda71e6bf9857cb5f50ac534a9731870"

echo "criando/atualizando Secret '${AUTH_SECRET}' (chave mestra) em '${PROXY_NAMESPACE}'..."
oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" create secret generic "$AUTH_SECRET" \
  --from-literal=master-key="$MASTER_KEY" \
  --dry-run=client -o yaml | oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" apply -f -

echo "Secret de auth pronto (chave mestra estática de laboratório)."
