#!/usr/bin/env bash
# Smoke do SetService — fatia 1, via sets.py. O próprio cliente semeia (SADD) e
# limpa (SREM) o estado; regenera stubs, abre port-forward e roda o cliente.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

PROJECT_ROOT="$(cd ../.. && pwd)"
CLIENT_DIR="${PROJECT_ROOT}/tools/grpc-client"

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

"$PY" "${CLIENT_DIR}/sets.py" localhost:18080
