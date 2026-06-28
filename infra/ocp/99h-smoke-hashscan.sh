#!/usr/bin/env bash
# Smoke do HashService — fatia 3 (HSETNX/HINCRBY/HSCAN), via hashmore.py. Semeia
# 1000 campos em demo:hash:scan (HSET em bloco via EVAL) para exercitar o HSCAN
# multi-página; HSETNX/HINCRBY são auto-semeados pelo cliente.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

PROJECT_ROOT="$(cd ../.. && pwd)"
CLIENT_DIR="${PROJECT_ROOT}/tools/grpc-client"

echo "limpando e semeando 1000 campos em demo:hash:scan (HSET via EVAL)..."
REDIS_POD="$(oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" get pod -l app="${REDIS_NAME}" -o jsonpath='{.items[0].metadata.name}')"
oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" exec "$REDIS_POD" -- sh -c '
  redis-cli del demo:hash:scan >/dev/null
  redis-cli eval "for i=1,1000 do redis.call(\"hset\",\"demo:hash:scan\",\"f\"..i,\"v\"..i) end return 1" 0 >/dev/null
  echo "  hlen agora: $(redis-cli hlen demo:hash:scan)"
'

if [ ! -d "${CLIENT_DIR}/.venv" ]; then
  bash "${CLIENT_DIR}/setup.sh"
else
  bash "${CLIENT_DIR}/gen-stubs.sh"
fi
if [ -x "${CLIENT_DIR}/.venv/Scripts/python.exe" ]; then
  PY="${CLIENT_DIR}/.venv/Scripts/python.exe"
else
  PY="${CLIENT_DIR}/.venv/bin/python"
fi

echo "iniciando port-forward (localhost:18080 -> svc/${APP_NAME}:8443)..."
oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" port-forward "svc/${APP_NAME}" 18080:8443 >/dev/null 2>&1 &
PF_PID=$!
trap 'kill $PF_PID 2>/dev/null || true' EXIT
sleep 3

"$PY" "${CLIENT_DIR}/hashmore.py" localhost:18080
