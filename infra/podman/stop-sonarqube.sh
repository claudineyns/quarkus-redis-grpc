#!/usr/bin/env bash
# Para e remove o container do SonarQube local.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

podman rm -f "$SONAR_CONTAINER" 2>/dev/null || true
echo "SonarQube (${SONAR_CONTAINER}) removido."
