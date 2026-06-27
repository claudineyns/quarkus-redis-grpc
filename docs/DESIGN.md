# DESIGN.md — `redis-grpc` architecture and design

> Project architecture and technical design document (**English** version).
> Portuguese version: [DESIGN.pt-BR.md](DESIGN.pt-BR.md). **Both files are kept in
> sync** — every design change must be reflected in both.
> The main reference and the agent working guidelines are in
> [../CLAUDE.md](../CLAUDE.md).
> All code produced in this repository MUST adhere to the guidelines below.
> Items marked **[OPEN]** are not yet decided — do not implement assuming a side
> without confirmation.

---

## 1. Purpose and premise

The application is a **gRPC-over-Redis gateway**: a north-south proxy that exposes
Redis commands through a gRPC API, letting external clients reach an internal
cluster Redis by **traversing the corporate edge**, which only forwards HTTP
traffic.

**Why it exists:** Redis' RESP protocol (raw TCP) cannot cross an HTTP edge route.
By wrapping the commands in gRPC (HTTP/2), the external client reaches Redis
through an OpenShift **byte passthrough route**.

**Origin constraint:** corporate policy mandates that Redis run as a container in
OpenShift. In this topology Redis lives in a **separate namespace**, reachable via
an intra-cluster **Service**.

### Network topology

```
External client
   │  TLS / HTTP2 (gRPC)
   ▼
Route (passthrough — TLS terminates at the POD, not at the edge)
   │
   ▼
Service (proxy)  ──►  Proxy pod (this application)
                          │  Redis client (Request/Response, pool, AUTH)
                          ▼
                     Redis Service (separate namespace)
                          │
                          ▼
                     Redis (standalone | sentinel)
```

---

## 2. Mandatory principles

1. **Environment-agnostic code.** The code NEVER assumes its runtime environment.
   The Redis address, mode (standalone/sentinel), credentials, TLS, and ports all
   come 100% from configuration/environment variables. *Optimization* for
   OpenShift lives in configuration/profiles, never in conditional code.

2. **1:1 mapping to Redis commands.** Each gRPC RPC faithfully represents a Redis
   command — same arguments, same semantics, same return type. There is no
   business logic, aggregation, or data transformation in the proxy.

3. **Binary-safe.** Redis is binary-safe. Values travel as `bytes` in protobuf
   (never `string`). Keys are `string`. No UTF-8 conversion/validation over
   values.

4. **Low latency is a first-class requirement.** Every decision weighs its latency
   cost: a non-blocking reactive path end-to-end, a reused connection pool,
   minimal copies/serializations, a single HTTP/2 port.

5. **The proxy does not own the data.** Durability, persistence, and lifecycle are
   the responsibility of the Redis deployment. The proxy exposes TTL/EXPIRE
   faithfully so the client controls expiration.

---

## 3. Stack

- **Quarkus** 3.27.3 (Red Hat build — `com.redhat.quarkus.platform`)
- **Java 21**
- **gRPC** reactive with **Mutiny** (`Uni`/`Multi`)
- **Redis client:** **low-level** — `io.vertx.mutiny.redis.client.Redis` with `Request`/`Response`

### 3.1 Repository scope and extension plan

**This repository is ONLY the proxy** — a **single-module** Maven project
(`io.github.claudineyns:redis-grpc`). No multi-module here. The proxy must
compile, run, and operate **in isolation**.

**Quarkus extension — future adjacent project (decided):**
- Once the proxy reaches a **stable version**, a **separate sibling project** will
  be created (adjacent directory, its own repository/project) solely for the
  high-level client extension.
- That adjacent project will be a **"real" Quarkus extension** — mandatory
  `runtime` + `deployment` split, with `@BuildStep`/`@Recorder` and native image
  support as a goal. Not a simple lib/producer.
- **Governance from here:** this project remains the source of truth that
  **administers** the adjacent project — in particular the **`.proto` contract**,
  which is defined and versioned HERE (role analogous to the shared WSDL/XSD in
  SOAP). The extension will consume this contract; it does not redefine it.

> Practical implication: the `.proto` lives in this repository (`src/main/proto/`)
> and is the contract artifact the adjacent extension project will consume. The
> sharing mechanism (publishing a contract artifact vs. direct reference) will be
> decided in the extension phase.

### Dependencies to add to `pom.xml`

- `io.quarkus:quarkus-grpc` — gRPC server + Mutiny stub generation from `.proto`
- `io.quarkus:quarkus-redis-client` — Redis client (Vert.x)

> Already present and relevant: `quarkus-tls-registry` (TLS served by the pod due
> to passthrough), `quarkus-smallrye-health`, `quarkus-micrometer-registry-prometheus`,
> `quarkus-smallrye-context-propagation`, Testcontainers, JUnit5, Mockito, REST-Assured, Jacoco.

---

## 4. Redis client

- **API:** the low-level Mutiny **`Redis`** client
  (`io.vertx.mutiny.redis.client.Redis`), building commands with
  **`Request`**/**`Command`** and reading **`Response`**. Commands MUST be built
  with `Request.cmd(Command.X).arg(...)`, using **byte[] args for values**
  (binary-safe); a `null` `Response` means Redis nil. The typed
  `RedisAPI`/`ReactiveRedisAPI` is **not used** — its methods are `String`-typed
  and would break binary-safety. The high-level `ReactiveRedisDataSource` is
  **forbidden** as well — its typing and Jackson serialization break 1:1 fidelity.
- **Supported modes (via config, no conditional code):**
  - **Standalone:** `quarkus.redis.hosts=redis://<host>:<port>`
  - **Sentinel:** `quarkus.redis.client-type=sentinel`,
    `quarkus.redis.hosts=redis://<sentinel1>,redis://<sentinel2>,...`,
    `quarkus.redis.master-name=<master>`
- **AUTH:** `quarkus.redis.password` (via secret/env). Never in code or committed.
- **Pool:** size `quarkus.redis.max-pool-size` / timeouts according to load
  (tuned in the OpenShift profile).

---

## 5. gRPC / protobuf modeling

- **Per-command typed messages.** Each command has its own `Request`/`Response`,
  mirroring its arguments and return. No generic envelope.
- **Types:** key `string`; value/field `bytes`; counters/cardinalities `int64`;
  flags as `optional`/dedicated enums.
- **Absence (Redis nil):** represent it explicitly (e.g., `optional bytes` or a
  `bool found` field). Never conflate "empty" with "absent".
- **Errors (see section 5.1).** Real Redis errors become **gRPC status**;
  nil/zero/negative results are NOT errors and stay in the payload.
- **Layout:** `.proto` under `src/main/proto/`, **one service per family** —
  `StringService`, `HashService`, `SetService`, `KeyService`. Families evolve and
  version independently; the number of services costs no latency (they all share
  the same HTTP/2 connection).
- **`common.proto` is deferred.** Originally planned for shared types, it is NOT
  created until a genuinely shared type emerges. Errors travel via gRPC status
  using `google.rpc.ErrorInfo` (resolved on the Java side), so no `RedisError`
  payload type is needed in the contract; expiration options are modeled
  per-command for now.
- **Java options (per file):** each family `.proto` sets
  `option java_multiple_files = true` and an explicit
  `option java_outer_classname = "<Family>Proto"`. The explicit outer class name
  is **mandatory** to avoid the name protobuf derives from the filename colliding
  with Java types — e.g., `string.proto` would otherwise generate a `String` outer
  class that shadows `java.lang.String` and breaks compilation.
- **Package and version:** proto package `io.github.claudineyns.redis.grpc.v1`
  (aligned with the `groupId`), with a matching `option java_package`.
  Versioning by directory/package (`...v1`) — a future `v2` coexists with `v1`
  without breaking. File structure:
  ```
  src/main/proto/
    string/v1/string.proto   # StringService  (GET, SET so far)
    hash/v1/hash.proto       # HashService
    set/v1/set.proto         # SetService
    key/v1/key.proto         # KeyService
    # common/v1/common.proto — deferred until a shared type is needed
  ```
- **Batch extensibility:** the protos MUST be designed to later accommodate a
  unary `Pipeline` RPC (`repeated` request → `repeated` result with per-item
  status) without breaking. See section 5.2.

### Command surface (CLOSED — initial v1 scope)

Scope = **Core + Recommended** of the four families. "Optional" commands remain
v2 candidates (adding a command later is a non-breaking change).

**KEY/VALUE — `StringService`**
- `SET` (with `EX`/`PX`/`EXAT`/`PXAT`/`NX`/`XX`/`KEEPTTL`/`GET`), `GET`, `MSET`,
  `MGET`, `INCR`, `DECR`, `INCRBY`, `DECRBY`, `GETDEL`, `GETEX`, `APPEND`, `STRLEN`

**KEY/HASH — `HashService`**
- `HSET` (multi-field), `HGET`, `HMGET`, `HGETALL`, `HDEL`, `HEXISTS`, `HLEN`,
  `HKEYS`, `HVALS`, `HSETNX`, `HINCRBY`, `HSCAN`

**SET — `SetService`**
- `SADD`, `SREM`, `SMEMBERS`, `SISMEMBER`, `SCARD`, `SMISMEMBER`, `SPOP`,
  `SRANDMEMBER`, `SSCAN`, `SINTER`, `SUNION`, `SDIFF`

**KEY (general) — `KeyService`**
- `DEL`, `EXISTS`, `EXPIRE`, `PEXPIRE`, `TTL`, `PTTL`, `PERSIST`, `TYPE`,
  `UNLINK`, `EXPIREAT`, `PEXPIREAT`, `SCAN`

> **Cursor (`SCAN`/`HSCAN`/`SSCAN`):** cursor-based iteration — modeled as a unary
> RPC with the cursor in the request and `{ next cursor + page }` in the response,
> mirroring the Redis protocol 1:1 (the client drives the loop; the proxy does not
> iterate).
> - **The cursor is an opaque `string`** ("0" = start and end of iteration). The
>   client only returns the received value, without interpreting it.
> - **Returned keys/fields/members are `string`** (section 5 convention). Note:
>   Redis keys are technically binary-safe; `bytes` remains a possible future
>   refinement, not adopted now.
>
> **Out of v1 scope (v2 candidates):** `GETSET`, `INCRBYFLOAT`, `SETRANGE`,
> `GETRANGE`, `HINCRBYFLOAT`, `HRANDFIELD`, `SMOVE`, `S*STORE`, `SINTERCARD`,
> `RENAME`, `RENAMENX`, `EXPIRETIME`, `PEXPIRETIME`.

### 5.1 Error propagation (decision)

**Mental rule:** the command's semantic result = **payload**; a real Redis error
or a transport failure = **gRPC status**.

**CRITICAL — nil/zero/negative is NOT an error.** Many Redis returns are legitimate
successes and MUST stay in the payload (`optional` / `bool found` / counters),
never become an error status:
- `GET`/`GETDEL`/`GETEX`/`SPOP` on absence → nil (a cache miss is success)
- `SET ... NX/XX` that does not write → nil
- `EXISTS`, `SISMEMBER`, `DEL`, `EXPIRE`, `HEXISTS`... → 0 is a valid result

| Situation | Output |
|---|---|
| Normal result, incl. nil/0/negative | **Payload** |
| Redis RESP error (`WRONGTYPE`, `ERR…`, `OOM`) | **gRPC status** + raw Redis code/message in `google.rpc.ErrorInfo`/trailers |
| Infra failure (connection/timeout/Redis down) | **gRPC status** `UNAVAILABLE` / `DEADLINE_EXCEEDED` |
| Missing/invalid token | **gRPC status** `UNAUTHENTICATED` (interceptor, before Redis) |

RESP error → gRPC status mapping:
- `WRONGTYPE` → `FAILED_PRECONDITION`
- invalid syntax/arguments → `INVALID_ARGUMENT`
- `OOM` → `RESOURCE_EXHAUSTED`
- `NOAUTH`/`WRONGPASS` (proxy config) → `INTERNAL`
- other `ERR …` → `INTERNAL`

The **raw Redis message** always accompanies the status (details/trailers) to
preserve 1:1 fidelity.

### 5.2 Batch/pipeline (decision)

**Out of the initial scope**, but anticipated. Rationale: the expensive RTT is the
**edge hop** (client↔cluster); a batch RPC would send N commands in 1 edge RTT,
pipelining against Redis. When it lands, it will be a **unary `Pipeline` RPC**
(`repeated` request via per-command `oneof` → `repeated` result with **per-item
status**), NOT bidirectional streaming (reserved for continuous flows).

Rules when implemented:
- It is **pipelining, not a transaction** — no atomicity guarantee (`MULTI/EXEC`
  is not covered). Document this explicitly for the client.
- **Partial failure:** the remaining commands proceed; each item carries its own
  status. Today's unary protos must already be designed to fit into that envelope
  without breaking.

---

## 6. Security

- **Static token in gRPC metadata.** A fixed API key provided via secret/env,
  validated by a **gRPC interceptor** on every call. No valid token →
  `UNAUTHENTICATED`. Never in code or committed.
- **Redis AUTH** always enabled (password via secret).
- **Mandatory edge TLS, one-way (no mTLS).** The pod serves server TLS via
  `quarkus-tls-registry` (the route is passthrough, so TLS terminates at the pod).
  The client validates the server; the server does **not** require a client
  certificate — caller authentication is done by the **static token in metadata**.
- **No TLS between app↔Redis.** The proxy↔Redis connection is cleartext inside the
  cluster; that segment is protected by `AUTH` + network isolation (NetworkPolicy/
  namespace), not TLS.
- **Command allowlist (mandatory).** By design, ONLY the commands of the
  KEY/VALUE, HASH, SET, and KEY-general families (section 5) are exposed.
  Destructive/admin commands (`FLUSHALL`, `FLUSHDB`, `CONFIG`, `KEYS`, `SCRIPT`,
  `SHUTDOWN`, `DEBUG`, etc.) do NOT exist in the gRPC surface — the allowlist IS
  the RPC surface itself, not a configurable toggle to be turned on/off.

---

## 7. Configuration and ports

- **Edge port:** gRPC multiplexed on the unified HTTP server
  (`quarkus.grpc.server.use-separate-server=false`) → **a single TLS HTTP/2
  port** → a single passthrough route.
- **Separate management interface** (`quarkus.management.enabled=true`, port 9000)
  for **health** (probes) and **Prometheus metrics** — internal, outside the edge
  route.
- All sensitive config via secret/env; nothing hardcoded.

---

## 8. Observability

- **Health:** `quarkus-smallrye-health` on the management port (readiness checks
  connectivity to Redis).
- **Metrics:** Prometheus via Micrometer on the management port.
- Structured logs; never log values/secrets.

---

## 9. Testing

- **Dev Services** bring up a Redis automatically in dev/test (zero config).
- **`@QuarkusTest`** for end-to-end integration tests (real gRPC client → proxy →
  Dev Services Redis).
- **Testcontainers** available for scenarios requiring explicit control (e.g.,
  validating behavior under Sentinel/failover).
- **Coverage:** Jacoco with unit + Quarkus merge already configured; reports to
  Sonar.
- Every exposed command must have tests covering: happy path, absence (nil), and
  type error (`WRONGTYPE`).
- **Test-specific config** lives in `src/test/resources/application.properties`
  (merged with the main file during tests, taking precedence) — e.g., the gRPC
  test client host/port. No `%test.` prefix needed there.

---

## 10. Code conventions

- **Reactive (Mutiny) is the priority** — end-to-end; **do not block the event
  loop**; no `@Blocking` on the hot path.
- **Virtual threads only when necessary.** When a thread for general-purpose work
  is genuinely needed (unavoidable blocking offload, background task), prefer
  **virtual threads** (`@RunOnVirtualThread`) over the traditional worker thread
  pool. It is not the default model of the hot path — it is the exception. At those
  points, avoid `synchronized` (use `ReentrantLock`) to prevent pinning on
  Java 21.
- Field injection (CDI) is accepted (Sonar rule `java:S6813` ignored in the pom).
- No business logic in the proxy: translate, forward, translate back.
- **`final` wherever applicable.** Local variables and method arguments MUST be
  declared `final` where there is no reassignment. Reinforces immutability and
  intent. (CDI-injected fields are a natural exception — they cannot be `final`.)
- **Didactic comments.** This is also a learning project: enrich implemented
  methods with explanatory comments wherever pertinent — favor the *why* (Redis
  semantics, gRPC/protobuf decisions, mapping choices) over the obvious *what*.

---

## 11. Decisions (tracker)

- [x] Final command list per family → Core+Recommended, with *SCAN (section 5).
- [x] Batch/pipeline RPC → out of the initial scope, anticipated (section 5.2).
- [x] Token format/source → static token via secret (section 6).
- [x] Command allowlist → mandatory, it is the surface itself (section 6).
- [x] `.proto` organization → 1 service per family + `common.proto` (section 5).
- [x] TLS between app↔Redis → no internal TLS; mandatory one-way edge TLS, no mTLS (section 6).
- [x] Redis → gRPC error propagation format → gRPC status + raw message (section 5.1).
- [x] `.proto` package and versioning scheme → `io.github.claudineyns.redis.grpc.v1`, versioning by directory (section 5).
- [x] Cursor type / key format in `*SCAN` → opaque `string` cursor, `string` keys (section 5).

> All v1-scope architecture decisions are closed.
