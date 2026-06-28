#!/usr/bin/env bash
# Smoke do KeyService — fatia 3 (SCAN), com avaliação de desempenho: semeia 1000
# chaves scan:perf:* (bulk via EVAL, não 1000 round-trips) e itera o SCAN
# (multi-página) medindo páginas + tempo, via keyscan.py.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

PROJECT_ROOT="$(cd ../.. && pwd)"
CLIENT_DIR="${PROJECT_ROOT}/tools/grpc-client"

echo "limpando e semeando 1000 chaves scan:perf:* no Redis (bulk via EVAL)..."
REDIS_POD="$(oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" get pod -l app="${REDIS_NAME}" -o jsonpath='{.items[0].metadata.name}')"
oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" exec "$REDIS_POD" -- sh -c '
  # idempotência: apaga o prefixo de execuções anteriores
  redis-cli --scan --pattern "scan:perf:*" | xargs -r -n 500 redis-cli del >/dev/null
  # semeia 1000 chaves num único comando (servidor-side, sem round-trips)
  redis-cli eval "for i=1,1000 do redis.call(\"set\",\"scan:perf:\"..i,\"v\") end return 1" 0 >/dev/null
  echo "  dbsize agora: $(redis-cli dbsize)"
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

"$PY" "${CLIENT_DIR}/keyscan.py" localhost:18080 "scan:perf:*" 1000
