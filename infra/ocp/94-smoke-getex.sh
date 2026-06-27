#!/usr/bin/env bash
# Smoke test do GETEX contra o proxy implantado, via getex.py. Semeia o estado
# (com/sem TTL), regenera os stubs, abre port-forward, roda o cliente e confere
# o efeito sobre o TTL via redis-cli.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

PROJECT_ROOT="$(cd ../.. && pwd)"
CLIENT_DIR="${PROJECT_ROOT}/tools/grpc-client"

echo "semeando estado demo:getex:* no Redis..."
REDIS_POD="$(oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" get pod -l app="${REDIS_NAME}" -o jsonpath='{.items[0].metadata.name}')"
oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" exec "$REDIS_POD" -- sh -c '
  redis-cli del demo:getex:a demo:getex:b demo:getex:c demo:getex:absent demo:getex:hash >/dev/null
  redis-cli set demo:getex:a va >/dev/null            # sem TTL
  redis-cli set demo:getex:b vb EX 100 >/dev/null     # com TTL
  redis-cli set demo:getex:c vc EX 100 >/dev/null     # com TTL
  redis-cli hset demo:getex:hash f v >/dev/null
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
oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" port-forward "svc/${APP_NAME}" 18080:8080 >/dev/null 2>&1 &
PF_PID=$!
trap 'kill $PF_PID 2>/dev/null || true' EXIT
sleep 3

"$PY" "${CLIENT_DIR}/getex.py" localhost:18080

echo ""
echo "efeito sobre o TTL (via redis-cli):"
TA="$(oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" exec "$REDIS_POD" -- redis-cli ttl demo:getex:a)"
TB="$(oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" exec "$REDIS_POD" -- redis-cli ttl demo:getex:b)"
TC="$(oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" exec "$REDIS_POD" -- redis-cli ttl demo:getex:c)"
echo "  ttl(a)=${TA}  (esperado >0: EX definiu)"
echo "  ttl(b)=${TB}  (esperado -1: PERSIST removeu)"
echo "  ttl(c)=${TC}  (esperado >0: sem opção manteve)"
