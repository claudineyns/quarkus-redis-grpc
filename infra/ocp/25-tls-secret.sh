#!/usr/bin/env bash
# Passo prévio ao deploy: gera (openssl) uma CA local e um certificado de
# servidor assinado por ela, e cria o Secret de TLS no CRC.
#   - CN do folha   = host da Route (EDGE_HOST)
#   - SAN do folha  = EDGE_HOST + localhost (localhost permite validar via
#                     port-forward sem name override)
#   - Secret        = tls.crt + tls.key + ca.crt (montado em /var/certificados/servidor/)
#
# Nota MSYS/Windows: openssl e oc são binários nativos. Usamos MSYS_NO_PATHCONV=1
# (preserva o "-subj /CN=...") com caminhos já em formato Windows (pwd -W).
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

PROJECT_ROOT="$(cd ../.. && pwd)"
DIR="${PROJECT_ROOT}/${CERT_DIR}"
mkdir -p "$DIR"
DIR_WIN="$(cd "$DIR" && pwd -W)"   # caminho Windows p/ os binários nativos

echo "gerando CA local..."
MSYS_NO_PATHCONV=1 openssl req -x509 -newkey rsa:4096 -nodes \
  -keyout "${DIR_WIN}/ca.key" -out "${DIR_WIN}/ca.crt" -days 3650 \
  -subj "/CN=redis-grpc-dev-ca"

echo "gerando chave + CSR do servidor (CN=${EDGE_HOST})..."
MSYS_NO_PATHCONV=1 openssl req -newkey rsa:2048 -nodes \
  -keyout "${DIR_WIN}/tls.key" -out "${DIR_WIN}/tls.csr" \
  -subj "/CN=${EDGE_HOST}"

# Extensões do folha: SAN com a Route e localhost; uso de servidor.
cat > "$DIR/leaf.ext" <<EXT
subjectAltName=DNS:${EDGE_HOST},DNS:localhost
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
EXT

echo "assinando o certificado folha com a CA..."
MSYS_NO_PATHCONV=1 openssl x509 -req -in "${DIR_WIN}/tls.csr" \
  -CA "${DIR_WIN}/ca.crt" -CAkey "${DIR_WIN}/ca.key" -CAcreateserial \
  -out "${DIR_WIN}/tls.crt" -days 825 -extfile "${DIR_WIN}/leaf.ext"

echo "criando/atualizando Secret '${TLS_SECRET}' em '${PROXY_NAMESPACE}'..."
MSYS_NO_PATHCONV=1 oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" create secret generic "$TLS_SECRET" \
  --from-file=tls.crt="${DIR_WIN}/tls.crt" \
  --from-file=tls.key="${DIR_WIN}/tls.key" \
  --from-file=ca.crt="${DIR_WIN}/ca.crt" \
  --dry-run=client -o yaml | oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" apply -f -

echo "TLS pronto. CA local para o cliente: ${DIR}/ca.crt"
