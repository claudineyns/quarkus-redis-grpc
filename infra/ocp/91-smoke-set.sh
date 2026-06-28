#!/usr/bin/env bash
# Smoke test do SET (incondicional, NX/XX, GET, EX) contra o proxy implantado,
# usando o cliente Python set.py. Limpa o estado das chaves demo:set:*, abre
# port-forward, roda o cliente e confere o TTL do cenário EX via redis-cli.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

PROJECT_ROOT="$(cd ../.. && pwd)"
CLIENT_DIR="${PROJECT_ROOT}/tools/grpc-client"

echo "limpando chaves demo:set:* no Redis..."
REDIS_POD="$(oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" get pod -l app="${REDIS_NAME}" -o jsonpath='{.items[0].metadata.name}')"
oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" exec "$REDIS_POD" -- \
  redis-cli del demo:set:a demo:set:b demo:set:c demo:set:ttl >/dev/null

# Garante o cliente Python pronto (venv + stubs).
if [ ! -d "${CLIENT_DIR}/.venv" ] || [ ! -d "${CLIENT_DIR}/generated" ]; then
  echo "preparando cliente Python (setup)..."
  bash "${CLIENT_DIR}/setup.sh"
fi
if [ -x "${CLIENT_DIR}/.venv/Scripts/python.exe" ]; then
  PY="${CLIENT_DIR}/.venv/Scripts/python.exe"
else
  PY="${CLIENT_DIR}/.venv/bin/python"
fi

echo "iniciando port-forward (localhost:18080 -> svc/${APP_NAME}:8080)..."
oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" port-forward "svc/${APP_NAME}" 18080:8443 >/dev/null 2>&1 &
PF_PID=$!
trap 'kill $PF_PID 2>/dev/null || true' EXIT
sleep 3

"$PY" "${CLIENT_DIR}/set.py" localhost:18080

echo ""
echo "conferindo TTL de demo:set:ttl via redis-cli (cenário EX)..."
TTL="$(oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" exec "$REDIS_POD" -- redis-cli ttl demo:set:ttl)"
echo "  TTL = ${TTL}"
