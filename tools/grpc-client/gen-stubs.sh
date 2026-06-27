#!/usr/bin/env bash
# Gera os stubs Python (string_pb2.py / string_pb2_grpc.py) a partir do .proto.
# Geração "flat" (o string.proto não tem imports), facilitando o import.
set -euo pipefail
cd "$(dirname "$0")"
source ./_venv.sh

PROTO_DIR="../../src/main/proto/string/v1"
mkdir -p generated

"$PY" -m grpc_tools.protoc -I "$PROTO_DIR" \
  --python_out=generated --grpc_python_out=generated \
  string.proto

echo "stubs gerados em generated/"
