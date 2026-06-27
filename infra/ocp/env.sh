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
