#!/usr/bin/env bash
# Smoke do SetService — fatia 3 (SSCAN), com avaliação de desempenho: semeia 1000
# membros em demo:set:scan (SADD em bloco via EVAL) e itera o SSCAN (multi-página)
# medindo páginas + tempo, via setscan.py.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

PROJECT_ROOT="$(cd ../.. && pwd)"
CLIENT_DIR="${PROJECT_ROOT}/tools/grpc-client"

echo "limpando e semeando 1000 membros em demo:set:scan (SADD via EVAL)..."
REDIS_POD="$(oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" get pod -l app="${REDIS_NAME}" -o jsonpath='{.items[0].metadata.name}')"
oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" exec "$REDIS_POD" -- sh -c '
  redis-cli del demo:set:scan >/dev/null
  redis-cli eval "for i=1,1000 do redis.call(\"sadd\",\"demo:set:scan\",\"m\"..i) end return 1" 0 >/dev/null
  echo "  scard agora: $(redis-cli scard demo:set:scan)"
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

"$PY" "${CLIENT_DIR}/setscan.py" localhost:18080 demo:set:scan 1000
