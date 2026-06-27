#!/usr/bin/env bash
# Smoke test do KeyService — fatia 2 (expiração), via keyexp.py. Semeia chaves
# (strings sem TTL), regenera os stubs, abre port-forward e roda o cliente.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

PROJECT_ROOT="$(cd ../.. && pwd)"
CLIENT_DIR="${PROJECT_ROOT}/tools/grpc-client"

echo "semeando estado demo:kx:* no Redis..."
REDIS_POD="$(oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" get pod -l app="${REDIS_NAME}" -o jsonpath='{.items[0].metadata.name}')"
oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" exec "$REDIS_POD" -- sh -c '
  redis-cli del demo:kx:k demo:kx:k2 demo:kx:k3 demo:kx:absent >/dev/null
  redis-cli set demo:kx:k  v >/dev/null
  redis-cli set demo:kx:k2 v >/dev/null
  redis-cli set demo:kx:k3 v >/dev/null
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

echo "iniciando port-forward (localhost:18080 -> svc/${APP_NAME}:8080)..."
oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" port-forward "svc/${APP_NAME}" 18080:8443 >/dev/null 2>&1 &
PF_PID=$!
trap 'kill $PF_PID 2>/dev/null || true' EXIT
sleep 3

"$PY" "${CLIENT_DIR}/keyexp.py" localhost:18080
