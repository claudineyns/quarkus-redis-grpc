#!/usr/bin/env bash
# Gera os stubs Python (string_pb2.py / string_pb2_grpc.py) a partir do .proto.
# Geração "flat" (o string.proto não tem imports), facilitando o import.
set -euo pipefail
cd "$(dirname "$0")"
source ./_venv.sh

mkdir -p generated

# Geração flat por arquivo (cada .proto sem imports cruzados) → string_pb2,
# key_pb2, etc., diretamente em generated/.
"$PY" -m grpc_tools.protoc -I "../../src/main/proto/string/v1" \
  --python_out=generated --grpc_python_out=generated \
  string.proto

"$PY" -m grpc_tools.protoc -I "../../src/main/proto/key/v1" \
  --python_out=generated --grpc_python_out=generated \
  key.proto

echo "stubs gerados em generated/"
