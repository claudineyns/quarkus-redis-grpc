#!/usr/bin/env bash
# Variáveis comuns dos scripts OpenShift/CRC.
# Sobrescreva exportando antes de chamar (ex.: OC_CXT=... ./deploy-all.sh).

export OC_CXT="${OC_CXT:-crc-developer}"          # contexto oc (usuário developer)
export PROXY_NAMESPACE="${PROXY_NAMESPACE:-redis-grpc}"
export REDIS_NAMESPACE="${REDIS_NAMESPACE:-redis}"
export APP_NAME="${APP_NAME:-redis-grpc}"
export REDIS_NAME="${REDIS_NAME:-redis}"

# Nível de log da aplicação no deploy (DEBUG no CRC para a validação quente).
export APP_LOG_LEVEL="${APP_LOG_LEVEL:-DEBUG}"

# Imagens: builder S2I (público) e Redis (público).
export BUILDER_IMAGE="${BUILDER_IMAGE:-registry.access.redhat.com/ubi9/openjdk-21:latest}"
export REDIS_IMAGE="${REDIS_IMAGE:-docker.io/redis:8}"

# Registry interno do OpenShift (resolve a imagem construída pelo S2I).
export INTERNAL_REGISTRY="${INTERNAL_REGISTRY:-image-registry.openshift-image-registry.svc:5000}"

# TLS de borda (passthrough): host da Route (= CN/SAN do cert), nome do Secret e
# diretório local (gitignored) onde o openssl gera CA + folha.
export EDGE_HOST="${EDGE_HOST:-redis-grpc.apps-crc.testing}"
export TLS_SECRET="${TLS_SECRET:-redis-grpc-tls}"
export CERT_DIR="${CERT_DIR:-temp/tls}"

# Secret com a chave mestra (HMAC) do interceptor de credenciais (DESIGN 6.1).
export AUTH_SECRET="${AUTH_SECRET:-redis-grpc-auth}"
# ConfigMap com a allowlist de hashes SHA-256(ACCESS_KEY) (hashes não são segredo).
export ACL_CONFIGMAP="${ACL_CONFIGMAP:-redis-grpc-acl}"

# CA local (em formato Windows, p/ o python nativo) usado pelo cliente gRPC sobre
# TLS. Definido só se o cert já existir (gerado por 25-tls-secret.sh). Os clientes
# Python leem REDIS_GRPC_CA; defina vazio (REDIS_GRPC_CA=) para forçar plaintext.
_CERT_ABS="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../${CERT_DIR}" 2>/dev/null && pwd -W 2>/dev/null || true)"
if [ -n "${_CERT_ABS:-}" ]; then
  export REDIS_GRPC_CA="${REDIS_GRPC_CA:-${_CERT_ABS}/ca.crt}"
fi
