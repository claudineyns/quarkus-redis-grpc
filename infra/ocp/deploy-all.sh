#!/usr/bin/env bash
# Orquestra o deploy completo no CRC (com TLS de borda).
set -euo pipefail
cd "$(dirname "$0")"

bash ./00-namespaces.sh
bash ./10-redis.sh
bash ./25-tls-secret.sh
bash ./26-master-key-secret.sh
bash ./27-credentials.sh
bash ./20-build-proxy.sh
bash ./30-deploy-proxy.sh
bash ./99-smoke-tls.sh
