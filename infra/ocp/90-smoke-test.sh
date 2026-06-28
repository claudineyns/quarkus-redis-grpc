#!/usr/bin/env bash
# Smoke test do GET (sucesso e erro) contra o proxy implantado, usando o cliente
# gRPC em Python (tools/grpc-client). Semeia o Redis, abre port-forward e roda
# o smoke.py. Health/métricas ficam fora deste smoke por ora.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

PROJECT_ROOT="$(cd ../.. && pwd)"
CLIENT_DIR="${PROJECT_ROOT}/tools/grpc-client"

# Chaves que o smoke.py espera (mantidas em sincronia com aquele script).
PRESENT_KEY="smoke:greeting"
PRESENT_VALUE="hello-from-crc"
WRONGTYPE_KEY="smoke:hash"

echo "semeando chaves no Redis..."
REDIS_POD="$(oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" get pod -l app="${REDIS_NAME}" -o jsonpath='{.items[0].metadata.name}')"
oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" exec "$REDIS_POD" -- redis-cli set "$PRESENT_KEY" "$PRESENT_VALUE" >/dev/null
oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" exec "$REDIS_POD" -- redis-cli del "$WRONGTYPE_KEY" >/dev/null
oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" exec "$REDIS_POD" -- redis-cli hset "$WRONGTYPE_KEY" f v >/dev/null
echo "  ${PRESENT_KEY}=${PRESENT_VALUE} ; ${WRONGTYPE_KEY}=<hash>"

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

echo "executando smoke.py..."
"$PY" "${CLIENT_DIR}/smoke.py" localhost:18080
