#!/usr/bin/env bash
# Variáveis comuns dos scripts de Sonar/podman.
# Sobrescreva exportando antes de chamar.

export SONAR_CONTAINER="${SONAR_CONTAINER:-redis-grpc-sonarqube}"
export SONAR_IMAGE="${SONAR_IMAGE:-sonarqube:26.6.0.123539-community}"
export SONAR_HOST_PORT="${SONAR_HOST_PORT:-9090}"
export SONAR_URL="${SONAR_URL:-http://localhost:${SONAR_HOST_PORT}}"
export SONAR_ADMIN_PASS="${SONAR_ADMIN_PASS:-SonarAdmin1!}"

# Raiz do projeto e arquivo (em temp/, gitignored) onde o token é salvo.
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export PROJECT_ROOT
export SONAR_TOKEN_FILE="${SONAR_TOKEN_FILE:-${PROJECT_ROOT}/temp/sonar-token}"
