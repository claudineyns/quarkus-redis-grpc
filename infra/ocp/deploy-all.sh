#!/usr/bin/env bash
# Orquestra o smoke deploy completo no CRC.
set -euo pipefail
cd "$(dirname "$0")"

bash ./00-namespaces.sh
bash ./10-redis.sh
bash ./20-build-proxy.sh
bash ./30-deploy-proxy.sh
bash ./90-smoke-test.sh
