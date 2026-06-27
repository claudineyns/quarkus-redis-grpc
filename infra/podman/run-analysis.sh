#!/usr/bin/env bash
# Roda os testes com cobertura (Jacoco) e envia a análise ao SonarQube local.
# O token vem de $SONAR_TOKEN ou do arquivo salvo por start-sonarqube.sh.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

TOKEN="${SONAR_TOKEN:-}"
if [ -z "$TOKEN" ] && [ -f "$SONAR_TOKEN_FILE" ]; then
  TOKEN="$(cat "$SONAR_TOKEN_FILE")"
fi
if [ -z "$TOKEN" ]; then
  echo "Token não encontrado. Rode ./start-sonarqube.sh ou exporte SONAR_TOKEN." >&2
  exit 1
fi

cd "$PROJECT_ROOT"
mvn -B -ntp clean verify \
  org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
  -Dsonar.host.url="${SONAR_URL}" \
  -Dsonar.token="${TOKEN}"
