# infra/ocp — OpenShift (CRC) smoke deploy

Bash scripts to deploy the `redis-grpc` proxy and a Redis to a local OpenShift
(CRC) for preliminary hot validation.

## Conventions

- `oc` runs as the **`developer`** user, using the structure
  `oc --context ${OC_CXT} -n ${OC_NAMESPACE} <command> [options]`.
- Defaults live in `env.sh` (override by exporting before running):
  - `OC_CXT=crc-developer`
  - `PROXY_NAMESPACE=redis-grpc`, `REDIS_NAMESPACE=redis`
- On Git Bash/MSYS, prefix `oc` calls that take Linux-style paths with
  `MSYS_NO_PATHCONV=1`.

## Scripts

| Script | Purpose |
|---|---|
| `env.sh` | Shared variables (sourced by the others). |
| `00-namespaces.sh` | Create the proxy and Redis projects (idempotent). |
| `10-redis.sh` | Ephemeral Redis Deployment + Service. |
| `20-build-proxy.sh` | Local `mvn package` (fast-jar) + Docker-strategy binary build (`src/main/docker/Dockerfile.jvm` over `ubi9/openjdk-21-runtime`). |
| `30-deploy-proxy.sh` | Proxy Deployment + Service (Redis address via env). |
| `90-smoke-test.sh` | Seed a key and call `StringService/Get` (grpcurl, if present). |
| `deploy-all.sh` | Run all of the above in order. |

## Usage

```bash
bash infra/ocp/deploy-all.sh
```

## Scope and status

Preliminary smoke deploy: in-cluster only (validated via `oc port-forward`).
**Not yet included** (added as the features land): edge TLS, passthrough Route,
auth token Secret, separate management port for health/metrics, NetworkPolicy,
and Redis Sentinel. See `../../docs/DESIGN.md`.
