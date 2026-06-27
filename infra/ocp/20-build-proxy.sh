#!/usr/bin/env bash
# Constrói a imagem do proxy via binary build com estratégia Docker:
#   1) empacota localmente com o mvn do sistema (fast-jar);
#   2) envia um contexto enxuto (Dockerfile + quarkus-app) ao cluster;
#   3) o cluster monta a imagem sobre ubi9/openjdk-21-runtime e publica no
#      imagestream interno ${APP_NAME}:latest.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

PROJECT_ROOT="$(cd ../.. && pwd)"
STAGE="${PROJECT_ROOT}/temp/docker-context"

echo "empacotando localmente (mvn package, fast-jar)..."
( cd "$PROJECT_ROOT" && mvn -B -ntp -DskipTests package )

echo "preparando contexto Docker em ${STAGE}..."
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp "${PROJECT_ROOT}/src/main/docker/Dockerfile.jvm" "$STAGE"/Dockerfile
cp -r "${PROJECT_ROOT}/target/quarkus-app" "$STAGE"/quarkus-app

# Garante um BuildConfig com estratégia Docker (recria se for de outra estratégia,
# ex.: o S2I source criado numa tentativa anterior).
NEED_BC=1
if oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" get bc/"${APP_NAME}" >/dev/null 2>&1; then
  STRAT="$(oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" get bc/"${APP_NAME}" -o jsonpath='{.spec.strategy.type}')"
  if [ "$STRAT" = "Docker" ]; then
    NEED_BC=0
  else
    echo "removendo BuildConfig anterior (estratégia ${STRAT})..."
    oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" delete bc/"${APP_NAME}"
  fi
fi
if [ "$NEED_BC" = "1" ]; then
  echo "criando BuildConfig (Docker strategy) '${APP_NAME}'..."
  oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" new-build --binary=true \
    --strategy=docker --name="${APP_NAME}"
fi

echo "iniciando build a partir de ${STAGE}..."
oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" start-build "${APP_NAME}" \
  --from-dir="$STAGE" --follow --wait
