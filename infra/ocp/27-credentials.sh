#!/usr/bin/env bash
# *** LABORATÓRIO ***
# Gera um par de credenciais (ACCESS_KEY/SECRET_KEY) a partir da chave mestra do
# Secret de auth e cria o ConfigMap com a allowlist de hashes SHA-256(ACCESS_KEY)
# (uma entrada por enquanto). Salva o par em temp/auth/ (gitignored) p/ o cliente.
#
# Definições (DEVEM casar com o interceptor Java — DESIGN 6.1):
#   SECRET_KEY = HMAC-SHA256(key = bytes da chave mestra [hex-decoded, 32 bytes],
#                            msg = string ACCESS_KEY)              -> hex 64
#   HASH       = SHA-256(string ACCESS_KEY)                        -> hex 64
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

PROJECT_ROOT="$(cd ../.. && pwd)"
AUTH_DIR="${PROJECT_ROOT}/temp/auth"
mkdir -p "$AUTH_DIR"

echo "lendo a chave mestra do Secret '${AUTH_SECRET}'..."
MASTER_KEY="$(oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" get secret "$AUTH_SECRET" \
  -o jsonpath='{.data.master-key}' | base64 -d)"

# ACCESS_KEY aleatório (hex 16 bytes); SECRET_KEY e HASH derivados conforme acima.
ACCESS_KEY="$(openssl rand -hex 16)"
SECRET_KEY="$(printf '%s' "$ACCESS_KEY" | openssl dgst -sha256 -mac HMAC -macopt "hexkey:${MASTER_KEY}" | awk '{print $NF}')"
HASH="$(printf '%s' "$ACCESS_KEY" | openssl dgst -sha256 | awk '{print $NF}')"

printf '%s' "$ACCESS_KEY" > "$AUTH_DIR/access_key"
printf '%s' "$SECRET_KEY" > "$AUTH_DIR/secret_key"

echo "criando/atualizando ConfigMap '${ACL_CONFIGMAP}' (allowlist de hashes)..."
oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" create configmap "$ACL_CONFIGMAP" \
  --from-literal=access-key-hashes="$HASH" \
  --dry-run=client -o yaml | oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" apply -f -

echo ""
echo "Par de credenciais (LAB) — salvo em ${AUTH_DIR}/:"
echo "  ACCESS_KEY = ${ACCESS_KEY}"
echo "  SECRET_KEY = ${SECRET_KEY}"
echo "  SHA256(ACCESS_KEY) = ${HASH}  (na allowlist do ConfigMap)"
