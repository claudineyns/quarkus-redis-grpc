# DESIGN.pt-BR.md — Arquitetura e design do `redis-grpc`

> Documento de arquitetura e design técnico do projeto (versão em **português**).
> Versão em inglês: [DESIGN.md](DESIGN.md). **Os dois arquivos são mantidos em
> sincronia** — toda alteração de design deve ser refletida em ambos.
> A referência principal e as orientações de trabalho do agente estão em
> [../CLAUDE.md](../CLAUDE.md).
> Todo código produzido neste repositório DEVE aderir às diretrizes abaixo.
> Itens marcados como **[EM ABERTO]** ainda não estão decididos — não implemente
> assumindo um lado sem confirmação.

---

## 1. Propósito e premissa

A aplicação é um **gateway gRPC sobre Redis**: um proxy norte-sul que expõe
comandos Redis através de uma API gRPC, permitindo que clientes externos
alcancem um Redis interno do cluster **atravessando a borda corporativa**, que
só encaminha tráfego HTTP.

**Por que existe:** o protocolo RESP do Redis (TCP cru) não atravessa uma route
HTTP de borda. Envelopando os comandos em gRPC (HTTP/2), o cliente externo
alcança o Redis através de uma **route com passthrough de bytes**.

**Restrição de origem:** política corporativa impõe que o Redis seja executado
como container em OpenShift. Nesta topologia o Redis vive em **namespace
separado**, alcançável por **Service** intra-cluster.

### Topologia de rede

```
Cliente externo
   │  TLS / HTTP2 (gRPC)
   ▼
Route (passthrough — TLS termina no POD, não na borda)
   │
   ▼
Service (proxy)  ──►  Pod proxy (esta aplicação)
                          │  Cliente Redis (Request/Response, pool, AUTH)
                          ▼
                     Service Redis (namespace separado)
                          │
                          ▼
                     Redis (standalone | sentinel)
```

---

## 2. Princípios mandatórios

1. **Agnosticismo de ambiente no código.** O código NUNCA assume o ambiente de
   execução. Endereço do Redis, modo (standalone/sentinel), credenciais, TLS e
   portas vêm 100% de configuração/variáveis de ambiente. A *otimização* para
   OpenShift mora na configuração/profile, nunca em código condicional.

2. **Mapeamento 1:1 com comandos Redis.** Cada RPC gRPC representa fielmente um
   comando Redis — mesmos argumentos, mesma semântica, mesmo tipo de retorno.
   Não há lógica de negócio, agregação ou transformação de dados no proxy.

3. **Binary-safe.** Redis é binary-safe. Valores trafegam como `bytes` no
   protobuf (nunca `string`). Chaves são `string`. Nada de conversão/validação
   UTF-8 sobre valores.

4. **Baixa latência é requisito de primeira classe.** Toda decisão considera
   custo de latência: caminho reativo não-bloqueante fim-a-fim, pool de
   conexões reutilizado, mínimo de cópias/serializações, porta única HTTP/2.

5. **O proxy não é dono do dado.** Durabilidade, persistência e ciclo de vida
   são responsabilidade do deployment do Redis. O proxy expõe TTL/EXPIRE com
   fidelidade para que o cliente controle expiração.

---

## 3. Stack

- **Quarkus** 3.27.3 (build Red Hat — `com.redhat.quarkus.platform`)
- **Java 21**
- **gRPC** reativo com **Mutiny** (`Uni`/`Multi`)
- **Cliente Redis:** **baixo nível** — `io.vertx.mutiny.redis.client.Redis` com `Request`/`Response`

### 3.1 Escopo do repositório e plano da extensão

**Este repositório é APENAS o proxy** — projeto Maven **módulo único**
(`io.github.claudineyns:redis-grpc`). Nada de multi-módulo aqui. O proxy deve
compilar, rodar e operar **isoladamente**.

**Extensão Quarkus — projeto adjacente futuro (decidido):**
- Quando o proxy atingir uma **versão estável**, será criado um **projeto irmão
  separado** (diretório adjacente, repositório/projeto próprio) só para a
  extensão cliente de alto nível.
- Esse projeto adjacente será uma **extensão Quarkus "de verdade"** — split
  obrigatório `runtime` + `deployment`, com `@BuildStep`/`@Recorder` e suporte a
  native image como objetivo. Não é lib/producer simples.
- **Governança a partir daqui:** este projeto permanece a fonte de verdade que
  **administra** o projeto adjacente — em especial o **contrato `.proto`**, que é
  definido e versionado AQUI (papel análogo ao WSDL/XSD compartilhado em SOAP). A
  extensão consumirá esse contrato; não o redefine.

> Implicação prática: o `.proto` mora neste repositório (`src/main/proto/`) e é o
> artefato de contrato que o projeto adjacente da extensão irá consumir. A
> mecânica de compartilhamento (publicar artefato de contrato vs. referência
> direta) será decidida na fase da extensão.

### Dependências a adicionar ao `pom.xml`

- `io.quarkus:quarkus-grpc` — servidor gRPC + geração de stubs Mutiny a partir de `.proto`
- `io.quarkus:quarkus-redis-client` — cliente Redis (Vert.x)

> Já presentes e relevantes: `quarkus-tls-registry` (TLS servido pelo pod por
> causa do passthrough), `quarkus-smallrye-health`, `quarkus-micrometer-registry-prometheus`,
> `quarkus-smallrye-context-propagation`, Testcontainers, JUnit5, Mockito, REST-Assured, Jacoco.

---

## 4. Cliente Redis

- **API:** o cliente Mutiny de baixo nível **`Redis`**
  (`io.vertx.mutiny.redis.client.Redis`), montando comandos com
  **`Request`**/**`Command`** e lendo **`Response`**. Os comandos DEVEM ser
  montados com `Request.cmd(Command.X).arg(...)`, usando **args byte[] para
  valores** (binary-safe); um `Response` `null` significa nil do Redis. A
  `RedisAPI`/`ReactiveRedisAPI` tipada **não é usada** — seus métodos são tipados
  como `String` e quebrariam o binary-safe. O alto nível `ReactiveRedisDataSource`
  também é **proibido** — sua tipagem e serialização Jackson quebram a
  fidelidade 1:1.
- **Modos suportados (via config, sem código condicional):**
  - **Standalone:** `quarkus.redis.hosts=redis://<host>:<port>`
  - **Sentinel:** `quarkus.redis.client-type=sentinel`,
    `quarkus.redis.hosts=redis://<sentinel1>,redis://<sentinel2>,...`,
    `quarkus.redis.master-name=<master>`
- **AUTH:** `quarkus.redis.password` (via secret/env). Nunca em código ou commit.
- **Pool:** dimensionar `quarkus.redis.max-pool-size` / timeouts conforme carga
  (afinado no profile OpenShift).

---

## 5. Modelagem gRPC / protobuf

- **Mensagens tipadas por comando.** Cada comando tem `Request`/`Response`
  próprios, espelhando seus argumentos e retorno. Sem envelope genérico.
- **Tipos:** chave `string`; valor/campo `bytes`; contadores/cardinalidades
  `int64`; flags como `optional`/enums dedicados.
- **Ausência (nil do Redis):** representar explicitamente (ex.: `optional bytes`
  ou um campo `bool found`). Nunca confundir "vazio" com "ausente".
- **Erros (ver seção 5.1).** Erros reais do Redis viram **status gRPC**;
  resultados nil/zero/negativos NÃO são erros e permanecem no payload.
- **Layout:** `.proto` em `src/main/proto/`, **um service por família** —
  `StringService`, `HashService`, `SetService`, `KeyService`. Famílias evoluem e
  versionam isolada­mente; o número de services não custa latência (todos
  compartilham a mesma conexão HTTP/2).
- **`common.proto` está adiado.** Originalmente previsto para tipos
  compartilhados, NÃO é criado até surgir um tipo realmente compartilhado. Erros
  trafegam pelo status gRPC via `google.rpc.ErrorInfo` (resolvido no lado Java),
  então não é preciso um tipo de payload `RedisError` no contrato; opções de
  expiração são modeladas por comando por ora.
- **Opções Java (por arquivo):** cada `.proto` de família define
  `option java_multiple_files = true` e um `option java_outer_classname =
  "<Família>Proto"` explícito. O nome explícito da outer class é **obrigatório**
  para evitar que o nome derivado pelo protobuf a partir do arquivo colida com
  tipos Java — ex.: `string.proto` geraria, senão, uma outer class `String` que
  ofusca `java.lang.String` e quebra a compilação.
- **Pacote e versão:** pacote proto `io.github.claudineyns.redis.grpc.v1`
  (alinhado ao `groupId`), com `option java_package` correspondente.
  Versionamento por diretório/pacote (`...v1`) — um `v2` futuro coexiste com
  `v1` sem quebra. Estrutura de arquivos:
  ```
  src/main/proto/
    string/v1/string.proto   # StringService  (COMPLETO para v1)
    hash/v1/hash.proto       # HashService  (v0.4.0: HSET/HGET/HDEL/HEXISTS/HLEN, HMGET/HGETALL/HKEYS/HVALS, HSETNX/HINCRBY/HSCAN)
    set/v1/set.proto         # SetService  (v0.3.0: SADD/SREM/SCARD/SISMEMBER/SMISMEMBER/SMEMBERS, SPOP, SSCAN)
    key/v1/key.proto         # KeyService  (COMPLETO: DEL/UNLINK/EXISTS/TYPE, família EXPIRE, TTL/PTTL, SCAN)
    # common/v1/common.proto — adiado até haver um tipo compartilhado
  ```
- **Extensibilidade para batch:** os protos DEVEM ser desenhados para acomodar
  futuramente um RPC unário `Pipeline` (`repeated` request → `repeated` result
  com status por item) sem quebra. Ver seção 5.2.
- **Semântica da resposta do `SET`:** `applied` informa se a gravação ocorreu
  (false quando `NX`/`XX` barra). Com `GET`, `previous` carrega o valor antigo
  (ausente = a chave não existia) e `applied` é deduzido da condição
  (incondicional → true; `NX` → aplicou sse a chave estava ausente; `XX` →
  aplicou sse a chave existia), pois o Redis não informa isso explicitamente sob
  `GET`.

### Superfície de comandos (FECHADA — escopo inicial v1)

Escopo = **Núcleo + Recomendado** das quatro famílias. Comandos "Opcionais"
ficam como candidatos a v2 (adicionar comando depois é mudança não-quebra).

**KEY/VALUE — `StringService`**
- `SET` (com `EX`/`PX`/`EXAT`/`PXAT`/`NX`/`XX`/`KEEPTTL`/`GET`), `GET`, `MSET`,
  `MGET`, `INCR`, `DECR`, `INCRBY`, `DECRBY`, `GETDEL`, `GETEX`, `APPEND`, `STRLEN`

**KEY/HASH — `HashService`**
- `HSET` (multi-campo), `HGET`, `HMGET`, `HGETALL`, `HDEL`, `HEXISTS`, `HLEN`,
  `HKEYS`, `HVALS`, `HSETNX`, `HINCRBY`, `HSCAN`

**SET — `SetService`**
- **Escopo v0.3.0:** `SADD`, `SREM`, `SMEMBERS`, `SISMEMBER`, `SCARD`,
  `SMISMEMBER`, `SPOP`, `SSCAN`
- **Adiados (revisão futura):** `SRANDMEMBER`, `SINTER`, `SUNION`, `SDIFF`

**KEY (geral) — `KeyService`**
- `DEL`, `EXISTS`, `EXPIRE`, `PEXPIRE`, `TTL`, `PTTL`, `PERSIST`, `TYPE`,
  `UNLINK`, `EXPIREAT`, `PEXPIREAT`, `SCAN`

> **Cursor (`SCAN`/`HSCAN`/`SSCAN`):** iteração por cursor — modelados como RPC
> unário com cursor no request e `{ próximo cursor + página }` no response,
> espelhando o protocolo Redis 1:1 (o cliente conduz o loop; o proxy não itera).
> - **Cursor é `string` opaco** ("0" = início e fim da iteração). O cliente
>   apenas devolve o valor recebido, sem interpretá-lo.
> - **Chaves/campos/membros devolvidos são `string`** (convenção da seção 5).
>   Nota: chaves Redis são tecnicamente binary-safe; `bytes` fica como refinamento
>   futuro possível, não adotado agora.
>
> **Fora do escopo v1 (candidatos a v2):** `GETSET`, `INCRBYFLOAT`, `SETRANGE`,
> `GETRANGE`, `HINCRBYFLOAT`, `HRANDFIELD`, `SMOVE`, `S*STORE`, `SINTERCARD`,
> `RENAME`, `RENAMENX`, `EXPIRETIME`, `PEXPIRETIME`.

### 5.1 Propagação de erros (decisão)

**Regra mental:** resultado semântico do comando = **payload**; erro real do
Redis ou falha de transporte = **status gRPC**.

**CRÍTICO — nil/zero/negativo NÃO é erro.** Vários retornos do Redis são
sucessos legítimos e DEVEM ficar no payload (`optional` / `bool found` /
contadores), nunca virar status de erro:
- `GET`/`GETDEL`/`GETEX`/`SPOP` em ausência → nil (cache miss é sucesso)
- `SET ... NX/XX` que não grava → nil
- `EXISTS`, `SISMEMBER`, `DEL`, `EXPIRE`, `HEXISTS`... → 0 é resultado válido

| Situação | Saída |
|---|---|
| Resultado normal, incl. nil/0/negativo | **Payload** |
| Erro RESP do Redis (`WRONGTYPE`, `ERR…`, `OOM`) | **Status gRPC** + código/mensagem crus do Redis em `google.rpc.ErrorInfo`/trailers |
| Falha de infra (conexão/timeout/Redis down) | **Status gRPC** `UNAVAILABLE` / `DEADLINE_EXCEEDED` |
| Token ausente/inválido | **Status gRPC** `UNAUTHENTICATED` (interceptor, antes do Redis) |

Mapeamento erro RESP → status gRPC:
- `WRONGTYPE` → `FAILED_PRECONDITION`
- sintaxe/argumentos inválidos → `INVALID_ARGUMENT`
- `OOM` → `RESOURCE_EXHAUSTED`
- `NOAUTH`/`WRONGPASS` (config do proxy) → `INTERNAL`
- demais `ERR …` → `INTERNAL`

A **mensagem crua do Redis** sempre acompanha o status (details/trailers) para
preservar fidelidade 1:1.

### 5.2 Batch/pipeline (decisão)

**Fora do escopo inicial**, mas previsto. Justificativa: o RTT caro é o **salto
de borda** (cliente↔cluster); um RPC de lote enviaria N comandos em 1 RTT de
borda, com pipelining contra o Redis. Quando entrar, será um **RPC unário
`Pipeline`** (`repeated` request via `oneof` por comando → `repeated` result com
**status por item**), NÃO streaming bidirecional (reservado para fluxo contínuo).

Regras quando implementado:
- É **pipelining, não transação** — sem garantia de atomicidade (`MULTI/EXEC`
  não está coberto). Documentar explicitamente para o cliente.
- **Falha parcial:** demais comandos prosseguem; cada item carrega seu próprio
  status. Os protos unários de hoje já devem ser desenhados para encaixar nesse
  envelope sem quebra.

---

## 6. Segurança

- **Autenticação do chamador via credenciais de acesso em metadata gRPC**
  (validadas por um interceptor gRPC em toda chamada; credencial ausente/inválida
  → `UNAUTHENTICATED`). O modelo evolui do token estático único para um **par
  ACCESS_KEY/SECRET_KEY** — ver 6.1. Nunca em código ou commit.
- **AUTH no Redis** sempre habilitado (senha via secret).
- **TLS de borda obrigatório, one-way (sem mTLS).** O pod serve TLS de servidor
  via `quarkus-tls-registry` na porta de borda unificada **8443** (`%prod`:
  `quarkus.http.tls-configuration-name=https`, `quarkus.http.insecure-requests=disabled`);
  a route passthrough termina o TLS no pod. Cert/key são entregues por um
  **Secret montado em `/var/certificados/servidor/`**, com os caminhos injetados
  por env var (`QUARKUS_TLS_HTTPS_KEY_STORE_PEM_PROXY_CERT`/`_KEY`). O cert folha
  tem **CN = host da Route** e **SAN = host da Route + `localhost`** (localhost
  facilita validar via port-forward). O cliente valida o servidor; o servidor
  **não** exige certificado de cliente — a autenticação do chamador é o par de
  credenciais (6.1). No CRC/dev o cert é uma **CA local + folha** gerada por
  `openssl` (ver `infra/ocp/25-tls-secret.sh`); em produção vem da CA corporativa.
  O management (9000) permanece plaintext/interno.
- **Sem TLS entre app↔Redis.** A conexão proxy↔Redis é em texto claro dentro do
  cluster; a proteção desse trecho é `AUTH` + isolamento de rede (NetworkPolicy/
  namespace), não TLS.
- **gRPC Server Reflection: habilitado por padrão (todos os ambientes), atrás de
  autenticação.** O reflection fica ON por padrão (toggle:
  `quarkus.grpc.server.enable-reflection-service`, default `true`) para permitir
  a descoberta de serviços por clientes/ferramentas. A descoberta NÃO é aberta: o
  interceptor de token opaco DEVE cobrir também o serviço de reflection, de modo
  que enumerar exija um token válido sobre TLS (sem token → `UNAUTHENTICATED`).
  Racional: preferir descoberta interoperável a segurança-por-obscuridade; os
  controles reais (token + TLS + superfície mínima de comandos) protegem tanto as
  chamadas quanto a descoberta. **[PENDENTE]** o interceptor de token — e a sua
  cobertura do reflection — está desenhado, ainda não implementado.
- **Allowlist de comandos (mandatória).** Por design, SOMENTE os comandos das
  famílias KEY/VALUE, HASH, SET e KEY-geral (seção 5) são expostos. Comandos
  destrutivos/admin (`FLUSHALL`, `FLUSHDB`, `CONFIG`, `KEYS`, `SCRIPT`,
  `SHUTDOWN`, `DEBUG`, etc.) NÃO existem na superfície gRPC — a allowlist é a
  própria superfície de RPCs, não uma trava configurável a ser ligada/desligada.

### 6.1 Credenciais de acesso — ACCESS_KEY / SECRET_KEY

**Status:** implementado — `CredentialValidator` + `AuthInterceptor` global
(cobre o reflection). Nomes dos headers configuráveis
(`proxy.auth.access-key-header` / `proxy.auth.secret-key-header`, defaults
`x-grpc-access-key` / `x-grpc-secret-key`). Auth **ativa só quando
`proxy.auth.master-key` está presente** (dev/test rodam sem ela). **A emissão
automática via HTTPS continua futura.**

Refina a autenticação do chamador em um par de credenciais que o proxy valida
**localmente**, sem armazenar nenhum segredo por usuário.

- **Chave mestra (chave do HMAC):** hex de 64 (32 bytes, `SecureRandom`),
  conhecida só pelos responsáveis; de um **Secret** OCP/CRC via env
  `PROXY_AUTH_MASTER_KEY` → propriedade `proxy.auth.master-key`. Usada
  **exclusivamente** como chave do HMAC (sem cifra/decifra). No HMAC, são os
  **32 bytes crus (hex-decoded)**.
- **ACCESS_KEY:** hex de 32 (16 bytes, alta entropia/`SecureRandom`), gerado pelos
  responsáveis. Identificador público da credencial.
- **SECRET_KEY** = `hex( HMAC-SHA256(key = bytes crus da chave mestra, msg =
  string ACCESS_KEY) )` (32 bytes → 64 hex). Mão única: verificável, não
  reversível.
- **Allowlist:** conjunto de hashes `SHA-256(string ACCESS_KEY)` (hex), de um
  **ConfigMap** OCP/CRC (hashes são mão única, não são segredo) via env
  `PROXY_AUTH_ACCESS_KEY_HASHES` (separados por vírgula) → propriedade
  `proxy.auth.access-key-hashes`. SHA-256 sem salt é aceitável porque o
  ACCESS_KEY é de alta entropia (128 bits aleatórios), não uma senha. Permite
  **revogação por credencial** (remover um hash) sem rotacionar a chave mestra.
- **Regra de validação** (no interceptor de auth, sobre a credencial trafegada na
  metadata gRPC; este interceptor também cobre o Server Reflection):
  1. `SHA-256(ACCESS_KEY)` ∈ allowlist — autorização/revogação;
  2. `SECRET_KEY == hex(HMAC-SHA256(chave_mestra, ACCESS_KEY))`, comparação em
     **tempo constante** — prova de que o par foi emitido pela app.
  Ambos ⇒ autenticado; caso contrário `UNAUTHENTICATED`.
- **Distribuição:** o par ACCESS_KEY/SECRET_KEY é entregue aos usuários finais
  sobre TLS. Nunca logar chaves/segredos.
- **Emissão automática via HTTPS — FUTURO:** um endpoint HTTPS que calcula o
  SECRET_KEY a partir de um ACCESS_KEY usando a chave mestra (para os responsáveis
  não calcularem à mão) será adicionado depois, quando houver repositório adequado
  para guardar os access keys dos usuários finais. Até lá, os pares são produzidos
  manualmente.

---

## 7. Configuração e portas

- **Porta de borda:** gRPC multiplexado no servidor HTTP unificado
  (`quarkus.grpc.server.use-separate-server=false`) → **uma única porta TLS
  HTTP/2** → uma só route de passthrough.
- **Interface de management separada** (`quarkus.management.enabled=true`,
  porta 9000) para **health** (probes) e **métricas Prometheus** — internos,
  fora da route de borda.
- **Exposição de borda via Ingress (restrição corporativa).** A route externa é
  criada a partir de um **Ingress** Kubernetes; o OpenShift **gera a Route
  passthrough automaticamente** (anotação
  `route.openshift.io/termination: passthrough`, `pathType: ImplementationSpecific`
  com path vazio → roteamento por host). A Route nunca é criada diretamente.
- Toda config sensível por secret/env; nada hardcoded.
- **Tuning de JVM no container (Java 21):** a imagem JVM
  (`src/main/docker/Dockerfile.jvm`) pré-configura `JAVA_TOOL_OPTIONS`
  container-aware — percentuais de RAM 25/75 (vs. o default de 25% da JVM),
  `InitiatingHeapOccupancyPercent=35`, `MaxGCPauseMillis=200` e teto fixo de
  `MaxMetaspaceSize`/`ReservedCodeCacheSize`. Escala com o memory limit do pod;
  sobreponível por ambiente via env do container.

---

## 8. Observabilidade

- **Health:** `quarkus-smallrye-health` na porta de management (readiness checa
  conectividade com o Redis).
- **Métricas:** Prometheus via Micrometer na porta de management.
- **Logging:** ver 8.1.

### 8.1 Logging

- **Framework:** JBoss Logging (`org.jboss.logging.Logger`), o padrão do Quarkus.
- **Níveis:** logs verbosos por comando em `DEBUG`; ciclo de vida em `INFO`;
  problemas em `WARN`/`ERROR`. O `DEBUG` é ligado/desligado por ambiente via o
  nível da categoria (default `INFO`), ex.:
  `QUARKUS_LOG_CATEGORY__IO_GITHUB_CLAUDINEYNS__LEVEL=DEBUG`.
- **Formato:** texto puro em todos os ambientes, com o MDC renderizado no
  pattern (`%X`). Sem JSON.
- **Correlação (`requestId`):** resolvido como `traceparent` (trace-id do W3C) →
  `x-request-id` (metadata) → UUID gerado.
- **Campos de MDC:** `requestId`, `rpc`, `command`, `key`, `status`,
  `durationMs`, `redisDurationMs`.
- **Segurança:** NUNCA logar valores nem segredos/token. Chaves SÃO logadas por
  inteiro.
- **Mecanismo:** um `ServerInterceptor` gRPC global popula `requestId`/`rpc` e
  emite uma linha de acesso com `status`/`durationMs`; os serviços adicionam
  `command`/`key`/`redisDurationMs` e logs de `DEBUG`. O MDC atravessa as
  fronteiras do Mutiny via `quarkus-smallrye-context-propagation`.

---

## 9. Testes

- **Dev Services** sobem um Redis automaticamente em dev/test (zero config).
- **`@QuarkusTest`** para testes de integração ponta a ponta (cliente gRPC real
  → proxy → Redis do Dev Services).
- **Testcontainers** disponível para cenários que exijam controle explícito
  (ex.: validar comportamento sob Sentinel/failover).
- **Cobertura:** Jacoco com merge unit + Quarkus já configurado; reporta para Sonar.
- **Análise Sonar local (opcional):** `infra/podman/` sobe um SonarQube local no
  podman e roda `verify` + o scanner (ver o README de lá).
- Cada comando exposto deve ter teste cobrindo: caminho feliz, ausência (nil),
  e erro de tipo (`WRONGTYPE`).
- **Config exclusiva de teste** fica em `src/test/resources/application.properties`
  (mescla-se com o arquivo principal nos testes, com precedência) — ex.: host/porta
  do cliente gRPC de teste. Não precisa do prefixo `%test.` lá.

---

## 10. Convenções de código

- **Reativo (Mutiny) é a prioridade** — de ponta a ponta; **não bloquear o event
  loop**; sem `@Blocking` na rota quente.
- **Virtual threads só quando necessário.** Quando for genuinamente preciso uma
  thread para trabalho de propósito geral (offload bloqueante inevitável, tarefa
  de fundo), preferir **virtual threads** (`@RunOnVirtualThread`) em vez do pool
  de worker threads tradicional. Não é o modelo padrão do caminho quente — é a
  exceção. Nesses pontos, evitar `synchronized` (usar `ReentrantLock`) para não
  causar pinning no Java 21.
- Injeção de campo (CDI) é aceita (regra Sonar `java:S6813` ignorada no pom).
- Sem lógica de negócio no proxy: traduzir, encaminhar, traduzir de volta.
- **`final` sempre que aplicável.** Variáveis locais e argumentos de método
  DEVEM ser declarados `final` onde não houver reatribuição. Reforça imutabilidade
  e intenção. (Campos injetados por CDI são exceção natural — não podem ser `final`.)
- **Comentários didáticos.** Este é também um projeto de aprendizado: enriquecer os
  métodos implementados com comentários explicativos sempre que pertinente —
  priorizar o *porquê* (semântica do Redis, decisões de gRPC/protobuf, escolhas de
  mapeamento) em vez do *o quê* óbvio.
- **Prefira guard clauses a `if/else`.** Sempre que possível, evite `if/else`;
  use early return ("if-return-or-proceed": trate o caso simples/de borda com
  `if (cond) return ...;` e siga adiante no nível principal) para reduzir
  aninhamento. Ex.: `toSetResponse`.
- **Constantes para strings.** Sempre que possível, associar literais de string
  estáticos/repetidos a constantes nomeadas (evita duplicação e previne o Sonar
  `java:S1192`) — inclusive em rótulos de `switch`, que aceitam constantes de
  compilação (`static final String` inicializada a partir de um literal).

---

## 11. Decisões (rastreador)

- [x] Lista final de comandos por família → Núcleo+Recomendado, com *SCAN (seção 5).
- [x] RPC de batch/pipeline → fora do escopo inicial, previsto (seção 5.2).
- [x] Formato/fonte do token → token estático via secret (seção 6).
- [x] Allowlist de comandos → mandatória, é a própria superfície (seção 6).
- [x] Organização do `.proto` → 1 service por família + `common.proto` (seção 5).
- [x] TLS entre app↔Redis → sem TLS interno; borda TLS one-way obrigatório, sem mTLS (seção 6).
- [x] Formato de propagação de erros Redis → gRPC → status gRPC + msg crua (seção 5.1).
- [x] Pacote e esquema de versão do `.proto` → `io.github.claudineyns.redis.grpc.v1`, versão por diretório (seção 5).
- [x] Tipo do cursor / formato das chaves no `*SCAN` → cursor `string` opaco, chaves `string` (seção 5).
- [x] Escopo da SetService na v0.3.0 → `SADD`/`SREM`/`SCARD`/`SISMEMBER`/`SMISMEMBER`/`SMEMBERS` + `SPOP` + `SSCAN`; **adiados para revisão futura:** `SRANDMEMBER`, `SINTER`, `SUNION`, `SDIFF` (seção 5).

> Todas as decisões de arquitetura do escopo v1 estão fechadas.
