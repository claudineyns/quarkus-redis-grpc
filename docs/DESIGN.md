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
    string/v1/string.proto   # StringService  (COMPLETE for v1)
    hash/v1/hash.proto       # HashService  (v0.4.0: HSET/HGET/HDEL/HEXISTS/HLEN, HMGET/HGETALL/HKEYS/HVALS, HSETNX/HINCRBY/HSCAN)
    set/v1/set.proto         # SetService  (v0.3.0: SADD/SREM/SCARD/SISMEMBER/SMISMEMBER/SMEMBERS, SPOP, SSCAN)
    key/v1/key.proto         # KeyService  (COMPLETE: DEL/UNLINK/EXISTS/TYPE, EXPIRE family, TTL/PTTL, SCAN)
    # common/v1/common.proto — deferred until a shared type is needed
  ```
- **Batch extensibility:** the protos MUST be designed to later accommodate a
  unary `Pipeline` RPC (`repeated` request → `repeated` result with per-item
  status) without breaking. See section 5.2.
- **`SET` response semantics:** `applied` reports whether the write happened
  (false when `NX`/`XX` blocks it). With `GET`, `previous` carries the old value
  (absent = key did not exist) and `applied` is deduced from the condition
  (unconditional → true; `NX` → applied iff the key was absent; `XX` → applied
  iff the key existed), since Redis does not state it explicitly under `GET`.

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
- **v0.3.0 scope:** `SADD`, `SREM`, `SMEMBERS`, `SISMEMBER`, `SCARD`,
  `SMISMEMBER`, `SPOP`, `SSCAN`
- **Deferred (future revision):** `SRANDMEMBER`, `SINTER`, `SUNION`, `SDIFF`

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

### 5.2 Batch & pipeline (decision)

**Both deferred** — treated as **two separate fronts**. On resumption,
**batch (MULTI/EXEC) is prioritized before pipeline.**

- **Pipeline (performance, non-atomic).** Reassessed: HTTP/2 already multiplexes,
  so firing N **concurrent** unary calls already collapses them to ~1 edge RTT.
  The pipeline's real gain over concurrent unary is therefore **not RTT** but
  **amortizing per-call overhead** — one auth/**HMAC** instead of N, fewer
  frames/envelopes/trailers, a single internal `redis.batch` — plus **ordering as
  one unit** and avoiding the HTTP/2 `MAX_CONCURRENT_STREAMS` fan-out for very
  large N. Justified for **high-volume bulk workloads**, marginal otherwise.
  Shape: **unary multi-command** RPC with a **generic envelope**
  (`Command{ repeated bytes args }`), NOT a per-command `oneof` (which becomes a
  god-message). Generic ⇒ the **command allowlist is mandatory per item**
  (preserve the curated surface, §6). **Per-item status** (value or error);
  partial failure proceeds. Open question: Vert.x `batch()` may fail-fast on the
  first error → per-item errors likely require a dedicated connection with
  individual send capture. Bidirectional streaming reserved for continuous flows.
- **Batch (atomicity).** `MULTI/EXEC` (optionally `WATCH`) — the one capability
  concurrent unary calls **cannot** emulate. This is the **unique-value** front,
  hence prioritized.

In both cases, today's unary protos must keep fitting a future envelope without
breaking.

---

## 6. Security

- **Caller authentication via access credentials in gRPC metadata** (validated by
  a gRPC interceptor on every call; missing/invalid credential →
  `UNAUTHENTICATED`). The model evolves from a single static token to an
  **ACCESS_KEY/SECRET_KEY pair** — see 6.1. Never in code or committed.
- **Redis AUTH** always enabled (password via secret).
- **Mandatory edge TLS, one-way (no mTLS).** The pod serves server TLS via
  `quarkus-tls-registry` on the unified edge port **8443** (`%prod`:
  `quarkus.http.tls-configuration-name=https`, `quarkus.http.insecure-requests=disabled`);
  the passthrough route terminates TLS at the pod. Cert/key are delivered by a
  **Secret mounted at `/var/certificados/servidor/`**, with the paths injected via
  env var (`QUARKUS_TLS_HTTPS_KEY_STORE_PEM_PROXY_CERT`/`_KEY`). The leaf cert has
  **CN = route host** and **SAN = route host + `localhost`** (localhost eases
  port-forward validation). The client validates the server; the server does
  **not** require a client cert — caller auth is the credential pair (6.1). For
  CRC/dev the cert is a local **CA + leaf** generated by `openssl` (see
  `infra/ocp/25-tls-secret.sh`); in production it comes from the corporate CA.
  Management (9000) stays plaintext/internal.
- **No TLS between app↔Redis.** The proxy↔Redis connection is cleartext inside the
  cluster; that segment is protected by `AUTH` + network isolation (NetworkPolicy/
  namespace), not TLS.
- **gRPC Server Reflection: enabled by default (all environments), gated by
  auth.** Reflection is ON by default (toggle:
  `quarkus.grpc.server.enable-reflection-service`, default `true`) to allow
  service discovery by clients/tools. Discovery is NOT open: the static-token
  interceptor MUST also cover the reflection service, so enumeration requires a
  valid token over TLS (no token → `UNAUTHENTICATED`). Rationale: prefer
  interoperable discovery over security-by-obscurity; the real controls (token +
  TLS + minimal command surface) protect both calls and discovery.
  **[PENDING]** the token interceptor — and its coverage of reflection — is
  designed, not yet implemented.
- **Command allowlist (mandatory).** By design, ONLY the commands of the
  KEY/VALUE, HASH, SET, and KEY-general families (section 5) are exposed.
  Destructive/admin commands (`FLUSHALL`, `FLUSHDB`, `CONFIG`, `KEYS`, `SCRIPT`,
  `SHUTDOWN`, `DEBUG`, etc.) do NOT exist in the gRPC surface — the allowlist IS
  the RPC surface itself, not a configurable toggle to be turned on/off.

### 6.1 Access credentials — ACCESS_KEY / SECRET_KEY

**Status:** implemented — `CredentialValidator` + global `AuthInterceptor`
(covers reflection). Header names are configurable
(`proxy.auth.access-key-header` / `proxy.auth.secret-key-header`, defaults
`x-grpc-access-key` / `x-grpc-secret-key`). Auth is **active only when
`proxy.auth.master-key` is set** (dev/test run without it). **Automatic HTTPS
emission remains future.**

Refines the caller authentication into a credential pair that the proxy validates
**locally**, without storing any per-user secret.

- **Master key (HMAC key):** 64 hex chars (32 bytes, `SecureRandom`), known only
  to the app owners; from an OCP/CRC **Secret** via env `PROXY_AUTH_MASTER_KEY`
  → property `proxy.auth.master-key`. Used **solely** as the HMAC key (no
  encryption/decryption). For HMAC it is the **32 raw bytes (hex-decoded)**.
- **ACCESS_KEY:** 32 hex chars (16 bytes, high-entropy/`SecureRandom`), generated
  by the owners. Public identifier of the credential.
- **SECRET_KEY** = `hex( HMAC-SHA256(key = master-key raw bytes, msg = ACCESS_KEY
  string) )` (32 bytes → 64 hex). One-way: verifiable, not reversible.
- **Allowlist:** a set of `SHA-256(ACCESS_KEY string)` hashes (hex), from an
  OCP/CRC **ConfigMap** (hashes are one-way, not secret) via env
  `PROXY_AUTH_ACCESS_KEY_HASHES` (comma-separated) → property
  `proxy.auth.access-key-hashes`. Plain SHA-256 (no salt) is acceptable because
  the ACCESS_KEY is high-entropy (128-bit random), not a password. Enables
  **per-credential revocation** (remove a hash) without rotating the master key.
- **Validation rule** (in the auth interceptor, over the credential carried in
  gRPC metadata; this interceptor also gates Server Reflection):
  1. `SHA-256(ACCESS_KEY)` ∈ allowlist — authorization/revocation;
  2. `SECRET_KEY == hex(HMAC-SHA256(masterKey, ACCESS_KEY))`, **constant-time**
     compare — proof the pair was issued by the app.
  Both must pass → authenticated; otherwise `UNAUTHENTICATED`.
- **Distribution:** the ACCESS_KEY/SECRET_KEY pair is delivered to end users over
  TLS. Never log keys/secrets.
- **Automatic emission over HTTPS — FUTURE:** an HTTPS endpoint that computes the
  SECRET_KEY from an ACCESS_KEY using the master key (so owners do not compute it
  by hand) will be added later, once there is an adequate repository to store
  end-user access keys. Until then, pairs are produced manually.

---

## 7. Configuration and ports

- **Edge port:** gRPC multiplexed on the unified HTTP server
  (`quarkus.grpc.server.use-separate-server=false`) → **a single TLS HTTP/2
  port** → a single passthrough route.
- **Separate management interface** (`quarkus.management.enabled=true`, port 9000)
  for **health** (probes) and **Prometheus metrics** — internal, outside the edge
  route.
- **Edge exposure via Ingress (corporate constraint).** The external route is
  created from a Kubernetes **Ingress**; OpenShift **auto-generates the
  passthrough Route** from it (annotation
  `route.openshift.io/termination: passthrough`, `pathType: ImplementationSpecific`
  with empty path → host-based routing). The Route is never created directly.
- All sensitive config via secret/env; nothing hardcoded.
- **Container JVM tuning (Java 21):** the JVM image
  (`src/main/docker/Dockerfile.jvm`) pre-sets container-aware
  `JAVA_TOOL_OPTIONS` — RAM percentages 25/75 (vs the JVM's 25% default),
  `InitiatingHeapOccupancyPercent=35`, `MaxGCPauseMillis=200`, and fixed
  `MaxMetaspaceSize`/`ReservedCodeCacheSize`. Scales with the pod memory limit;
  overridable per environment via the container env.

---

## 8. Observability

- **Health:** `quarkus-smallrye-health` on the management port (readiness checks
  connectivity to Redis).
- **Metrics:** Prometheus via Micrometer on the management port.
- **Logging:** see 8.1.

### 8.1 Logging

- **Framework:** JBoss Logging (`org.jboss.logging.Logger`), the Quarkus standard.
- **Levels:** verbose per-command logs at `DEBUG`; lifecycle at `INFO`; problems
  at `WARN`/`ERROR`. `DEBUG` is toggled per environment via the category level
  (default `INFO`), e.g.
  `QUARKUS_LOG_CATEGORY__IO_GITHUB_CLAUDINEYNS__LEVEL=DEBUG`.
- **Format:** plain text in all environments, with the MDC rendered in the
  pattern (`%X`). No JSON.
- **Correlation (`requestId`):** resolved as `traceparent` (W3C trace-id) →
  `x-request-id` (metadata) → generated UUID.
- **MDC fields:** `requestId`, `rpc`, `command`, `key`, `status`, `durationMs`,
  `redisDurationMs`.
- **Security:** NEVER log values or secrets/token. Keys ARE logged in full.
- **Mechanism:** a global gRPC `ServerInterceptor` sets `requestId`/`rpc` and
  emits an access line with `status`/`durationMs`; services add
  `command`/`key`/`redisDurationMs` plus `DEBUG` logs. MDC crosses Mutiny
  boundaries via `quarkus-smallrye-context-propagation`.

---

## 9. Testing

- **Dev Services** bring up a Redis automatically in dev/test (zero config).
- **`@QuarkusTest`** for end-to-end integration tests (real gRPC client → proxy →
  Dev Services Redis).
- **Testcontainers** available for scenarios requiring explicit control (e.g.,
  validating behavior under Sentinel/failover).
- **Coverage:** Jacoco with unit + Quarkus merge already configured; reports to
  Sonar.
- **Local Sonar analysis (optional):** `infra/podman/` starts a local SonarQube
  on podman and runs `verify` + the scanner (see its README).
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
- **Prefer guard clauses over `if/else`.** Whenever practical, avoid `if/else`;
  use early returns ("if-return-or-proceed": handle the simple/edge case with
  `if (cond) return ...;`, then proceed at the top level) to reduce nesting.
  E.g. `toSetResponse`.
- **String constants.** Whenever practical, associate static/repeated string
  literals with named constants (avoids duplication and pre-empts Sonar
  `java:S1192`) — including `switch` case labels, which accept compile-time
  constants (`static final String` initialized from a literal).

---

## 11. Decisions (tracker)

- [x] Final command list per family → Core+Recommended, with *SCAN (section 5).
- [x] Batch/pipeline RPC → out of the initial scope, anticipated (section 5.2).
- [x] Batch & pipeline as two separate fronts → **both deferred**; on resumption
  **batch (MULTI/EXEC) before pipeline**. Pipeline = generic envelope + per-item
  allowlist, a scale optimization (HTTP/2 already covers edge RTT); batch =
  unique atomicity value (section 5.2).
- [x] Token format/source → static token via secret (section 6).
- [x] Command allowlist → mandatory, it is the surface itself (section 6).
- [x] `.proto` organization → 1 service per family + `common.proto` (section 5).
- [x] TLS between app↔Redis → no internal TLS; mandatory one-way edge TLS, no mTLS (section 6).
- [x] Redis → gRPC error propagation format → gRPC status + raw message (section 5.1).
- [x] `.proto` package and versioning scheme → `io.github.claudineyns.redis.grpc.v1`, versioning by directory (section 5).
- [x] Cursor type / key format in `*SCAN` → opaque `string` cursor, `string` keys (section 5).
- [x] SetService v0.3.0 scope → `SADD`/`SREM`/`SCARD`/`SISMEMBER`/`SMISMEMBER`/`SMEMBERS` + `SPOP` + `SSCAN`; **deferred to a future revision:** `SRANDMEMBER`, `SINTER`, `SUNION`, `SDIFF` (section 5).

> All v1-scope architecture decisions are closed.
