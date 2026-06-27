# Define $PY com o python do venv (cross-platform: Windows usa Scripts/).
# Pensado para ser "sourced" por outros scripts.
if [ -x .venv/Scripts/python.exe ]; then
  PY=".venv/Scripts/python.exe"
elif [ -x .venv/bin/python ]; then
  PY=".venv/bin/python"
else
  echo "venv não encontrado; rode ./setup.sh primeiro" >&2
  return 1 2>/dev/null || exit 1
fi
export PY
