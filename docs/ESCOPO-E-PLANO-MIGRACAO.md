# HELP-AGENT Orçamentos — Escopo e Plano de Migração (HTML → Java + Angular)

> Fonte analisada: `legacy/HELP-AGENT-ORCAMENTO-v3_5.html` (arquivo único, ~1,2 MB, 3.132 linhas)
> Data da análise: 2026-09-25

---

## 1. Visão geral

Ferramenta interna da **R Damásio** (área de TI) que monta o **impresso de orçamento** oficial da empresa a partir de prints/PDFs de **chamados internos** e de **orçamentos de fornecedores**. Uma IA (Google Gemini) lê os documentos e extrai os dados; o usuário revisa; o sistema preenche o **template PDF da empresa correta** (formulário AcroForm) e anexa ao final os documentos originais, gerando um único PDF para download.

### Composição do arquivo atual

| Bloco | Linhas | Conteúdo |
|---|---|---|
| `<head>` | 1–9 | `pdf-lib@1.17.1` via unpkg, Google Fonts (Inter, IBM Plex Mono, Playfair Display) |
| CSS | 10–1459 | ~1.450 linhas; temas via `data-theme`, overlay animado, cards, chips, modal, toasts |
| HTML | 1461–1712 | Header com stepper, modal de histórico, 3 cards (Upload, Revisão, Gerar PDF) |
| Dados | 1717–1723 | `CADLOJAS` (41 lojas) e `TEMPLATES_B64` (4 PDFs em base64, ~1,09 MB — ~90% do arquivo) |
| JS | 1724–3131 | ~1.400 linhas de lógica, tudo em escopo global |

---

## 2. Escopo funcional (o que o sistema faz hoje)

### 2.1 Fluxo principal — stepper de 4 etapas
`1 IMAGENS → 2 EXTRAÇÃO → 3 REVISÃO → 4 PDF`

### 2.2 Modo de aquisição (OPEX × CAPEX)
- **OPEX** (padrão): exige **≥1 chamado + ≥1 orçamento**. Observação começa com `# <nº chamado>.`
- **CAPEX**: aquisição sem chamado; exige só **≥1 orçamento**. Observação começa com `CAPEX.`; loja é informada manualmente. O destino "Chamado" some do composer.

### 2.3 Entrada de documentos ("composer")
- Colar imagem com **Ctrl+V** (ex.: recorte Win+Shift+S) direto na caixa.
- Anexar arquivo (📎) — imagem ou PDF.
- **Digitar/colar texto**: o texto é rasterizado em PNG via `<canvas>` para manter um pipeline único por imagem.
- Seletor de destino: **Chamado** ou **Orçamento**; **Ctrl+Enter** adiciona.
- Suporta **N chamados** (mesmo CNPJ/compra) e **N orçamentos**; cada anexo vira um "chip" removível.

### 2.4 Extração com IA (Google Gemini)
- Envia todos os documentos (base64 inline) + prompt em português, pedindo **JSON estrito**:
  `chamado_num, loja_num, loja_nome, titulo, itens[{produto, descricao, qtd, valor_unit, valor_total}], total, observacao`.
- **Regra de negócio central:** cada orçamento anexado = **1 item consolidado** (qtd 1, valor = total do orçamento).
- **Hierarquia de preços para e-commerce**: à vista/PIX > preço regular; ignorar parcelas e preços riscados.
- Resiliência:
  - Modelo primário `gemini-3.6-flash`, fallback `gemini-3.5-flash-lite` quando a **quota diária** estoura (429 + "quota/PerDay").
  - Até **3 tentativas** com backoff linear (1,2 s × n) para status transitórios (408, 425, 429, 5xx) e JSON inválido.
  - Degradação: se a API recusar `thinkingConfig`/`responseMimeType` (400), repete com config mínima.
  - Não repete 400/401/403 definitivos; mensagem específica para 404 de modelo.
- Overlay "Construindo…" com status das tentativas.

### 2.5 Revisão dos dados
- **Loja**: busca por número (normaliza zeros à esquerda: `023` = `23`) ou por nome (datalist, *contains*). Preenche **Empresa** e **CNPJ** (somente leitura) e mostra badge do template.
- **Cabeçalho**: título, data de emissão (data **local**, não UTC — correção A4).
- **Itens**: até **10 linhas** (limite físico do template). Qtd × unitário = total da linha.
- **Totais**: subtotal (livre), frete, acréscimos, total geral = Σ itens + frete + acréscimos.
- **Responsáveis**: requerente (default `ANTONIO FELIPE`) e gestor (default `GILSON PASSOS`).
- **Observações**: texto livre (pré-preenchido pela IA).
- **Avisos pós-extração**:
  - A2 — IA trouxe mais de 10 itens → informa quantos foram descartados.
  - C2 — total lido pela IA diverge da soma dos itens (> R$ 0,05) → alerta.

### 2.6 Geração do PDF
- Validações bloqueantes: loja selecionada, título preenchido e **A1** (linha com valor sem produto, produto sem valor, nenhum item).
- Carrega o template da loja (`TD`, `DAM`, `RDAM`, `CPL`) e preenche os campos AcroForm (`FIELD_MAP` + `ITEM_ROWS` 10×6).
- Valores fixos no impresso: **Depto = TECNOLOGIA**, **Variação = 6%**; data por extenso (dia / mês por extenso / ano).
- Anexa, nesta ordem: **formulário → chamado(s) → orçamento(s)**. PDFs têm as páginas copiadas; imagens vão centralizadas em A4; carimbo âmbar no topo com rótulo (`Chamado 1021069`, `Orçamento 1/3`) e timestamp. Imagens não JPG/PNG são convertidas via canvas. Falha ao anexar gera página de erro em vez de abortar.
- Nome do arquivo: `Orçamento <nº chamado>.pdf` ou `Orçamento <título>.pdf`.
- "Novo orçamento" = `location.reload()`.

### 2.7 Histórico (IndexedDB local do navegador)
- Salva cada PDF gerado (metadados + bytes), máx. **100** registros (remove os mais antigos).
- Modal com busca (título, loja, nº loja, chamado, empresa), re-download, excluir item, limpar tudo.
- Exportar/importar JSON (PDF em base64); importação **adiciona** registros.

### 2.8 Dados mestres embutidos
- **41 lojas** (`n, nome, cnpj, empresa, template`): TD 19 · DAM 17 · CPL 3 · RDAM 2.
- **4 templates PDF** com formulário AcroForm (nomes de campo `Caixa de texto …`).

### 2.9 Visual
- Temas (`dark-amber`, `dark-blue`, `minimal`, `light`) existem no CSS/JS, mas **os botões estão desativados na prática**: um IIFE remove o tema salvo e força o visual "Gemini". Responsivo (breakpoint 720 px), toasts, overlay.

---

## 3. Problemas e riscos encontrados

| # | Severidade | Problema | Tratamento na migração |
|---|---|---|---|
| R1 | **Crítico** | **Chave da API Gemini em texto puro no HTML** (`GEMINI_API_KEY`, linha ~1808). Qualquer pessoa com o arquivo usa a cota/billing. | **Revogar e gerar nova chave já**, independente da migração. No novo sistema a chave fica só no backend (variável de ambiente / cofre). |
| R2 | Alto | Sem autenticação nem trilha de auditoria — qualquer um com o arquivo gera impressos oficiais. | Login corporativo + registro de quem gerou cada orçamento. |
| R3 | Alto | Histórico só no navegador (IndexedDB): perdido ao trocar de máquina/limpar dados; não compartilhado entre a equipe. | Histórico centralizado em banco + armazenamento de arquivos. |
| R4 | Médio | Lojas, CNPJs e templates **hardcoded**; qualquer mudança exige editar e redistribuir o HTML. | Cadastros administráveis (CRUD de lojas e upload de templates). |
| R5 | Médio | Responsáveis, depto e variação 6% fixos no código. | Parâmetros configuráveis. |
| R6 | Médio | Nomes de campos do PDF acoplados (`Caixa de texto 4_38`…) e iguais para os 4 templates. | Mapeamento por template, validado na subida do template. |
| R7 | Baixo | HTML malformado: `<button id="btnNovo">` sem `</button>` e `card-body` sem fechar (linhas ~1706–1710). | Some na reescrita. |
| R8 | Baixo | Código morto: seletor de temas neutralizado, `imagens` "retrocompat", `validade` e `subtotal` sem regra. | Decidir se entram ou não (ver §6). |
| R9 | Baixo | `innerHTML` com dados em `renderHistorico` (título/loja sem escape) e em `setStatus`. | Angular escapa por padrão. |
| R10 | Baixo | `exportarHistorico` usa `String.fromCharCode(...bytes)` — estoura a pilha com PDFs grandes. | Exportação feita no backend. |
| R11 | Baixo | Dependência de CDN (unpkg) e prompt duplicado (OPEX/CAPEX quase idênticos). | Lib local / backend; prompt em template único parametrizado. |

---

## 4. Arquitetura alvo

```
┌──────────────────────── Angular (SPA) ────────────────────────┐
│  Composer (colar/anexar/texto) · Revisão (Reactive Forms)     │
│  Histórico · Admin (lojas, templates, parâmetros)             │
└──────────────┬────────────────────────────────────────────────┘
               │ HTTPS / JSON + multipart   (token OIDC)
┌──────────────▼──────────────── Spring Boot ───────────────────┐
│  /api/extracoes   → GeminiClient (retry, fallback, prompt)    │
│  /api/orcamentos  → PdfService (PDFBox: AcroForm + anexos)    │
│  /api/lojas, /api/templates, /api/parametros (admin)          │
│  /api/historico   → consulta, download, exportação            │
└──────┬───────────────────────┬────────────────────────────────┘
       │                       │
  PostgreSQL              Armazenamento de arquivos
  (lojas, orçamentos,     (templates, anexos, PDFs gerados:
   itens, auditoria)       disco/S3/MinIO/Blob)
```

### Stack sugerida

| Camada | Escolha | Observação |
|---|---|---|
| Backend | **Java 21 + Spring Boot 3.x** (Web, Validation, Data JPA, Security/OAuth2 Resource Server, Actuator) | |
| PDF | **Apache PDFBox 3.x** | Preenche AcroForm, copia páginas, embute imagens, desenha carimbo — equivalente direto ao `pdf-lib`. |
| IA | Cliente HTTP (`RestClient`) para a API Gemini + **Resilience4j** (retry, fallback) | Porta 1:1 da lógica de retry/fallback atual. Chave via env/secret. |
| Banco | **PostgreSQL** + **Flyway** | |
| Frontend | **Angular** (versão LTS vigente, standalone components, signals, Reactive Forms) | Angular Material ou PrimeNG para UI. |
| Auth | OIDC com o provedor corporativo (Entra ID/AD, Keycloak…) | A confirmar (§6). |
| Build/Infra | Maven, Docker, docker-compose p/ dev; CI com testes | |

### Decisão-chave: onde gerar o PDF
**Recomendado: no backend (PDFBox).** Motivos: templates saem do cliente (o bundle cai de 1,2 MB para o tamanho normal de uma SPA), o PDF gerado já nasce no histórico central, e há um único lugar para validar A1. A conversão texto→imagem também migra para o backend (ou é eliminada: texto pode ir para a IA como `text` part, sem rasterizar).

---

## 5. Modelo de dados (proposta)

```
loja            (id, numero, nome, cnpj, empresa, template_codigo, ativa)
template_pdf    (codigo [TD|DAM|RDAM|CPL], rotulo, arquivo_ref, mapa_campos jsonb, max_itens, versao)
parametro       (chave, valor)   -- depto, variacao, requerente_padrao, gestor_padrao, modelos Gemini
orcamento       (id, modo [OPEX|CAPEX], loja_id, titulo, data_emissao, chamado_num,
                 subtotal, frete, acrescimos, total, observacoes, requerente, gestor,
                 status [RASCUNHO|GERADO], pdf_ref, criado_por, criado_em)
orcamento_item  (id, orcamento_id, ordem, produto, descricao, qtd, valor_unit, valor_total)
anexo           (id, orcamento_id, papel [CHAMADO|ORCAMENTO], ordem, mime, arquivo_ref)
extracao_ia     (id, orcamento_id, modelo, tentativas, json_bruto, avisos, duracao_ms, criado_em)
```

Valores monetários em `NUMERIC(14,2)` / `BigDecimal` — nunca `double` (o HTML usa `parseFloat`).

---

## 6. Decisões em aberto (validar com o negócio antes da Fase 2)

1. **Autenticação**: qual provedor corporativo? Quem pode administrar lojas/templates?
2. **Histórico existente**: migrar os registros do IndexedDB de cada usuário? (Sugestão: tela de importação do JSON exportado pela v3.5.)
3. **Campo "validade"** existe no template (`Caixa de texto 1_6`) mas nunca é preenchido — deve entrar?
4. **Subtotal**: hoje é livre e não entra em nenhum cálculo. Regra desejada?
5. **Temas**: manter o seletor de 4 temas ou só o visual atual?
6. **Retenção** de documentos e PDFs (LGPD — chamados podem ter nome de colaborador).
7. **Hospedagem**: on-premise ou nuvem? Isso define o armazenamento de arquivos.
8. **Limite de 10 itens**: manter (restrição do impresso) ou gerar páginas de continuação?

---

## 7. Plano de ação

### Fase 0 — Contenção imediata (antes de tudo) · ~0,5 dia
- [ ] **Revogar a chave Gemini exposta** e emitir uma nova (restrita à API usada).
- [ ] Recolher cópias do HTML com a chave antiga; distribuir versão sem a chave embutida até a migração.
- [ ] Extrair do HTML os 4 templates PDF (base64 → `.pdf`) e o `CADLOJAS` (→ JSON/CSV) como artefatos versionados.

### Fase 1 — Descoberta e fundação · ~1 semana
- [ ] Responder as decisões do §6.
- [ ] Criar repositório (monorepo `backend/` + `frontend/` ou dois repos) com README, `.editorconfig`, CI.
- [ ] Scaffold Spring Boot (Maven, perfis dev/prod, Actuator, Flyway, docker-compose com PostgreSQL).
- [ ] Scaffold Angular (standalone, roteamento, lib de UI, ESLint, proxy para o backend).
- [ ] Inventariar os campos AcroForm de cada template com PDFBox e confirmar o `FIELD_MAP`/`ITEM_ROWS` por template.
- [ ] Montar **suíte de casos de referência**: 5–10 orçamentos reais já gerados pela v3.5 (entradas + PDF de saída) para comparação.

### Fase 2 — Backend: domínio e cadastros · ~1 semana
- [ ] Migrations Flyway do modelo do §5; seed das 41 lojas e dos 4 templates.
- [ ] Entidades, repositórios e serviços; utilitário de **dinheiro BRL** (`BigDecimal`, parse/format pt-BR) com testes — porta de `parseBRL`/`fmtBRL`.
- [ ] Busca de loja: por número normalizado (`023` = `23`) e por nome (contains, case-insensitive).
- [ ] CRUD admin de lojas, templates (upload + validação do mapa de campos) e parâmetros.
- [ ] Segurança: OAuth2 Resource Server, perfis `USUARIO` / `ADMIN`.

### Fase 3 — Backend: extração com IA · ~1 semana
- [ ] `GeminiClient` com `RestClient`, chave via `@ConfigurationProperties` + env/secret.
- [ ] Porta da resiliência: 3 tentativas, backoff, status retentáveis, fallback de modelo por quota diária, degradação de `generationConfig`, mensagem de 404 de modelo, modelo forçado por config.
- [ ] Prompt como **template único** (OPEX/CAPEX por parâmetro) em arquivo de recurso, versionado.
- [ ] Parse robusto do JSON (remover cercas ```` ``` ````, extrair `{…}`), DTO validado.
- [ ] Regras pós-extração: limite de itens (A2) e conferência de total (C2) devolvidas como `avisos[]`.
- [ ] Endpoint `POST /api/extracoes` (multipart: chamados[], orcamentos[], modo, textos[]). Texto enviado como `text` part (sem rasterizar), ou rasterizado no backend se a qualidade cair.
- [ ] Registrar `extracao_ia` (modelo usado, tentativas, duração) para observabilidade de custo/qualidade.
- [ ] Testes com WireMock simulando 429/503/400/JSON quebrado.

### Fase 4 — Backend: geração do PDF · ~1 semana
- [ ] `PdfService` (PDFBox): carregar template, preencher AcroForm (campos fixos, itens, totais, data por extenso pt-BR, responsáveis, depto, variação).
- [ ] Validações bloqueantes A1 + loja + título no servidor (`@Valid` + regra de domínio).
- [ ] Anexos na ordem formulário → chamados → orçamentos; imagens em A4 com escala proporcional; conversão de webp/gif/heic; carimbo âmbar com rótulo e timestamp; página de erro em falha de anexo.
- [ ] Nome do arquivo (`Orçamento <chamado>.pdf` / título) e persistência do PDF + registro no histórico.
- [ ] **Teste de regressão**: gerar os casos de referência da Fase 1 e comparar campos preenchidos com os PDFs da v3.5.

### Fase 5 — Frontend Angular · ~2 semanas
- [ ] Layout/shell: header com stepper (4 etapas), toggle OPEX/CAPEX, botão Histórico, toasts, overlay de processamento.
- [ ] **Composer**: paste de imagem (Ctrl+V), anexar arquivo, texto, destino Chamado/Orçamento, Ctrl+Enter, chips removíveis, habilitação do botão conforme o modo.
- [ ] **Revisão** com Reactive Forms: autocomplete de loja (número e nome), empresa/CNPJ read-only, `FormArray` de itens (máx. configurável), cálculo reativo de totais, responsáveis com defaults vindos de `/api/parametros`, painel de avisos A2/C2.
- [ ] **Gerar PDF**: chamada ao backend, download do blob, exibição de erros de validação por campo, "Novo orçamento" limpando o estado (sem `reload`).
- [ ] **Histórico**: lista paginada e filtrável no servidor, re-download, exclusão (conforme permissão), importação do JSON da v3.5.
- [ ] **Admin**: telas de lojas, templates e parâmetros.
- [ ] Porta do CSS para SCSS por componente + tokens de tema (CSS custom properties); responsivo ≥ 720 px e mobile.

### Fase 6 — Qualidade, segurança e implantação · ~1 semana
- [ ] Testes: unitários (JUnit 5/Mockito; Jasmine/Jest), integração (Testcontainers + PostgreSQL), e2e (Playwright) do fluxo completo OPEX e CAPEX.
- [ ] Limites de upload (tamanho/quantidade/MIME), rate limit na extração, CORS, headers de segurança.
- [ ] Logs estruturados, métricas (chamadas à IA, tempo de geração, erros), health checks.
- [ ] Dockerfiles, pipeline de CI/CD, ambientes homologação e produção.
- [ ] Manual curto do usuário e do administrador.

### Fase 7 — Transição · ~1 semana
- [ ] Homologação em paralelo: mesmos casos na v3.5 e no novo sistema, conferência pelos usuários-chave.
- [ ] Importação dos históricos locais (JSON exportado pela v3.5).
- [ ] Go-live, período de acompanhamento e **desativação da v3.5** (e da chave antiga, se ainda ativa).

**Estimativa total:** ~8–9 semanas para 1 dev full-stack (≈ 5–6 semanas com backend e frontend em paralelo).

---

## 8. Critérios de aceite

1. Os casos de referência geram PDFs com **os mesmos valores em todos os campos** que a v3.5 (mesmo template, itens, totais, datas, observações, ordem dos anexos).
2. Nenhuma credencial chega ao navegador (verificável inspecionando o bundle e o tráfego).
3. Todas as regras A1, A2, A4 e C2 cobertas por testes automatizados.
4. Retry/fallback da IA cobertos por testes com respostas simuladas (429 por minuto × quota diária, 5xx, 400 de config, JSON inválido).
5. Histórico acessível de qualquer máquina, com registro de quem gerou cada orçamento.
6. Lojas, templates e parâmetros alteráveis sem novo deploy.

---

## 9. Mapa de rastreabilidade (função atual → destino)

| HTML v3.5 | Backend (Spring) | Frontend (Angular) |
|---|---|---|
| `CADLOJAS`, `buscarLoja`, `buscarPorNome`, `normalizarNumLoja` | `LojaService`, tabela `loja` | `LojaAutocompleteComponent` |
| `TEMPLATES_B64`, `FIELD_MAP`, `ITEM_ROWS` | `template_pdf` + storage | Admin › Templates |
| `composerOnPaste`, `adicionarDoComposer`, `renderEntrada`, `fazerChip` | — | `ComposerComponent`, `AnexoChipComponent` |
| `textoParaImagemDataURL` | (opcional) `TextoRasterizer` | — |
| `setModo`, `checkExtractReady` | `ModoAquisicao` enum | `OrcamentoStore` (signals) |
| `extrairDados`, prompt, `chamarGeminiComRetry/Fallback` | `ExtracaoService`, `GeminiClient`, Resilience4j | `ExtracaoService` (HTTP) + overlay |
| `preencherFormulario` (avisos A2/C2) | `RegrasExtracao` | `RevisaoComponent` |
| `addItemComDados`, `calcItem`, `calcTotal`, `somaItens` | `CalculoOrcamento` (BigDecimal) | `ItensFormArray` |
| `validarItens` (A1) | `ValidadorOrcamento` | validadores de formulário |
| `gerarPDF`, `anexar*`, `carimbarRotulo`, `embedImageViaCanvas` | `PdfService` (PDFBox) | `GerarPdfComponent` |
| IndexedDB (`salvar/listar/remover/limparHistorico`, export/import) | `HistoricoController` + tabela `orcamento` | `HistoricoComponent` |
| `setTheme`, `setNav`, `flashToast`, `setStatus` | — | `ThemeService`, `StepperComponent`, toasts |
