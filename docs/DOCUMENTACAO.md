# HELP-AGENT Orçamentos — Documentação geral

Documento central do projeto: o que o sistema faz, como está construído, como roda, como se mantém e o que falta.
Cada seção resume e aponta para o documento detalhado quando existe. Atualizado em 30/09/2026.

## Índice

1. [Visão geral](#1-visão-geral)
2. [Funcionalidades](#2-funcionalidades)
3. [Arquitetura](#3-arquitetura)
4. [Estrutura do repositório](#4-estrutura-do-repositório)
5. [Banco de dados](#5-banco-de-dados)
6. [Regras de negócio](#6-regras-de-negócio)
7. [Leitura por IA (Gemini)](#7-leitura-por-ia-gemini)
8. [Cotação em lojas online](#8-cotação-em-lojas-online)
9. [API](#9-api)
10. [Configuração](#10-configuração)
11. [Ambientes e como executar](#11-ambientes-e-como-executar)
12. [Qualidade: testes e CI](#12-qualidade-testes-e-ci)
13. [Operação: logs, saúde, métricas e backup](#13-operação-logs-saúde-métricas-e-backup)
14. [Segurança](#14-segurança)
15. [Integração com uma aplicação maior](#15-integração-com-uma-aplicação-maior)
16. [Como trabalhar neste repositório](#16-como-trabalhar-neste-repositório)
17. [Histórico de mudanças](#17-histórico-de-mudanças)
18. [Pendências e decisões em aberto](#18-pendências-e-decisões-em-aberto)
19. [Glossário](#19-glossário)

**Mapa dos documentos**

| Documento | Para quem | Conteúdo |
|---|---|---|
| **este** (`docs/DOCUMENTACAO.md`) | todos | visão geral e índice de tudo |
| [README](../README.md) | quem sobe e usa | subir para o helpdesk, endereço na rede, uso das telas, tabela da API |
| [docs/MANUTENCAO.md](MANUTENCAO.md) | quem mexe no código | fluxo em detalhe, mapa do código, receitas, armadilhas, cotação, homologação |
| [docs/INTEGRACAO.md](INTEGRACAO.md) | quem integra | contrato, rastreio, saúde, métricas, variáveis, contêineres, limitações |
| [docs/ESCOPO-E-PLANO-MIGRACAO.md](ESCOPO-E-PLANO-MIGRACAO.md) | histórico | escopo original e plano de migração do HTML v3.5 |
| [legacy/cotacao-suprimentos/HANDOFF.md](../legacy/cotacao-suprimentos/HANDOFF.md) | histórico | repasse do piloto Python da cotação |
| [CLAUDE.md](../CLAUDE.md) | assistente de código | regras de trabalho e verificação |

---

## 1. Visão geral

O HELP-AGENT gera o **impresso oficial de orçamento** da R Damásio. O atendente do helpdesk anexa o chamado e os
orçamentos de fornecedor (prints, PDFs, texto); a IA lê os dados; o atendente revisa; o sistema preenche o
template PDF da empresa da loja, anexa os documentos originais e guarda tudo num histórico central.

Também **cota o item em lojas online** (Kabum, Pichau, Terabyte, Mercado Livre, Dell, Lenovo) e monta o orçamento
a partir da cotação, com o print da página de cada opção para a validação manual.

- **Usuários:** o helpdesk (uso compartilhado, sem login hoje, na rede interna).
- **Origem:** migração do HTML único `legacy/HELP-AGENT-ORCAMENTO-v3_5.html`, que fazia tudo no navegador (chave
  da IA embutida, histórico só no navegador de cada um). A cotação veio do piloto Python de Suprimentos.
- **Onde roda hoje:** uma máquina Windows da rede do helpdesk, pelo `iniciar-helpagent.bat`, em
  `http://helpagent.rdamasio.com.br` (DNS interno → IP da máquina).

## 2. Funcionalidades

| Funcionalidade | Tela | Resumo |
|---|---|---|
| **Novo orçamento** | `/` | anexar/colar chamado e orçamentos → leitura por IA → revisão ao lado dos documentos → PDF |
| **Tipos de requisição** | seletor na tela inicial | Requisição/Chamado (padrão), OPEX e CAPEX; muda a obrigatoriedade do chamado e a observação do impresso |
| **Preenchimento manual** | `/` | sem IA (fora do ar, desligada ou por escolha) |
| **Histórico** | `/historico` | todos os orçamentos gerados, abas por tipo, busca, download do PDF, exclusão, backup JSON |
| **Lojas** | `/lojas` | cadastro de lojas (número, nome, CNPJ, empresa do impresso, template, razão social, IE, cidade/UF) |
| **Cotação** | `/cotacao` | busca o item em 6 lojas, ranking por critérios ajustáveis, melhor de cada loja, planilha `.xlsx` |
| **Orçamento por cotação** | `/orcamento-cotacao` | os itens escolhidos na cotação viram o impresso, com 3 prints por item e um resumo da cotação |
| **Uso da IA** (técnico) | `/swagger/uso-ia` | cota de cada modelo, gasto do dia, últimas chamadas; fora do menu |
| **Documentação da API** (técnico) | `/swagger-ui.html` | contrato gerado do código |
| **Visual por marca** | seletor no cabeçalho | Damásio Motopeças ou TD Motopeças (só visual; o impresso segue a loja) |
| **Homologação** | `http://localhost:4201` | cópia isolada para testar antes da produção |

Detalhes de uso: [README](../README.md). Detalhes técnicos de cada uma: [MANUTENCAO](MANUTENCAO.md) §1, §7 e §8.

## 3. Arquitetura

```
┌──────────────────────── Navegador ────────────────────────┐
│ Angular 22 (standalone, signals, sem zone.js)             │
│ config.json (onde está a API) · interceptador (id, token) │
└──────────────┬────────────────────────────────────────────┘
               │ /api/...  (proxy do ng serve na porta 80, ou nginx no contêiner)
┌──────────────▼──────────────── Spring Boot 4.1 · Java 21 ─────────────────────────┐
│ extracao  → Gemini (cadeia de modelos, controle de cota)      ──▶ Google Gemini  │
│ orcamento → validação (A1) → cálculo → PDFBox (template + anexos)                │
│ historico · loja · parametros · usoia (cota, métricas, painel)                    │
│ cotacao   → Playwright → Chrome instalado (1 aba por página)  ──▶ lojas online    │
└──────┬───────────────────────────────┬────────────────────────────────────────────┘
       │                               │
  PostgreSQL (H2 no perfil local)   Disco: PDFs gerados, prints, perfil do Chrome
  schema pelo Flyway (V1–V7)        (backend/dados/)
```

**Fluxo principal (orçamento por documentos):** composer → `POST /api/extracoes` (IA lê, servidor confere A2/C2
e acha a loja) → revisão → `POST /api/orcamentos` (servidor valida A1, recalcula, gera o PDF, grava) → download.
Diagrama completo em [MANUTENCAO](MANUTENCAO.md) §1.

**Stack**

| Camada | Tecnologia |
|---|---|
| Backend | Spring Boot 4.1.1, Java 21, Spring MVC, Spring Data JPA, Flyway, Spring Security (OIDC resource server), Actuator |
| PDF | Apache PDFBox 3 (preenche o formulário AcroForm do template e anexa originais) |
| IA | Google Gemini (REST `generateContent`, saída em JSON com esquema fixo) |
| Cotação | Playwright Java 1.63 dirigindo o Chrome instalado; Apache POI (planilha) |
| Contrato/observabilidade | springdoc-openapi 3, Micrometer + Prometheus |
| Banco | PostgreSQL (produção/contêiner); H2 em modo PostgreSQL (perfil local e testes) |
| Frontend | Angular 22, TypeScript, SCSS com o Design System R Damásio (fontes Archivo e JetBrains Mono) |
| Infra | `.bat` no Windows (hoje); Docker + docker-compose + nginx (preparado); GitHub Actions (CI) |

## 4. Estrutura do repositório

```
helpagent_new/
├─ backend/                        Spring Boot
│  ├─ src/main/java/br/com/rdamasio/helpagent/
│  │  ├─ config/                   propriedades, segurança/CORS, OpenAPI, liga/desliga de recursos, /api/parametros
│  │  ├─ common/                   dinheiro, documento, id de requisição, origem (IP), erros (ProblemDetail)
│  │  ├─ extracao/                 leitura por IA: prompt, cliente Gemini, cadeia de modelos, parser, avisos, cache
│  │  ├─ usoia/                    controle de cota, registro de chamadas, métricas, saúde "ia", painel
│  │  ├─ orcamento/                entidades, validação A1, cálculo, geração do impresso
│  │  ├─ pdf/                      montagem do PDF (PDFBox), resumo da cotação, carimbo dos prints
│  │  ├─ template/                 os 4 impressos (TD, DAM, RDAM, CPL) e os campos do formulário
│  │  ├─ loja/                     cadastro e busca de lojas
│  │  ├─ historico/                consulta, download, exclusão, backup/importação
│  │  ├─ armazenamento/            onde ficam os PDFs (interface + disco local)
│  │  └─ cotacao/                  coletor (Chrome), uma Fonte* por loja, motor de ranking, planilha, prints
│  ├─ src/main/resources/
│  │  ├─ application.yml · application-local.yml
│  │  ├─ db/migration/             V1–V7 (Flyway)
│  │  ├─ prompts/extracao.txt      prompt da leitura por IA
│  │  ├─ pdf-templates/            TD.pdf, DAM.pdf, RDAM.pdf, CPL.pdf
│  │  └─ cotacao/                  extrair-<loja>.js (um por loja), preparar-print.js, validar-print.js
│  ├─ src/test/java/               65 testes (+4 manuais/condicionais)
│  ├─ config/                      application-local.yml com a chave do Gemini — FORA do git
│  ├─ dados/                       banco H2, PDFs, prints, logs, perfil do Chrome — FORA do git
│  └─ Dockerfile · pom.xml · mvnw
├─ frontend/                       Angular
│  ├─ src/app/
│  │  ├─ core/                     API, tipos, dinheiro, arquivos, avisos, marca, recursos, configuração, interceptador
│  │  ├─ layout/                   cabeçalho, faixa, etapas, ícones, overlay, toasts
│  │  └─ features/                 novo-orcamento, historico, lojas, cotacao, orcamento-cotacao, uso-ia
│  ├─ src/styles.scss              TODO o visual (tokens); componentes não têm CSS próprio
│  ├─ public/                      logos, config.json
│  ├─ proxy.conf.json · proxy.homologacao.json · angular.json
│  └─ Dockerfile · nginx.conf.template
├─ docs/                           esta documentação, manutenção, integração, escopo
├─ tools/                          extrair_legado.py · avaliar_extracao.py · paridade_cotacao.py
├─ legacy/                         HTML v3.5 original e piloto Python da cotação (só referência)
├─ .github/workflows/ci.yml        CI
├─ docker-compose.yml              PostgreSQL + backend + frontend
└─ iniciar-/parar-helpagent.bat · iniciar-/parar-homologacao.bat
```

Mapa de classe por classe: [MANUTENCAO](MANUTENCAO.md) §2.

## 5. Banco de dados

Schema só por **migration Flyway** (`ddl-auto: validate`), em SQL portável (PostgreSQL e H2).

| Tabela | Conteúdo |
|---|---|
| `loja` | número (chave de negócio, fixo), nome, CNPJ, empresa impressa, template, ativa, razão social, IE, cidade, UF |
| `orcamento` | tipo (`REQUISICAO`/`OPEX`/`CAPEX`), origem (`DOCUMENTOS`/`COTACAO`), loja, título, datas, chamado, totais, observação, responsáveis, PDF, quem/quando |
| `orcamento_item` | linhas do impresso; nas vindas da cotação, também loja fornecedora, URL e momento do print |
| `uso_ia` | uma linha por requisição ao Gemini: modelo, degrau, papel, resultado, tokens, tempo, IP (90 dias) |

| Migration | O quê |
|---|---|
| V1 | schema inicial (`loja`, `orcamento`, `orcamento_item`) |
| V2 | lojas do HTML v3.5 (gerado por `tools/extrair_legado.py`) |
| V3 | "válido até" do orçamento |
| V4 | três tipos de requisição (o antigo OPEX virou `REQUISICAO`) |
| V5 | orçamento por cotação (origem e dados da loja fornecedora por linha) |
| V6 | cadastro completo das lojas (planilha do grupo, 54 lojas; 14 novas) |
| V7 | tabela `uso_ia` |

Loja não se apaga (só desativa): os orçamentos antigos apontam para ela. Migration aplicada não se edita.

## 6. Regras de negócio

Valem **no servidor**; o frontend só mostra.

| Código | Regra |
|---|---|
| **A1** | não gera impresso cujo total não fecha com as linhas (linha sem produto, produto sem valor, nenhum item) |
| **A2** | o impresso tem 10 linhas; se vier mais, avisa quantas ficaram de fora |
| **A4** | data de emissão na data local, não UTC |
| **C2** | total lido ≠ soma das linhas (mais de R$ 0,05) → aviso de conferência |
| — | cada orçamento anexado vira **um** item consolidado (qtd 1, valor = total dele) |
| — | preço de e-commerce: à vista/PIX antes do preço regular; nunca a parcela |
| — | chamado obrigatório em Requisição e OPEX; opcional em CAPEX (e não vai para a IA) |
| — | chamado já orçado → aviso com link para o PDF anterior |
| — | "válido até": a IA só lê o que está escrito; o servidor calcula e recusa validade anterior à emissão |
| — | orçamento por cotação só é gerado com todos os prints com imagem |
| — | leitura feita por modelo reserva → aviso na revisão para conferir valores |

Detalhes e onde cada uma está: [MANUTENCAO](MANUTENCAO.md) §1.

## 7. Leitura por IA (Gemini)

- **Chave** só no servidor (`backend/config/application-local.yml` ou `GEMINI_API_KEY`). Nunca no frontend.
- **Saída estruturada:** a API devolve JSON num esquema fixo (os campos de `DadosExtraidos`).
- **Cadeia de modelos** (`helpagent.gemini.cadeia`), do melhor para o pior:
  `gemini-3.6-flash → 3.5-flash → 3.5-flash-lite → 3.1-flash-lite → 2.5-flash`. Cota esgotada, sobrecarga ou modelo
  inexistente descem um degrau; quando a pausa acaba, as leituras voltam ao de cima sozinhas.
- **Controle de cota:** o servidor conta requisições e tokens por minuto e requisições por dia (o dia do Google
  vira à meia-noite do Pacífico) e desce **antes** de levar o 429. Limites no `application.yml`; só o 20/dia do
  3.6-flash é confirmado pelo Google, os demais são estimativa até conferir no AI Studio.
- **Anti-rajada:** até 3 leituras simultâneas, até 4 requisições por leitura, leitura igual em andamento é
  reaproveitada; mesmos arquivos em até 30 min vêm do cache sem gastar cota.
- **Consumo medido:** cerca de **2.500 tokens por leitura** de 1 arquivo (a maior parte é o prompt), ~4.900 com 3.
- **Tempo:** 5–10 s normalmente; um modelo lento por mais de 12 s dispara o de baixo em paralelo; prazo total 60 s.
- **Onde acompanhar:** `/swagger/uso-ia`, `GET /api/uso-ia`, métricas `helpagent_ia_*`, saúde `ia`.

Detalhes, medições e como avaliar precisão (`tools/avaliar_extracao.py`): [MANUTENCAO](MANUTENCAO.md) §4.

## 8. Cotação em lojas online

| Nível | Lojas |
|---|---|
| 1 · Varejo de TI (padrão) | Kabum, Pichau, Terabyte |
| 2 · + Marketplace | + Mercado Livre |
| 3 · + Fabricantes | + Dell, Lenovo |

- O servidor dirige o **Chrome instalado** (Playwright), com janela fora da tela e perfil persistente: o Mercado
  Livre bloqueia navegador headless. Precisa de **usuário logado** na máquina (não roda como serviço do Windows).
- Uma aba por página de loja, todas disparadas antes de ler; uma cotação por vez no servidor.
- Cada loja tem uma classe `Fonte*` e um script `extrair-<loja>.js`; esgotado/indisponível nunca entra no ranking.
- Ranking: eliminatórios em ordem fixa → score ponderado (preço, entrega, fornecedor, marca) → grupos. Mudar
  critério reordena na hora, sem nova busca. Resultado guardado 30 min (para reavaliar e gerar a planilha).
- **Orçamento por cotação:** "Escolher para o orçamento" fotografa a escolhida e 2 alternativas em segundo plano,
  com loja, hora e link carimbados na imagem; o PDF leva impresso, resumo da cotação e prints.
- A Amazon foi removida em 30/09/2026. Tempo típico: nível 1 ~20 s; a lentidão está investigada (§18).

Detalhes (seletores por loja, decisões, diagnóstico, como acrescentar loja): [MANUTENCAO](MANUTENCAO.md) §7 e §8.

## 9. API

Tudo sob `/api`. Contrato completo e sempre atual em **`/v3/api-docs`** e **`/swagger-ui.html`**.

| Grupo | Rotas principais |
|---|---|
| Lojas | `GET/POST /api/lojas`, `GET/PUT /api/lojas/{numero}`, `PATCH /api/lojas/{numero}/ativa` |
| Leitura por IA | `POST /api/extracoes` (multipart) |
| Orçamento | `POST /api/orcamentos` (multipart: dados + arquivos) → PDF |
| Histórico | `GET /api/historico`, `/contagem`, `/por-chamado`, `/{id}/pdf`, `DELETE /{id}`, `/exportar`, `/importar` |
| Cotação | `GET /api/cotacao/estado`, `POST /api/cotacao`, `/{id}/reavaliar`, `/{id}/planilha`, `/{id}/prints`, `/prints…` |
| Parâmetros | `GET /api/parametros` (padrões do formulário, ambiente, recursos ligados) |
| Uso da IA | `GET /api/uso-ia` |

- Erros sempre em **ProblemDetail** (RFC 9457) com `problemas` (lista para a tela) e `idRequisicao`.
- Cabeçalho **`X-Request-Id`**: segue a chamada nos logs; em erro 5xx a tela mostra "Código para o suporte".
- Recurso desligado → **503** com explicação. Tabela completa: [README](../README.md) → API.

## 10. Configuração

Padrões em `backend/src/main/resources/application.yml`; tudo que muda por ambiente vem de variável.

| Variável | Padrão | Para quê |
|---|---|---|
| `PORT` | 8080 | porta do backend |
| `DB_URL` · `DB_USER` · `DB_PASSWORD` | PostgreSQL local | banco (o perfil `local` usa H2 em `backend/dados/`) |
| `GEMINI_API_KEY` | — | chave da IA |
| `GEMINI_MODELO_FORCADO` | — | um modelo só, sem cadeia (comparar modelos) |
| `SEGURANCA_HABILITADA` | `true` | exige JWT (o perfil `local` desliga) |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | — | provedor OIDC, com a segurança ligada |
| `CORS_ORIGENS` | `http://localhost:4200` | frontend servido de outro domínio |
| `RECURSO_IA` · `RECURSO_COTACAO` | `true` | liga/desliga cada recurso |
| `ARMAZENAMENTO_DIR` | `./dados/arquivos` | PDFs gerados |
| `COTACAO_PRINTS` · `COTACAO_PERFIL` · `COTACAO_CANAL` | `./dados/prints` · `./dados/navegador` · `chrome` | cotação |
| `ACTUATOR_EXPOR` · `ACTUATOR_DETALHES` | `health,info` · `never` | Actuator |
| `API_DOCS` | `true` | contrato OpenAPI e Swagger |
| `HELPAGENT_AMBIENTE` | — | `homologacao` mostra a faixa na tela |

**Frontend:** `frontend/public/config.json` (`apiBase`, `enviarCookies`) e, na página hospedeira,
`window.helpAgent.token` — ver [INTEGRACAO](INTEGRACAO.md) §6.

## 11. Ambientes e como executar

| Ambiente | Como | Endereço | Dados |
|---|---|---|---|
| **Produção (hoje)** | `iniciar-helpagent.bat` / `parar-helpagent.bat` | `http://helpagent.rdamasio.com.br` (porta 80) | `backend/dados/` (H2) |
| **Homologação** | pasta da branch `homologacao` (git worktree) → `iniciar-homologacao.bat` | `http://localhost:4201` | `backend/dados-homologacao/` |
| **Desenvolvimento** | backend `mvnw spring-boot:run -Dspring-boot.run.profiles=local`; frontend `npm start` | `http://localhost:4200` | `backend/dados/` |
| **Contêineres** | `GEMINI_API_KEY=... docker compose up --build` | `http://localhost:8081` | volumes; PostgreSQL; cotação desligada |

- O backend escuta só em `127.0.0.1`; a rede entra pelo proxy do frontend, que encaminha `/api` e o Swagger.
- Fluxo de mudança: desenvolver na `homologacao` → testar na 4201 → juntar na `main` → reiniciar a produção.
- **Particularidades da máquina atual:** a JVM precisa de `-Djdk.net.unixdomain.tmpdir=<pasta sem ~>` (os `.bat`
  já passam); `npm install` só pelo espelho `--registry=https://registry.yarnpkg.com`; `&` quebra argumentos do
  `mvnw.cmd`; PowerShell 5 grava arquivo com BOM (quebra o `.java`). Lista completa: [MANUTENCAO](MANUTENCAO.md) §5.

Passo a passo: [README](../README.md); homologação: [MANUTENCAO](MANUTENCAO.md) §9.

## 12. Qualidade: testes e CI

```bash
cd backend && ./mvnw -q compile
```

```bash
cd backend && ./mvnw -q test
```

```bash
cd frontend && npx ng build
```

- **65 testes** no backend, entre eles:
  - política da cadeia de modelos e controle de cota (`ExtratorIaTest`, `ControleCotaIaTest`);
  - paridade do ranking com o piloto Python (`MotorCotacaoParidadeTest`);
  - PDF e planilha;
  - a aplicação inteira subindo (`AplicacaoSobeTest`) e os recursos desligados (`RecursosDesligadosTest`).
- **Condicionais/manuais:** `MigracoesPostgresTest` (só com `TESTE_POSTGRES_URL`, roda no CI), coleta real nas
  lojas, exploração de loja nova e diagnóstico de tempo da cotação (ligados por `-Dcotacao.*`).
- **CI** (`.github/workflows/ci.yml`), a cada push na `main`/`homologacao`:
  - testes do backend, com as migrations num **PostgreSQL de verdade**;
  - build do frontend;
  - build das duas imagens Docker.
- **Sem testes de frontend** ainda (pendência).

## 13. Operação: logs, saúde, métricas e backup

- **Log:** `backend/dados/logs/helpagent.log` (gira em 10 MB, 14 dias). Toda linha leva `[req=<id>]`. Auditoria
  sem login: alterações de loja com antes/depois e IP, cada cotação com termo/lojas/IP, cada leitura com modelo,
  degrau, tokens e de onde a IA tirou cada valor.
- **Saúde:** `/actuator/health` (banco, disco, `ia`), sondas `/liveness` e `/readiness`, versão em `/actuator/info`.
- **Métricas:** `/actuator/prometheus` quando exposto (`helpagent_ia_requisicoes_total`, `_tokens_total`,
  `_duracao_seconds`, `_cota_dia_usada`/`_limite`).
- **Uso da IA:** `/swagger/uso-ia`.
- **Backup:** Histórico → *Exportar JSON* (orçamentos, itens e PDFs); *Importar JSON* aceita também o backup do
  HTML v3.5. Fazer antes de atualizar a versão ou trocar de banco.
- **Diagnóstico de lentidão:** IA em [MANUTENCAO](MANUTENCAO.md) §4; cotação em §7 ("Diagnóstico").

## 14. Segurança

- **Hoje:** perfil `local`, API **sem login**, aceitável só na rede segmentada do helpdesk; o backend não fica
  exposto (só `127.0.0.1`). Toda alteração vai para o log com o IP.
- **Preparado:** com `SEGURANCA_HABILITADA=true`, `/api/**` exige JWT de um provedor OIDC; o frontend manda o token
  que o portal fornecer. Saúde, versão e contrato ficam abertos; métricas só se expostas, em rede interna.
- **Segredos:** nunca em arquivo versionado (`backend/config/` e `.env` no `.gitignore`); a imagem Docker não leva
  `config/` nem `dados/`.
- **Cotação:** o servidor só fotografa URL que veio da própria cotação (o Chrome do servidor não abre endereço
  arbitrário da rede interna).
- **Atenção:** a chave do Gemini que estava no HTML v3.5 precisa ser **revogada** (§18).

## 15. Integração com uma aplicação maior

Preparado sem depender de como vai ser (serviço próprio, módulo, portal):
- contrato OpenAPI;
- `X-Request-Id` ponta a ponta;
- saúde, sondas e métricas;
- liga/desliga por recurso;
- frontend configurável na hora de abrir (`config.json`, token do portal, sub-caminho);
- Docker e CI.

**O que ainda prende a uma instância só:**
- PDFs e prints em disco local;
- cota da IA e cache em memória;
- fila da cotação.

Tudo em [INTEGRACAO](INTEGRACAO.md).

## 16. Como trabalhar neste repositório

- **Antes de mudar código:** ler [MANUTENCAO](MANUTENCAO.md) (receitas e armadilhas).
- **Documentar sempre:** todo arquivo novo começa com um comentário dizendo seu papel; comentários explicam o
  *porquê*; mudou comportamento → atualizar MANUTENCAO/README (e este documento, se mudar a visão geral).
- **Português** em nomes, mensagens e comentários.
- **Regras no servidor**; o frontend só mostra.
- **Schema** só por migration nova e portável; **visual** só por tokens em `styles.scss`.
- **Versionar a cada ajuste:** um commit por mudança (mensagem em português com o porquê), sem segredo no diff,
  `git fetch` antes, push para `https://github.com/jesusgomesea/helpagent` e CI verde.

## 17. Histórico de mudanças

| Data | Mudança | Commit |
|---|---|---|
| 25/09/2026 | medições da IA e avaliação com orçamentos reais (antes do repositório) | — |
| 28/09/2026 | sistema em Java/Angular + cotação em lojas online (piloto portado, 7 lojas, visual do DS) | `4c40c32` |
| 28/09/2026 | orçamento por cotação com prints + ambiente de homologação | `639387f` |
| 28/09/2026 | conferência do print (esconde aviso de cookies, confere o preço) | `a07c7cf` |
| 29/09/2026 | esgotado/indisponível nunca entra no ranking | `899c7b2` |
| 29/09/2026 | cadeia de modelos com controle de cota; cadastro completo das lojas | `8b7978e` |
| 29/09/2026 | integração: OpenAPI, id de requisição, saúde e métricas | `eca6bfe` |
| 29/09/2026 | integração: liga/desliga por recurso | `4ef3064` |
| 29/09/2026 | integração: frontend embutível | `68d26d4` |
| 29/09/2026 | integração: Docker, docker-compose e CI | `cb92345` |
| 29/09/2026 | integração: limitações e pendências documentadas | `cb8e969` |
| 30/09/2026 | Uso da IA sai do menu: painel técnico em `/swagger/uso-ia` | `47aa9ac` |
| 30/09/2026 | Amazon removida da cotação | `e0d69cd` |

Detalhe de cada uma: `git log`.

## 18. Pendências e decisões em aberto

**Segurança e produção**
- Revogar a chave do Gemini que estava no HTML v3.5 (provável origem de uso fora deste servidor).
- Escolher o provedor de login (Entra ID, Keycloak...) e separar papéis (atendente × administrador) nas rotas de
  escrita de lojas e na exclusão do histórico.
- Plano pago do Gemini e limites reais na cadeia (copiar do painel do AI Studio).

**Qualidade**
- Lentidão da cotação: investigação feita ([MANUTENCAO](MANUTENCAO.md) §7), correção aguardando aprovação.
- Avaliar a precisão do 3.1-flash-lite e do 2.5-flash com orçamentos reais.
- Testes de frontend e de ponta a ponta.
- Conferir os prints de Mercado Livre, Dell e Lenovo no orçamento por cotação.

**Negócio**
- Suprimentos validar a regra "aceitar sem nota quando a própria loja vende" (nota presumida 4,5).
- Conferir empresa/impresso das 14 lojas novas; o 806 veio com o mesmo CNPJ do 804.
- Registro DNS interno `helpagent.rdamasio.com.br` (TI).

**Integração**
- Como o backend e o frontend entram na aplicação maior (decidido depois).
- Tirar da memória/disco local o que impede várias instâncias.
- Prefixo `/api/v1` antes de outros sistemas consumirem a API.

## 19. Glossário

| Termo | Significado |
|---|---|
| **Impresso** | o PDF oficial de orçamento da empresa (template AcroForm), preenchido pelo sistema |
| **Template** | um dos 4 impressos: TD (TD Motopeças), DAM (Damásio), RDAM (R Damásio), CPL (CPL Motoparts) |
| **Empresa** | código que sai no impresso (ex.: `DAMASIO-PE`), por loja |
| **Requisição / OPEX / CAPEX** | tipos de requisição; mudam a obrigatoriedade do chamado e a observação |
| **Composer** | área de entrada: colar, anexar ou digitar chamado e orçamentos |
| **Cadeia / degrau** | a lista ordenada de modelos da IA e a posição de um modelo nela |
| **RPM · TPM · RPD** | requisições por minuto, tokens por minuto, requisições por dia (limites do Google) |
| **Nível de busca** | conjunto de lojas da cotação (1 varejo de TI, 2 + marketplace, 3 + fabricantes) |
| **Fonte** | uma loja da cotação (classe `Fonte*` + script `extrair-<loja>.js`) |
| **Eliminatório** | critério da cotação que descarta o anúncio antes do score |
| **Cesta** | itens escolhidos na cotação para o orçamento por cotação |
| **Homologação** | cópia isolada (branch, portas e dados próprios) para testar antes da produção |
| **Perfil `local`** | H2 em arquivo e API sem login; o modo em que o sistema roda hoje |
