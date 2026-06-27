#!/usr/bin/env bash
# Sobe um SonarQube (community) no podman para análise local, espera ficar UP,
# troca a senha padrão do admin, gera um token global de análise e o salva em
# temp/sonar-token (gitignored) para o run-analysis.sh consumir.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

podman rm -f "$SONAR_CONTAINER" 2>/dev/null || true

MSYS_NO_PATHCONV=1 podman run -d \
  --name "$SONAR_CONTAINER" \
  -p "${SONAR_HOST_PORT}:9000" \
  -e SONAR_ES_BOOTSTRAP_CHECKS_DISABLE=true \
  "$SONAR_IMAGE"

echo "Aguardando SonarQube inicializar (pode levar alguns minutos)..."
until curl -sf "${SONAR_URL}/api/system/status" 2>/dev/null | grep -q '"status":"UP"'; do
  printf "."
  sleep 5
done
echo ""
echo "SonarQube disponível em ${SONAR_URL}"

# Troca a senha padrão do admin no primeiro acesso (ignora erro se já trocada).
curl -sf -u "admin:admin" \
  -X POST "${SONAR_URL}/api/users/change_password" \
  -d "login=admin&previousPassword=admin&password=${SONAR_ADMIN_PASS}" \
  >/dev/null 2>&1 || true

# Gera um token global de análise. Nome único (timestamp) evita colisão ao
# re-executar o script.
TOKEN_NAME="redis-grpc-analysis-$(date +%s)"
TOKEN_RESPONSE="$(curl -sf -u "admin:${SONAR_ADMIN_PASS}" \
  -X POST "${SONAR_URL}/api/user_tokens/generate" \
  -d "name=${TOKEN_NAME}&type=GLOBAL_ANALYSIS_TOKEN")"

SONAR_TOKEN="$(echo "$TOKEN_RESPONSE" \
  | python3 -c "import sys, json; print(json.load(sys.stdin)['token'])")"

mkdir -p "$(dirname "$SONAR_TOKEN_FILE")"
printf '%s' "$SONAR_TOKEN" > "$SONAR_TOKEN_FILE"

echo ""
echo "Token de análise salvo em: ${SONAR_TOKEN_FILE}"
echo "Console: ${SONAR_URL}  (admin / ${SONAR_ADMIN_PASS})"
echo ""
echo "Para rodar os testes + análise:  bash infra/podman/run-analysis.sh"
