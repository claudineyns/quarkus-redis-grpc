# CLAUDE.md — Guia do agente (referência principal)

> Este é o documento **principal** de referência. Ele reúne as **orientações
> gerais ao agente** (premissas de operação e modo de trabalho) e aponta para os
> demais documentos do projeto.
>
> **Arquitetura, design técnico e decisões → [docs/DESIGN.md](docs/DESIGN.md).**

---

## Índice de documentos

| Documento | Conteúdo |
|---|---|
| **CLAUDE.md** (este) | Guia do agente: premissas de operação e modo de trabalho. Referência principal. |
| [docs/DESIGN.md](docs/DESIGN.md) | Arquitetura e design técnico — versão em **inglês** (canônica para leitura externa). |
| [docs/DESIGN.pt-BR.md](docs/DESIGN.pt-BR.md) | Arquitetura e design técnico — versão em **português**. |
| [README.md](README.md) | Visão geral pública do projeto (em **inglês**). |

> **DESIGN bilíngue:** `docs/DESIGN.md` (inglês) e `docs/DESIGN.pt-BR.md`
> (português) são **mantidos em sincronia**. Ver premissa na seção 1.

---

## O projeto em um parágrafo

`redis-grpc` é um **gateway gRPC sobre Redis** — um proxy norte-sul que expõe
comandos Redis como RPCs gRPC (1:1), para que clientes externos alcancem um Redis
interno do cluster através de uma route OpenShift com passthrough. Detalhes de
arquitetura, modelagem e decisões estão em [docs/DESIGN.md](docs/DESIGN.md).

---

## 1. Premissas de operação (ambiente de desenvolvimento)

- **Sempre usar `bash`** para executar comandos neste projeto (não PowerShell/cmd).
- Sempre que houver **referências de caminho Linux**, prefixar o comando com
  `MSYS_NO_PATHCONV=1` para evitar a conversão automática de caminhos do
  Git Bash/MSYS no Windows.

  ```bash
  MSYS_NO_PATHCONV=1 <comando com /caminho/linux>
  ```

- **Build sempre com o `mvn` do sistema** (não usar wrapper `mvnw` nem caçar
  binário alternativo). Ex.: `mvn -B -ntp clean compile`.
- **Comandos `oc` (OpenShift/CRC):** autenticar sempre como usuário `developer`
  e usar a estrutura
  `oc --context ${OC_CXT} -n ${OC_NAMESPACE} <command> [options]` (contexto e
  namespace via variáveis). Aplicar `MSYS_NO_PATHCONV=1` quando houver caminhos
  `/...` literais.
- **Dados temporários em `temp/`.** Usar a pasta `temp/` do projeto para qualquer
  arquivo/dado temporário (não o temp do sistema nem scratchpad). Já está no
  `.gitignore`, então não é versionada.
- **`README.md` sempre em inglês.** O conteúdo do README é mantido exclusivamente
  em inglês. O `CLAUDE.md` permanece em português.
- **DESIGN bilíngue, editado em paralelo (MANDATÓRIO).** A documentação de
  arquitetura existe em dois arquivos espelhados:
  - `docs/DESIGN.md` → **inglês**
  - `docs/DESIGN.pt-BR.md` → **português**

  Toda alteração de design DEVE ser aplicada **aos dois arquivos na mesma
  edição**, cada um no seu idioma, mantendo-os sincronizados em conteúdo,
  estrutura e numeração de seções. Nunca alterar um sem o outro.

---

## 2. Modo de trabalho: discutir antes de implementar (MANDATÓRIO)

- **Toda intenção de implementação DEVE ser submetida à discussão ANTES de ser
  aplicada.** Não escrever/alterar código de produção sem aprovação prévia da
  abordagem. Apresentar trade-offs e uma recomendação; só implementar após o "ok".
- **Aulas didáticas são sob demanda.** Por padrão, NÃO dar aulas/explicações
  conceituais extensas — ir direto à discussão de implementação. O autor solicita
  uma **"aula excepcional"** quando tiver dúvida; aí sim explicar o conceito de
  gRPC/protobuf em profundidade, ancorando em **JSON-RPC** e **SOAP/WSDL** (modelos
  que ele conhece).
- **Escopo do fluxo de discussão:** editar `CLAUDE.md`, `docs/DESIGN.md` /
  `docs/DESIGN.pt-BR.md` e demais arquivos de config/documentação para **registrar
  decisões** é permitido durante a conversa; **código-fonte da aplicação, não** —
  esse passa pelo fluxo de discussão primeiro.
