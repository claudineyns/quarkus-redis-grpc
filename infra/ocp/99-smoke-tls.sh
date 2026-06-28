#!/usr/bin/env bash
# Smoke test do TLS de borda: valida um GET sobre TLS (secure channel + CA),
# via port-forward na porta TLS 8443. O SAN do cert inclui 'localhost', então a
# verificação de hostname contra localhost funciona sem name override.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

PROJECT_ROOT="$(cd ../.. && pwd)"
CLIENT_DIR="${PROJECT_ROOT}/tools/grpc-client"
CA="${PROJECT_ROOT}/${CERT_DIR}/ca.crt"

[ -f "$CA" ] || { echo "CA não encontrada em ${CA} — rode 25-tls-secret.sh primeiro" >&2; exit 1; }

echo "semeando chaves no Redis..."
REDIS_POD="$(oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" get pod -l app="${REDIS_NAME}" -o jsonpath='{.items[0].metadata.name}')"
oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" exec "$REDIS_POD" -- sh -c '
  redis-cli set smoke:greeting hello-from-crc >/dev/null
  redis-cli del smoke:hash >/dev/null
  redis-cli hset smoke:hash f v >/dev/null
'

if [ ! -d "${CLIENT_DIR}/.venv" ] || [ ! -d "${CLIENT_DIR}/generated" ]; then
  bash "${CLIENT_DIR}/setup.sh"
fi
if [ -x "${CLIENT_DIR}/.venv/Scripts/python.exe" ]; then
  PY="${CLIENT_DIR}/.venv/Scripts/python.exe"
else
  PY="${CLIENT_DIR}/.venv/bin/python"
fi

echo "port-forward TLS (localhost:18443 -> svc/${APP_NAME}:8443)..."
oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" port-forward "svc/${APP_NAME}" 18443:8443 >/dev/null 2>&1 &
PF_PID=$!
trap 'kill $PF_PID 2>/dev/null || true' EXIT
sleep 3

echo "executando smoke.py sobre TLS (CA via env.sh REDIS_GRPC_CA)..."
"$PY" "${CLIENT_DIR}/smoke.py" localhost:18443
