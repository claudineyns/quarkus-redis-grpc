# tools/grpc-client — Python gRPC client (didactic)

A small Python gRPC client used to exercise the `redis-grpc` proxy. It also
serves a learning purpose (client-side gRPC in Python).

## Layout

| File | Purpose |
|---|---|
| `requirements.txt` | `grpcio` + `grpcio-tools`. |
| `setup.sh` | Create the `.venv`, install deps, generate stubs. |
| `gen-stubs.sh` | Generate Python stubs from `src/main/proto/string/v1/string.proto`. |
| `client.py` | Channel + `StringService` stub + `get()` helper (commented). |
| `smoke.py` | Success/error scenarios: GET present, nil, WRONGTYPE. |
| `generated/` | Generated stubs (git-ignored). |
| `.venv/` | Virtualenv (git-ignored). |

## Usage

```bash
bash tools/grpc-client/setup.sh                  # one-time
.venv/python smoke.py localhost:18080            # via the smoke runner below
```

Normally invoked through `infra/ocp/90-smoke-test.sh`, which seeds Redis, opens a
port-forward to the proxy, and runs `smoke.py`.
