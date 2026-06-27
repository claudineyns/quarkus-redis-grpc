#!/usr/bin/env bash
# Prepara o cliente Python: cria o venv, instala dependências e gera os stubs.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d .venv ]; then
  echo "criando venv..."
  python -m venv .venv
fi

if [ -x .venv/Scripts/python.exe ]; then PY=".venv/Scripts/python.exe"; else PY=".venv/bin/python"; fi

echo "instalando dependências..."
"$PY" -m pip install --upgrade pip >/dev/null
"$PY" -m pip install -r requirements.txt

echo "gerando stubs..."
bash ./gen-stubs.sh

echo "pronto."
