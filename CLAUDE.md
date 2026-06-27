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

Este projeto também é um **aprendizado de gRPC** para o autor, que **não é
especialista** no protocolo. Portanto:

- **Toda intenção de implementação DEVE ser submetida à discussão ANTES de ser
  aplicada.** Não escrever/alterar código de produção sem aprovação prévia da
  abordagem.
- Explicar o **porquê** das escolhas de gRPC/protobuf em linguagem didática
  (conceitos: HTTP/2, unary vs. streaming, mensagens, `oneof`, status x payload,
  interceptors, geração de stubs, etc.) — não assumir familiaridade.
- Apresentar trade-offs e uma recomendação; só implementar após o "ok".
- **Ancoragem didática:** o autor conhece **JSON-RPC** e **SOAP/WSDL**. Explicar
  gRPC por analogia com esses modelos (ex.: `.proto` ≈ contrato como o WSDL;
  `service`/`rpc` ≈ operações; protobuf ≈ payload binário no lugar de XML/JSON;
  HTTP/2 + streaming como diferencial).
- **Escopo do fluxo de discussão:** editar `CLAUDE.md`, `docs/DESIGN.md` e demais
  arquivos de configuração/documentação para **registrar decisões** é permitido
  durante a conversa; **código-fonte da aplicação, não** — esse passa pelo fluxo
  de discussão primeiro.
