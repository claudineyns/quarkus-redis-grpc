#!/usr/bin/env bash
# Cria os projetos/namespaces do proxy e do Redis (idempotente).
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

for ns in "$PROXY_NAMESPACE" "$REDIS_NAMESPACE"; do
  if oc --context "$OC_CXT" get project "$ns" >/dev/null 2>&1; then
    echo "projeto '$ns' já existe"
  else
    echo "criando projeto '$ns'..."
    oc --context "$OC_CXT" new-project "$ns" >/dev/null
  fi
done
