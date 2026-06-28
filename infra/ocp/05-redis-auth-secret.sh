#!/usr/bin/env bash
# *** LABORATÓRIO APENAS ***
# Cria o Secret com a SENHA do Redis (requirepass) — estática, só p/ lab/CRC.
# Presente nos DOIS namespaces: o do Redis (servidor) e o do proxy (cliente).
# NUNCA usar esta senha fora de laboratório.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

# Senha do Redis. ESTÁTICA — somente laboratório.
REDIS_PASSWORD="R3dis-gRPC-Lab-2026"

for ns in "$REDIS_NAMESPACE" "$PROXY_NAMESPACE"; do
  echo "criando/atualizando Secret '${REDIS_AUTH_SECRET}' (senha do Redis) em '${ns}'..."
  oc --context "$OC_CXT" -n "$ns" create secret generic "$REDIS_AUTH_SECRET" \
    --from-literal=password="$REDIS_PASSWORD" \
    --dry-run=client -o yaml | oc --context "$OC_CXT" -n "$ns" apply -f -
done

echo "Secret da senha do Redis pronto (estática de laboratório)."
