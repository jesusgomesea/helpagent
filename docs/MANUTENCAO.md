# Guia de manutenção — HELP-AGENT Orçamentos

Para quem vai mexer no código. A documentação geral (e o mapa de todos os documentos) está em
[DOCUMENTACAO.md](DOCUMENTACAO.md); como rodar, no [README](../README.md). O escopo
original e o plano de migração estão em [ESCOPO-E-PLANO-MIGRACAO.md](ESCOPO-E-PLANO-MIGRACAO.md).

## 1. Como o sistema funciona

```
Navegador (Angular)                          Servidor (Spring Boot, 127.0.0.1:8080)
───────────────────                          ──────────────────────────────────────
1. Composer: cola/anexa chamado e orçamentos
2. "Extrair dados com IA" ── multipart ────▶ POST /api/extracoes
                                               ExtracaoService → PromptExtracao (prompts/extracao.txt)
                                               ExtratorIa (cadeia de modelos + cota) → GeminiClient → Google
                                               RegrasExtracao (avisos A2/C2) + LojaService.resolver
3. Revisão: formulário preenchido ◀── JSON ─┘
4. "Baixar PDF" ── multipart (dados+arquivos)▶ POST /api/orcamentos
                                               ValidadorOrcamento (A1) → CalculoOrcamento
                                               PdfOrcamentoService (PDFBox): template + anexos
                                               ArmazenamentoLocal (PDF em disco) + tabela orcamento
   download do PDF ◀────────────────────────┘
5. Histórico ─────────────────────────────▶ GET /api/historico · /exportar · /importar
```

O navegador só conhece `/api/...`. Em desenvolvimento e no uso atual pela rede, o `ng serve` recebe tudo
na porta 80 e encaminha `/api` para o backend (`frontend/proxy.conf.json`). O backend não fica exposto.

### Tipos de requisição (desde 28/09/2026)

| Tipo (banco) | Tela | Chamado | Observação do impresso |
|---|---|---|---|
| `REQUISICAO` | **Requisição / Chamado** (padrão) | obrigatório | `# 1021069. Manutenção de…`, o formato de sempre |
| `OPEX` | OPEX | obrigatório | `OPEX. # 1021069. …` |
| `CAPEX` | CAPEX | opcional; não vai para a IA | `CAPEX. Aquisição de…` |

- **Nomes:** o nome exibido fica só em `ROTULO_MODO` (`frontend/src/app/core/modelos.ts`). "Requisição / Chamado"
  é provisório, e trocar ali não mexe em banco nem em backup. **Não renomeie a constante do enum**, porque ela é o valor gravado.
- **Obrigatoriedade do chamado:** vem de `ModoAquisicao.exigeChamado()` (backend) e de `exigeChamado()` (frontend).
- **Prompt:** OPEX e Requisição usam o mesmo prompt; o OPEX só ganha o rótulo `OPEX. ` no começo da observação.
  O `PromptExtracaoTest` garante que, sem o rótulo, o texto é idêntico ao de antes, então a precisão medida continua valendo.
- **Histórico antigo:** até 28/09 "OPEX" era o fluxo normal. A migration V4 moveu os 97 que existiam para
  `REQUISICAO`. O backup de antes está em `backend/dados/backups/historico-antes-dos-3-tipos-2026-09-28.json`.
  Backup em formato **versão 2** (exportado antes disso) com "OPEX" também é importado como `REQUISICAO`.
- **Tela de histórico:** abas por tipo com contagem (que respeita a busca) e paginação de 20 em 20. A aba, a
  página e a busca ficam na URL (`/historico?tipo=CAPEX&pagina=2`). A consulta é uma `Specification`
  (`historico/FiltroHistorico`), não JPQL fixo: cada filtro é opcional, e a listagem e as contagens usam o mesmo.
- **Filtros do histórico (desde 30/09/2026):** botão "Filtros" abre um painel com período de **emissão** (de/até),
  loja (inclusive desativadas), faixa de **total** e origem (documentos ou cotação). Vão na URL
  (`?de=2026-09-01&ate=2026-09-30&loja=23&valorMin=100&valorMax=5000&origem=COTACAO`) e para a API com os mesmos
  nomes (`HistoricoController.FiltrosPainel`); valem na lista e nos números das abas. Também filtra por
  **fornecedor** (`?fornecedor=<id>`), e cada linha da lista mostra os fornecedores do orçamento. Filtro novo: campo no
  `FiltroHistorico` + parâmetro no `FiltrosPainel` + campo no painel (`historico.page.ts`).
- **Lixeira (desde 30/09/2026, V8):** "apagar" preenche `orcamento.excluido_em/excluido_por` em vez de apagar. O
  orçamento some das abas, do aviso de chamado já orçado e do backup, e aparece na aba **Lixeira**
  (`?tipo=LIXEIRA`), de onde é restaurado (`POST /{id}/restaurar`) ou excluído de vez (`DELETE /{id}/definitivo`,
  só para o que já está na lixeira). Passados 30 dias, `LixeiraHistorico` apaga registro e PDF — na subida e sempre
  que a lixeira é aberta (não há agendador). Cada movimento vai para o log com o IP/usuário.

### Recursos da revisão

- **Documentos ao lado do formulário** (`documentos.ts`): abas com os orçamentos e chamados, com imagem em
  "ajustar à largura"/"tamanho real" e PDF no visualizador do navegador.
- **Chamado já orçado** (`ChamadosJaOrcados`): aviso com link para o PDF anterior, feito depois da leitura e
  quando o número é editado. A busca é por número inteiro, então "102107" não casa com "1021071".
- **Válido até**: a IA só informa o que está escrito (`validade_ate` ou `validade_dias`) e o servidor faz a
  conta (`ExtracaoService.validadeSugerida`). Validade anterior à emissão é recusada.
- **Fonte de cada valor**: a IA diz de onde leu cada total, por exemplo "pág. 1, linha Subtotal". Isso vai só
  para o log (`Extração item 1: … fonte="…"`), não para a tela, e serve para auditar uma leitura errada.
- **Fornecedor de cada item (desde 30/09/2026, V9)**: a IA lê quem emitiu cada orçamento (`fornecedor`,
  `fornecedor_cnpj`; o prompt proíbe usar o grupo R Damásio, que é o cliente). O servidor reconhece o cadastrado
  (`ReconhecimentoFornecedor`: CNPJ válido → nome/apelido com a mesma "chave" → nome lido que **começa** pelo
  cadastrado, em palavra inteira e com 5+ letras; nunca "contém no meio") e a revisão mostra "cadastrado" ou
  "novo — será cadastrado". Ao gerar, `FornecedorService.vincular` liga a linha ao cadastro, **cadastra quem é novo**
  e guarda o nome lido como apelido — o cadastro cresce sozinho. Na cotação, o fornecedor é a loja. O impresso não
  tem campo de fornecedor: é dado do histórico (lista, filtro "Fornecedor") e do cadastro. Regra conservadora de
  propósito: juntar dois fornecedores diferentes é pior que cadastrar um repetido (esse se corrige na tela).
  Testado em 30/09 com um orçamento real: leu "Image Informática Ltda" e o CNPJ, e não o cliente.

### Regras de negócio (nomes herdados do HTML v3.5, citados nos comentários)

| Código | Regra | Onde |
|---|---|---|
| **A1** | Não gerar impresso cujo total não fecha com as linhas visíveis (linha com valor sem produto, produto sem valor, nenhum item) | `ValidadorOrcamento` |
| **A2** | O impresso tem 10 linhas; se a IA trouxer mais, avisar quantas ficaram de fora | `RegrasExtracao`, `helpagent.orcamento.max-itens` |
| **A4** | Data de emissão na data **local**, não UTC (depois das 21h o UTC já é amanhã) | `dataLocalISO` (frontend) |
| **C2** | Total lido pela IA ≠ soma das linhas (> R$ 0,05) → aviso de conferência | `RegrasExtracao` |
| — | Cada orçamento anexado vira **um** item consolidado (qtd 1, valor = total do orçamento) | prompt |
| — | Preço de e-commerce: à vista/PIX > preço regular; nunca parcela | prompt |

## 2. Mapa do código

### Backend — `backend/src/main/java/br/com/rdamasio/helpagent/`

| Pacote | Responsabilidade |
|---|---|
| `config` | `HelpAgentProperties` (tudo que é configurável, prefixo `helpagent.*`), segurança/CORS, usuário atual, `/api/parametros`, `OpenApiConfig` (contrato em `/v3/api-docs`), `Recursos` + `GuardaRecursos` (liga/desliga IA e cotação; desligado → 503) |
| `common` | `Dinheiro` (BRL ↔ `BigDecimal`), `Documento` (arquivo enviado), `OrigemRequisicao` (IP para os logs de auditoria), `IdRequisicaoFiltro` (X-Request-Id no log e no erro), erros de negócio e o `TratadorErros` (ProblemDetail) |
| `loja` | Cadastro de lojas e busca por número/nome |
| `fornecedor` | Fornecedores conhecidos: reconhecimento do que a IA leu (`ReconhecimentoFornecedor`), vínculo da linha ao gerar e o cadastro `/api/fornecedores` |
| `template` | Os 4 impressos: `TemplateCodigo`, nomes dos campos AcroForm (`CamposImpresso`) e leitura do PDF em branco |
| `extracao` | Tudo da IA: prompt, cliente Gemini, política de tentativas e cadeia de modelos, parser da resposta, avisos |
| `usoia` | Controle de cota do Gemini por modelo (`ControleCotaIa`), registro de cada chamada (tabela `uso_ia`), métricas Prometheus, saúde `ia` (`SaudeIa`) e o painel `/api/uso-ia` (§4) |
| `orcamento` | Entidades, cálculo, validação A1 e o serviço que gera o impresso |
| `pdf` | Montagem do PDF com PDFBox (preenchimento, anexos, carimbo, página de erro) |
| `armazenamento` | Onde os PDFs gerados ficam (hoje: disco local) |
| `historico` | Consulta (`FiltroHistorico`), download, lixeira (`LixeiraHistorico`), backup JSON (exportar/importar) |
| `cotacao` | Cotação em 6 lojas online: Chrome via Playwright, uma `Fonte*` por loja, motor de ranking, planilha (§7); prints das páginas de produto para o orçamento por cotação (`PrintsCotacao`, §8) |

Recursos: `application.yml` (padrões), `application-local.yml` (perfil sem PostgreSQL e sem login),
`db/migration/` (Flyway), `prompts/extracao.txt`, `pdf-templates/*.pdf`, `cotacao/extrair-<loja>.js` (um por loja da cotação),
`cotacao/preparar-print.js` (fecha o aviso de cookies antes do print).

### Frontend — `frontend/src/app/`

| Pasta | Conteúdo |
|---|---|
| `core/` | `api.ts` (todas as chamadas HTTP), `modelos.ts` (tipos da API), `recursos.ts` (o que o servidor oferece; esconde menu/rotas desligados), `configuracao.ts` + `interceptador-api.ts` (config.json, apiBase, X-Request-Id, token do portal), `imagem-api.ts` (imagens da API pelo HttpClient), `dinheiro.ts`, `arquivos.ts` (anexos, texto→imagem), `avisos.ts` (toasts e overlay), `marca.ts` (visual Damásio × TD) |
| `layout/` | Cabeçalho, faixa de abertura, etapas, overlay de carregamento, toasts, ícones SVG |
| `features/novo-orcamento/` | Página principal: `orcamento.store.ts` (estado com signals), `composer.ts` (card 1), `revisao.ts` (cards 2 e 3) |
| `features/historico/` | Lista, busca, download, backup JSON |
| `features/lojas/` | Cadastro de lojas |
| `features/fornecedores/` | Cadastro de fornecedores (`/lojas/fornecedores`, junto das lojas) |
| `features/cotacao/` | Cotação em lojas online: `cotacao.api.ts` (tipos + HTTP, separados do `core/api.ts`), `cotacao.page.ts`, `criterios.ts`, `resultado.ts`, `cesta.store.ts` (itens escolhidos para o orçamento por cotação) |
| `features/orcamento-cotacao/` | Tela de revisão e geração do orçamento por cotação (§8) |
| `features/uso-ia/` | Painel "Uso da IA" (área técnica, rota `/swagger/uso-ia`, fora do menu): cota de cada modelo da cadeia, totais do dia do Google, horas e últimas chamadas (§4) |

Estilo: **um único** `src/styles.scss`, no **Design System R Damásio** (o mesmo do piloto de cotação de
Suprimentos, adotado em 28/09/2026: papel claro, marinho `#0B3A5C` + vermelho `#CB2028`, fontes Archivo e
JetBrains Mono). Os tokens têm duas camadas:
1. **primitivos** `--rd-*` (paleta, fontes, sombras), que não mudam;
2. **semânticos** (`--primaria`, `--destaque`, `--topo`, `--text`, `--border`…), que os componentes usam e que
   cada marca redefine.

Os componentes usam as classes globais e não têm estilo próprio. Assim o visual inteiro muda num lugar só.

## 3. Receitas

### Trocar a chave ou o modelo do Gemini
- Chave: `backend/config/application-local.yml` → `helpagent.gemini.api-key` (fora do git). Em servidor: variável `GEMINI_API_KEY`.
- Modelos: a **cadeia** em `application.yml` → `helpagent.gemini.cadeia`, do melhor para o pior, com os limites
  de cada um (ver §4 "Cadeia de modelos e controle de cota"). Para comparar modelos sem cadeia: `GEMINI_MODELO_FORCADO`.
  (As antigas `GEMINI_MODELO_PRIMARIO`/`GEMINI_MODELO_FALLBACK` não existem mais.)
- Reiniciar o backend (`parar-helpagent.bat` → `iniciar-helpagent.bat`).

### Mudar o que a IA devolve (novo campo)
O contrato está em cinco lugares, que precisam andar juntos:
1. `prompts/extracao.txt`;
2. `DadosExtraidos.java`;
3. `GeminiClient.ESQUEMA_RESPOSTA`;
4. `frontend/src/app/core/modelos.ts`;
5. `tools/avaliar_extracao.py` (`SCHEMA`).

Depois de mudar, rode a avaliação com orçamentos reais para ver se a precisão se manteve.

### Mudar o prompt
Editar `backend/src/main/resources/prompts/extracao.txt`. Os trechos que mudam entre OPEX e CAPEX são
os marcadores `{{...}}`, preenchidos em `PromptExtracao`. O JSON pedido no prompt precisa continuar
batendo com `DadosExtraidos` (nomes em snake_case).

### Adicionar ou alterar uma loja
Pela tela **Lojas** (menu do cabeçalho), sem mexer em código. Regras, validadas no servidor (`LojaService`):
- número único e fixo; se a loja trocar de número, cadastre outra e desative a antiga;
- CNPJ conferido pelos dígitos verificadores (`common/Cnpj`);
- nome e empresa gravados em maiúsculas, como no cadastro original;
- loja não se apaga, só se desativa: ela some da revisão, mas os orçamentos antigos continuam apontando para ela.

Sem login, cada alteração vai para o log com o antes, o depois e o IP (`Loja 23 ALTERADA por 10.4.x.x: antes […] depois […]`).
O IP do atendente chega pelo `X-Forwarded-For`, que o proxy do `ng serve` preenche (`xfwd` em `proxy.conf.json`).
Não edite `V2__seed_lojas.sql` nem `V6__cadastro_completo_lojas.sql`: o Flyway recusa migration já aplicada que foi alterada.

**Cadastro completo (V6, 29/09/2026).** A planilha de lojas do grupo trouxe razão social, inscrição estadual, cidade e
UF (colunas opcionais, só cadastro: o impresso continua saindo com `empresa` e `cnpj`). A V6 sobrescreveu nome e CNPJ
das lojas existentes — o 123 mudou de `…/0002-38` para `…/0003-19` — sem mexer em empresa/impresso, e cadastrou as
que faltavam. Para essas, empresa e impresso foram escolhidos na migration e devem ser conferidos pela tela:
51 DAMASIO MT (DAM); 701–703 TDLM (TD); 905/907/913, cópias de 5/7/13 com outro número (TD); 800–806 RDS, hotéis,
Lucano, RD Emp. e Rufino (RDAM, provisório: não são motopeças e não há impresso próprio). O 806 veio com o mesmo CNPJ
do 804. O 53 DAMASIO MTS não está na planilha e ficou como estava.

### Trocar ou incluir um impresso (template PDF)
1. Salve o PDF em `backend/src/main/resources/pdf-templates/<CODIGO>.pdf`.
2. Novo código: acrescente em `TemplateCodigo` e na coluna `template_codigo` das lojas.
3. Confira os nomes dos campos do formulário. Se forem diferentes dos atuais (`Caixa de texto …`), o
   `CamposImpresso` precisa virar um mapa por template. Campo inexistente não quebra a geração, só gera
   um `WARN Campo '...' não existe no template` no log. Procure por isso depois de testar.

### Adicionar uma marca ao seletor de visual
1. Logo branca em `frontend/public/`.
2. `core/marca.ts`: nova entrada em `MARCAS` e no tipo `Marca`.
3. `styles.scss`: bloco `:root[data-marca='x']` redefinindo só a camada semântica (`--primaria`, `--primaria-escura`,
   `--primaria-clara`, `--anel`, `--destaque`, `--destaque-escuro`, `--topo`, `--topo-texto`, `--rodape`, `--border`, `--border2`).
4. `index.html`: o script inline que aplica a marca antes da pintura aceita só `td`. Inclua o novo id.

### Mudar cores e sombras
Só pelos tokens no topo de `styles.scss`. Cor de componente que deve seguir a marca usa o token semântico,
nunca um primitivo `--rd-*` direto. Exceções de propósito:
- as 4 cores dos critérios da cotação (`--cot-*`), que são fixas porque na TD primária e destaque são ambos vermelhos;
- o overlay de carregamento, que é escuro porque as logos da esteira são brancas.

### Backup antes de atualizar
Histórico → **Exportar JSON** antes de atualizar a versão ou trocar de banco. Depois, **Importar JSON**.
Repetidos (mesmo título + mesmo instante) são ignorados, então importar duas vezes é seguro.

## 4. Desempenho da leitura por IA

**Medido em uso real (25/09/2026):** uma leitura normal leva **5–10 s**. O caso lento (43,6 s) não era
leitura: o modelo principal sobrecarregado segurou **~37 s** antes de devolver *503 high demand*, e o
sistema insistia nele. Onde o tempo vai e o que cada peça faz:

| Etapa | Otimização | Onde |
|---|---|---|
| Envio | Imagens reduzidas no navegador para no máximo 2000px e recomprimidas se passarem de 1,5 MB | `core/arquivos.ts` (`LADO_MAXIMO`, `BYTES_MAXIMO`) |
| Espera por modelo lento | Limite de **30 s** por chamada (antes 90 s) | `helpagent.gemini.timeout` |
| Modelo sobrecarregado (503/tempo esgotado) | **Desce um degrau** da cadeia, sem repetir o mesmo; o modelo "esfria" 60 s e as próximas leituras preferem o de baixo | `ExtratorIa`, `ControleCotaIa.RESFRIAMENTO` |
| Todos já falharam nesta leitura | Volta ao melhor que ainda tem vez (sobrecarga é passageira: em 28/09 o principal deu 503 e, logo depois, respondia em 2–3 s). **Prazo total de 60 s** e **teto de 4 requisições** por leitura; depois a tela devolve o erro e dá para preencher à mão | `ExtratorIa` (`PRAZO_TOTAL`), `helpagent.gemini.max-chamadas-por-leitura` |
| Modelo lento (fila do Google) | Passou de **12 s** sem responder → dispara o degrau de baixo **em paralelo** (só se ele tiver cota) e vale a primeira resposta boa. Vale em **qualquer** degrau, não só no primeiro | `ExtratorIa.comReserva`, `helpagent.gemini.reserva-apos` |
| Cota diária esgotada (plano gratuito: **20/dia** no 3.6-flash) | Detectada pelo `quotaId` "PerDay" do erro, não pelo texto (o limite por minuto usa as mesmas palavras). Desce de degrau; o modelo fica fora **15 min** e é sondado de novo, porque a cota libera aos poucos | `ControleCotaIa.PAUSA_COTA_DIA`, `FalhaIa.cotaDiaria` |
| Cota por minuto esgotada | Desce de degrau **na hora** e o modelo descansa o `retryDelay` que o Google mandou (5 s a 2 min). Antes repetia o mesmo modelo 1,2 s depois e levava outro 429 | `ControleCotaIa`, `FalhaIa.cotaPorMinuto` |
| Resposta fora do formato | *Structured output* (`responseJsonSchema`): a API garante o JSON dos campos de `DadosExtraidos` | `GeminiClient.ESQUEMA_RESPOSTA` |
| Mesmos arquivos de novo | Cache por conteúdo (SHA-256) por **30 min**: resposta na hora, sem gastar cota | `CacheExtracao`, `helpagent.gemini.cache-extracao` |
| Raciocínio do modelo | `thinkingLevel` configurável (`low` padrão; `minimal` é mais rápido, testar a precisão antes). Nos modelos **2.x** vira `thinkingBudget` (0 em `low`): eles recusam `thinkingLevel` com 400 | `helpagent.gemini.nivel-raciocinio`, `GeminiClient.configRaciocinio` |
| Duplo clique / dois atendentes com o mesmo arquivo | A segunda leitura espera a primeira, **em andamento**, em vez de gastar outra requisição | `CacheExtracao.obter` |
| Espera percebida | Overlay com fase ("Enviando · 60%" → "IA lendo…"), cronômetro, e o toast final informa o tempo | `Processando`, `NovoOrcamentoPage` |

Medição depois das mudanças: leitura de 1 imagem pelo principal em 11,5 s (2.053 tokens de entrada, a
maioria do prompt; 158 de saída), e repetição dos mesmos arquivos em **0,24 s** pelo cache. O que sobra
no caminho normal é fila do lado do Google, e não tamanho de arquivo.

### Cadeia de modelos e controle de cota (29/09/2026)

**Por quê.** O RPM do projeto no Google subiu de repente. O servidor só descobria o limite levando 429, e o 429
também conta requisição; numa leitura ruim eram até 6 chamadas. Duas coisas mudaram: o fallback virou uma
**cadeia** de N modelos, e o servidor passou a **contar a própria cota** antes de chamar.

**Cadeia** (`helpagent.gemini.cadeia`, do melhor para o pior). Esgotou ou sobrecarregou, desce um degrau; quando a
pausa do modelo de cima acaba, as leituras voltam a ele sozinhas:

| Degrau | Modelo | Limites configurados (rpm · rpd) | Observação |
|---|---|---|---|
| 1 | `gemini-3.6-flash` | 5 · 20 | 20/dia veio do próprio 429 do Google |
| 2 | `gemini-3.5-flash` | 5 · 20 | estimativa; em 29/09 esgotou 30–40 s em 3 de 3 chamadas (sobrecarga no Google) |
| 3 | `gemini-3.5-flash-lite` | 10 · 20 | estimativa |
| 4 | `gemini-3.1-flash-lite` | 15 · 500 | estimativa; aceita o formato fixo (testado) |
| 5 | `gemini-2.5-flash` | 10 · 250 | estimativa; aceita o formato fixo com `thinkingBudget` (testado) |

Os limites acima são **estimativas** até alguém copiar os do painel https://aistudio.google.com/rate-limit (o Google
não os publica nem os devolve pela API). Se o Google recusar com um limite diário menor, o servidor passa a usar o
dele (log `[Cota IA] ... limite aprendido`). O `gemini-2.5-flash-lite` **ficou de fora**: aparece na lista de modelos,
mas a API responde 404 "no longer available to new users" (testado em 29/09). Na subida, o servidor lista os
modelos da chave (não gasta cota) e tira da cadeia os que não existem; um 404 em uso também tira o modelo até
reiniciar. **Antes de pôr um modelo novo na cadeia**, rode `tools/avaliar_extracao.py` com ele forçado
(`GEMINI_MODELO_FORCADO`): modelo menor erra mais valor.

**Controle de cota** (`usoia/ControleCotaIa`), por modelo e em memória:
- janela móvel de 60 s de requisições e tokens de entrada (RPM/TPM) e contagem do dia (RPD);
- **o dia do Google vira à meia-noite do Pacífico** (4h ou 5h em Brasília), não à meia-noite daqui;
- antes de cada chamada, **reserva** a vaga (duas leituras simultâneas não passam juntas pelo último lugar). Sem vaga,
  desce de degrau **sem chamar** — conta como "pulo" no painel;
- tokens: reserva uma estimativa (prompt ÷ 3,5 + ~1.100 por imagem ou página de PDF) e troca pelo que o Google contou;
- a 80% do limite diário, avisa no log uma vez por dia e modelo.

**Anti-rajada**: no máximo **3 leituras ao mesmo tempo** no servidor (`max-simultaneas`; a próxima espera até 45 s
por vaga e então devolve "IA ocupada") e no máximo **4 requisições por leitura** (`max-chamadas-por-leitura`).

**Registro** (tabela `uso_ia`, V7): uma linha por requisição enviada, com modelo, degrau, papel (principal,
reserva, degrau, repetição), resultado, tokens, tempo, tipo de orçamento, nº de arquivos e IP. Guardado 90 dias. Na
subida, os contadores do dia são remontados daqui, senão um reinício "devolveria" a cota já gasta.

**Painel "Uso da IA"** (área técnica: só pelo endereço **`/swagger/uso-ia`**, fora do menu do helpdesk desde 30/09/2026; atualiza a cada 15 s): situação de cada degrau, requisições e
tokens do dia, **pico por minuto** (é o "RPM" do painel do Google), leituras que caíram em modelo reserva, colunas
por hora e as 40 últimas chamadas. Quando a leitura sai de um modelo abaixo do primeiro, a revisão ganha o aviso
"Lido pelo modelo reserva ...: confira valores e quantidades".

**Limitação que não dá para resolver aqui:** o Google conta por **projeto**. Outra máquina com a mesma chave ou o
HTML v3.5 (que tinha a chave embutida) gastam a mesma cota sem passar por este servidor. Se o AI Studio mostrar
mais requisições que o painel, é isso — revogar a chave antiga resolve.

**Incidente de 28/09/2026 ("extrator lento"):** o prompt estava intacto. O principal devolveu 503 e o
alternativo (`flash-lite`) estourou 30 s três vezes seguidas: 95 s até o erro. Medido logo depois, a mesma
leitura levou **2–3 s no principal**, com ou sem formato fixo, e **de 2 s a mais de 60 s no alternativo**, também
com ou sem formato fixo. Ou seja, era instabilidade do alternativo no Google, e a política insistia nele. Hoje
o sistema alterna entre os modelos (linha "Todos já falharam nesta leitura" acima).

**Como diagnosticar lentidão:** o log fica em `backend/dados/logs/helpagent.log` (perfil local; gira em 10 MB,
guarda 14 dias). O backend registra, para cada chamada,
`[Gemini] <modelo> → 200 em <ms> · tokens entrada=… saída=… raciocínio=…` e, por extração,
`Extração … duracaoMs=… arquivos=… tamanhoKB=… cache=…`.
- Muitos tokens de **entrada**: imagem ou PDF pesado (PDF multipágina conta cada página).
- Muitos de **raciocínio**: baixar `nivel-raciocinio`.
- `HTTP 503` ou "não respondeu": sobrecarga do Google. A cadeia já cuida disso.
- Leitura que caiu em modelo reserva, recusas e pico por minuto: `/swagger/uso-ia` ou `GET /api/uso-ia`.

### Avaliação com orçamentos reais (25/09/2026) — `tools/avaliar_extracao.py`

6 orçamentos do histórico, com o total revisado pelo atendente como gabarito: print do Mercado Livre (riscado,
"16% OFF" e parcelas), tabela de fornecedor, ordem de serviço fotografada, proposta escaneada de 6 páginas
e propostas em PDF. Modelo alternativo (`flash-lite`), porque a cota do principal acabou no meio do teste.

| Configuração | Acertos | Observação |
|---|---|---|
| atual (`low`) | 5/6 | errou só a proposta de 6 páginas com anotações à mão |
| `minimal` | 6/6 | acertou a de 6 páginas, mas por variação: `raciocínio=null` em todas as configs mostra que o modelo quase não raciocina já em `low` |
| `low` + formato fixo | 5/6 | mesma precisão e tempo do atual → **adotado** (só ganha robustez) |

- **Preço à vista:** acertou o print do Mercado Livre (R$ 2.678, e não o riscado nem a parcela) em todas as
  configurações e nos dois modelos.
- **`minimal`: não adotado.** Sem ganho mensurável, já que o modelo não gasta tokens de raciocínio em `low`,
  e sem amostra suficiente para provar que não piora nos casos difíceis.
- **Paralelo por orçamento: não adotado.** Com 2 orçamentos, 3,0 s juntos contra 2,8 s em paralelo. A outra
  medição (29 s contra 2,4 s) foi fila do Google, que a reserva em paralelo já cobre. Dobra a cota gasta.
- O erro recorrente (proposta com o valor certo impresso, mas com anotações e páginas extras) é de
  interpretação, não de configuração. Quem pega é a revisão humana.

Para repetir depois de trocar modelo ou prompt, com o backend rodando:
`python tools/avaliar_extracao.py --ids 40 36 45 51 39 161 --configs atual minimal schema`.
Cada chamada gasta cota.

**Ainda possível, não feito:** *resolução de mídia* (`mediaResolution`), que gasta menos tokens por imagem mas
arrisca errar valores pequenos. Avaliar com a ferramenta acima antes.

## 5. Armadilhas conhecidas (custam tempo se esquecidas)

- **Cotação: o Chrome não sobe.** O backend precisa rodar com um usuário logado na máquina, porque na sessão 0
  do Windows (Serviço, ou Tarefa Agendada "executar estando conectado ou não") o Chrome não abre. Bloquear a tela
  (Win+L) não atrapalha; fazer logoff, sim. Detalhes na §7.
- **Dois Chromes no mesmo perfil não sobem.** O perfil da cotação (`helpagent.cotacao.perfil`) fica travado pelo
  Chrome que o abriu. Por isso a homologação usa `backend/dados-homologacao/navegador` (§9): se apontar para o perfil
  da produção, a cotação de uma das duas falha enquanto a outra estiver rodando.
- **Arquivo `.java` com BOM não compila** (`illegal character: '\ufeff'`). O `Set-Content`/`Out-File` do
  PowerShell 5 grava UTF-8 com BOM. Edite pelo editor ou use `-Encoding utf8NoBOM` (PowerShell 7).
- **`&` em argumento do `mvnw.cmd` quebra o comando.** O `mvnw.cmd` passa pelo `cmd`, que lê o `&` como
  separador: tudo depois dele some, inclusive o `-DargLine` da pasta temporária (e a JVM falha com "loopback").
  Nas URLs de teste da cotação, evite `&`.
- **Pichau (Next.js): ler as tags `<script>`, não o array `self.__next_f`.** Depois que a página hidrata, o Next
  troca o `push` do array, e os pedaços que chegam depois (o dos produtos) não ficam guardados nele. Lendo o
  array, a Pichau voltava com 0 anúncios.
- **JVM não sobe: `Unable to establish loopback connection`.** O TEMP do Windows com nome curto 8.3
  (`ELUAN~1.JES`) quebra o socket AF_UNIX interno do NIO. Solução: `-Djdk.net.unixdomain.tmpdir=<pasta sem ~>`.
  O `.bat` já passa isso.
- **`.bat` abre a janela do backend mas nada sobe ("mvnw.cmd não é reconhecido").** Com a variável
  `NoDefaultCurrentDirectoryInExePath` ligada, o `cmd` não procura comandos na pasta atual. O `.bat` chama o
  `mvnw.cmd` pelo caminho completo, com aspas externas extras: com mais de duas aspas na linha, o `cmd /k`
  remove a primeira e a última. Não simplifique essa linha.
- **`npm install` trava.** `registry.npmjs.org` é bloqueado na rede; use `--registry=https://registry.yarnpkg.com`.
- **403 ao abrir por um nome novo.** O `ng serve` só aceita os nomes listados em `allowedHosts`
  (`frontend/angular.json`), como proteção contra DNS rebinding. Nome novo precisa entrar ali.
- **Backend recusando conexão do proxy.** O backend escuta só em `127.0.0.1`, e o proxy aponta para
  `127.0.0.1:8080`, não para `localhost`. O Node pode resolver `localhost` para `::1` (IPv6), onde não há ninguém.
- **Schema.** `ddl-auto: validate`: o Hibernate só confere, e quem cria tabela é o Flyway. Mudou entidade?
  Crie migration. Use SQL padrão (sem tipos exclusivos do PostgreSQL), porque os testes e o perfil local rodam em H2.
- **Dinheiro é `BigDecimal` no backend.** O frontend manda números. Toda conta que vale é refeita no
  servidor (`CalculoOrcamento`), e o total da tela é só visual.
- **Emoji ou caractere fora do Latin-1 em campo do PDF.** A fonte do formulário (WinAnsi) não codifica.
  O `PdfOrcamentoService` remove o caractere em vez de perder o campo inteiro.

## 6. Convenções

- Nomes de classes, métodos, campos e mensagens em **português**. Comentários explicam o **porquê**
  (regra de negócio, armadilha, decisão), não o que a linha já diz.
- Toda classe/arquivo começa com um comentário de 1–3 linhas dizendo seu papel.
- Regra de negócio nova ganha teste e um código na tabela da seção 1 se for conferência exibida ao usuário.
- Frontend: componentes standalone, `OnPush`, estado em signals, sem zone.js. Tipos da API ficam em `core/modelos.ts`; a cotação, por ser funcionalidade à parte, tem os seus em `features/cotacao/cotacao.api.ts`.
- Nada de segredo em arquivo versionado. `backend/config/` está no `.gitignore`.

## 7. Cotação em lojas online (`cotacao`, tela `/cotacao`)

Começou como o piloto em Python de Suprimentos, só com o Mercado Livre (`legacy/cotacao-suprimentos/`, repasse em
[legacy/cotacao-suprimentos/HANDOFF.md](../legacy/cotacao-suprimentos/HANDOFF.md)). Foi portado para o Java com
**paridade provada por teste** e depois ampliado para 7 lojas. É funcionalidade à parte: não toca no orçamento
nem no banco.

**Amazon removida em 30/09/2026** (pedido do usuário): saíram `FonteAmazon` e `extrair-amazon.js`; hoje são **6 lojas**.
Para voltar, os dois arquivos estão no histórico do git (commit anterior a esta remoção) e a receita "Acrescentar outra
loja" abaixo vale. Os tempos medidos antes dessa data, nesta seção, são com as 7 lojas.

### Lojas e níveis de busca

| Nível | Grupo | Lojas | Quando usar |
|---|---|---|---|
| 1 (padrão) | Varejo de TI | Kabum, Pichau, Terabyte | peças e periféricos; o melhor preço à vista costuma estar aqui |
| 2 | + Marketplace | Mercado Livre | item que o varejo de TI não tem, ou para comparar |
| 3 | + Fabricantes | Dell, Lenovo | notebooks, desktops, monitores dessas marcas |

Os níveis estão em `CotacaoService.NIVEIS`; o grupo de cada loja, na própria classe (`FonteCotacao.Grupo`); a ordem
na tela e na planilha, no `@Order` de cada fonte. A tela também deixa marcar loja por loja.

Tempos medidos em 28/09/2026: nível 1 ~20 s; as 7 lojas juntas, 24–42 s. A primeira cotação depois de subir o
backend é mais lenta (o Chrome cria o perfil).

### Fluxo
```
POST /api/cotacao {termo, paginas, fontes, criterios}
  CotacaoService ─► ColetorCotacao ─► Chrome instalado (Playwright), uma aba por página de loja
       │                 │            1) dispara todas as navegações (COMMIT)  2) lê aba por aba
       │                 ├► FonteKabum, FontePichau, ... ─► resources/cotacao/extrair-<loja>.js
       │                 └► ResolvedorPatrocinados (só o click1 do ML) · sem duplicados (loja + título)
       ├─► MotorCotacao: eliminatórios (ordem fixa) → score ponderado → grupos por segmento
       └─► guarda em memória por `id` (30 min): /reavaliar e /planilha usam esse id
```
- **Lojas em paralelo**: o coletor abre uma aba por página e dispara todas as navegações antes de ler qualquer
  uma. O total fica perto da loja mais lenta, não da soma (em sequência, as 7 levariam ~100 s). Em ondas de 8 abas.
- **Falha de uma loja não derruba as outras**: vira aviso ("Pichau não devolveu resultados para o termo") e a
  tela mostra a loja riscada na lista "anúncios por loja".
- **Reavaliar** (`POST /api/cotacao/{id}/reavaliar`) roda só o motor sobre os mesmos anúncios, na hora e sem
  abrir o Chrome. A tela chama sozinha 350 ms depois da última mudança num critério.
- **Uma cotação por vez** no servidor inteiro (trava no `ColetorCotacao`): os objetos do Playwright não podem
  ser usados por duas threads. `GET /api/cotacao/estado` diz se há uma em andamento, e a tela avisa que vai
  entrar na fila.
- Cada cotação vai para o log com o termo, as lojas e o IP
  (`Cotação 'ssd 256gb' em [kabum, pichau, terabyte] (1 pág.) pedida por 10.4.x.x`).

### De onde cada loja tira os dados

Sempre que o site embute os dados em JSON, a extração lê o JSON e não o HTML: quebra bem menos quando o
layout muda. Tudo que depende do site fica em `resources/cotacao/extrair-<loja>.js`, com os seletores comentados.

| Loja | Fonte dos dados | Preço usado | Nota | Quem vende | Ponto frágil |
|---|---|---|---|---|---|
| Kabum | `script#__NEXT_DATA__` → `props.pageProps.data.catalogServer.data` | `priceWithDiscount` (PIX) | `rating` se `ratingCount > 0` | KaBuM! ou parceiro (`flags.isMarketplace`) | caminho do JSON |
| Pichau | tags `<script>self.__next_f.push(...)` → linha com `products.items` | `pichau_prices.avista` (PIX) | não tem | Pichau | formato do Next.js (ver armadilha abaixo) |
| Terabyte | cartões `.product-item` e atributos `data-tss-*` | `data-tss-price` (Pix) | `.tss-rating-value` | Terabyte | nomes das classes |
| Mercado Livre | cartões `li.ui-search-layout__item` (tabela abaixo) | preço do cartão | chips de avaliação | vários | layout muda com frequência |
| Dell | atributo `data-product-detail` (JSON por cartão) | `dellPrice` | não tem | Dell | nome do atributo; título genérico + especificações do cartão |
| Lenovo | cartões `.product_item[data-product-code]` | `.price-summary-info .price-title` | `.card-rating-container` | Lenovo | classes do preço |

**Esgotado ou indisponível nunca entra no ranking** (desde 29/09/2026). O script de cada loja devolve o produto
sem estoque como `{titulo, indisponivel: true}`; o `FonteComScript` descarta antes do motor e conta, e o
`ColetorCotacao` transforma a contagem em aviso ("Terabyte: 223 anúncios esgotados ou indisponíveis ignorados";
"Kabum: todos os N resultados estão esgotados..."). Como cada loja diz que acabou:

| Loja | Sinal de esgotado/indisponível |
|---|---|
| Kabum | `available === false` no JSON. **Não** usar `quantity`: vem 0 em quase todos, inclusive em produto da KaBuM! com `available` verdadeiro |
| Pichau | `stock_status === "OUT_OF_STOCK"` |
| Terabyte | `data-tss-estoque="0"` ou etiqueta `.esgotadoL` ("Esgotado") — a busca lista os esgotados junto (223 de 300 em "ssd 256gb") |
| Dell | `data-is-sold-out="True"` |
| Lenovo | `data-adobe-params` com `marketingStatus` diferente de `"Available"` (ex.: `"Temporarily Unavailable"`) |
| Mercado Livre | a busca não lista anúncio sem estoque; o script (cópia do piloto) não foi alterado |

No **print** do "Escolher para o orçamento", o `validar-print.js` também confere a página do produto: aviso curto
visível de esgotado/indisponível/"avise-me" **e** nenhum botão de comprar visível → o print sai com o alerta
"a página indica produto esgotado ou indisponível — escolha outro anúncio" (o produto acabou entre a busca e a
escolha). A regra dupla evita falso alerta com "relacionados esgotados" no rodapé da página.

Mercado Livre em detalhe (o script é o do piloto, sem alteração). **Use `textContent`, nunca `innerText`**: os
dados acessíveis (`.andes-visually-hidden`) são escondidos por CSS, e `innerText` volta vazio.

| Seletor | Extrai | Sintoma se quebrar |
|---|---|---|
| `li.ui-search-layout__item` | o card do anúncio | Mercado Livre sem resultados |
| `.poly-component__title` | título e link | anúncio ignorado |
| `.poly-price__current .andes-money-amount__fraction` | preço | anúncio descartado (sem preço) |
| `.andes-visually-hidden` | nota, vendidos, frete grátis, origem | tudo `null` |
| `.poly-component__review-compacted` | nota (layout alternativo) | nota `null` → tudo cortado por "sem avaliação" |
| `use[href="#poly_full"]` · `use[href="#poly_cockade"]` | FULL · loja oficial | entrega e reputação pontuam errado |
| `click1.mercadolivre.com.br` no href | patrocinado | links de anúncio pago ficam de rastreamento |

### Decisões que não devem ser desfeitas sem medir
- **Chrome de verdade, com janela, fora da tela.** O Mercado Livre bloqueia HTTP direto, a API pública
  (`403` sem token de aplicação) e qualquer modo headless, mesmo com perfil aquecido. O piloto testou seis
  abordagens (tabela no HANDOFF, §7.1). Selenium é mais detectável ainda. As outras 6 lojas abriram sem bloqueio
  nesse mesmo Chrome (28/09/2026).
- **Flags contra "economia" de aba em segundo plano** (`--disable-background-timer-throttling` e afins, em
  `ColetorCotacao.abrirNavegador`): a janela fica fora da tela e as lojas abrem em abas de fundo.
- **Usa o navegador instalado** (`helpagent.cotacao.canal`: `chrome` ou `msedge`) e nunca baixa o Chromium do
  Playwright (`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`).
- **Perfil persistente** em `backend/dados/navegador/` (fora do git, porque tem cookies). Se um site pedir
  verificação de conta, resolva à mão uma vez (ver diagnóstico abaixo) e as próximas passam.
- **`vendidos = null` é "a página não informou", nunca zero.** Só o ML informa (às vezes); o filtro de volume mínimo só vale quando o dado existe. Tratar como 0 derruba a
  lista inteira com o filtro padrão de 100.
- **Loja própria sem nota não é eliminada** (`aceitarLojaPropriaSemNota`, ligado por padrão, com switch na tela).
  A regra do piloto ("vendedor sem avaliação pública" sai) foi feita para vendedor de marketplace e descartaria
  quase todo produto da Pichau, da Dell e da própria KaBuM!. Com a regra, quem vende é a loja (`vendedorProprio`)
  e a reputação é presumida com nota 4,5 (`MotorCotacao.NOTA_PRESUMIDA_LOJA_PROPRIA`, o mínimo padrão).
  **É mudança de política, acrescentada ao ampliar as lojas: Suprimentos deve validar.** O ML nunca tem
  `vendedorProprio`, então a paridade com o piloto continua valendo.
- **Duplicados só dentro da mesma loja.** O mesmo SSD na Kabum e na Pichau é justamente a comparação.
- **A ordem dos eliminatórios é regra:** produto → logística → reputação. O primeiro que bate vira o motivo exibido.
- **Arredondamento igual ao Python** (`MotorCotacao.arred`, `HALF_EVEN` sobre o valor exato). `Math.round` desalinha.
- **Pesos e cortes padrão (`CriteriosCotacao.PADRAO`) são política do departamento de Suprimentos**, não
  decisão técnica. Mudança passa por eles.

### Limitações conhecidas
- **As buscas das lojas misturam categorias.** "ssd 256gb" na Pichau traz notebooks e pen drives; "notebook i5"
  traz mesa de colo. Como preço baixo pesa muito no score, acessório barato pode virar o "melhor". O remédio é o
  filtro de título: "Título deve conter" (`256, nvme`), "não pode conter" (`pen drive, mesa`) ou segmentos.
- **Paginação**: Terabyte e Dell usam só a 1ª página (a Terabyte já traz a lista inteira; o catálogo da Dell é
  pequeno). A Lenovo pede mais itens na mesma página (`rows`).
- **Dell e Lenovo** só fazem sentido para produtos delas; para outros itens não devolvem nada, e isso vira aviso.

### Desempenho da cotação: investigação em aberto (28/09/2026)

**Queixa:** depois de entrar as outras lojas, a busca ficou lenta. Antes (só Mercado Livre) levava 7 a 15 s;
hoje o nível 1 leva ~20 s e as 7 lojas, 24 a 42 s. **Nada foi alterado na coleta ainda** — o que segue é medição
e proposta, à espera de validação.

Ferramenta: `DiagnosticoTempoManualTest` reproduz a coleta (uma aba por loja, mesmo Chrome) e mostra, por loja,
quando a resposta começou, o HTML ficou pronto, a página terminou de carregar, a lista foi lida e quanto durou a
extração. `-Dcotacao.modos` escolhe as variantes a comparar:
```bash
cd backend && ./mvnw test -Dtest=DiagnosticoTempoManualTest -Dcotacao.diagnostico=true -Dcotacao.modos=0,1,2,0,1,2
```
(modo 0 = como a coleta faz hoje · 1 = disparo imediato · 2 = disparo imediato + bloqueio dentro do Chrome.
Nesta máquina, acrescente `-DargLine=-Djdk.net.unixdomain.tmpdir=<pasta sem ~>`.)

**O que as medições mostraram** (7 lojas, "ssd 256gb"):

| Achado | Medida |
|---|---|
| Abrir o Chrome a cada cotação | 8–9 s na 1ª vez, ~1,5 s nas seguintes |
| O disparo das lojas **não é paralelo**: `navigate(COMMIT)` espera cada site responder antes de passar à próxima aba | a 7ª loja só começava aos 13–15 s |
| Cada página fica com o HTML pronto em menos de 2 s depois de a resposta começar | — |
| Modo atual, até ler a última loja (sem contar abrir o Chrome) | 17,8 s e 18,4 s |
| Disparo imediato sozinho (com `waitForURL` já em `COMMIT`) | 21,7 s e 17,6 s — sem ganho claro |
| Disparo imediato + bloqueio de imagem/fonte/rastreador dentro do Chrome (CDP) | 15,3 · 16,0 · 13,8 · 16,1 s — o melhor, ~3 s a menos |

**Descartado (medido):**
- *Espera de 30 s por loja sem resultado* — não acontece: a Dell sem o item devolve a página vazia na hora.
- *Bloquear imagem/fonte com `route` do Playwright* — piorou (lista lida aos ~25 s em vez de ~20 s): no Java,
  cada requisição interceptada passa pelo processo Java. Se for bloquear, tem de ser dentro do Chrome
  (CDP `Network.setBlockedURLs`, como no modo 2).
- *Contar requisições com `onRequest`* — também passa cada evento pelo Java; o diagnóstico só conta com
  `-Dcotacao.contar=true`.

**Hipótese principal ainda não confirmada:** com 7 sites pesados carregando juntos (60–100 scripts cada, anúncios
e rastreadores), o processador satura e a leitura de cada aba espera o JavaScript do site. Sinal disso: os
eventos `load` chegam aos 14–22 s, e o disparo imediato sozinho quase não ganhou.

**Ponto em aberto — por que a leitura demora depois do HTML pronto:** no disparo imediato, a Kabum fica com o
HTML pronto aos ~5 s mas só é lida aos ~19 s (e o modo 1 sozinho não ganhou do atual: ~20 s). Suspeitou-se do
`waitForURL`, que por padrão espera o `load` da página; passou a esperar só o `COMMIT` e **a demora continuou**
(19,4 s). Não se sabe ainda qual das esperas segura: `waitForURL`, `waitForLoadState(DOMCONTENTLOADED)`,
`waitForSelector` ou o `evaluate` da extração. É o primeiro passo ao retomar: cronometrar cada uma separadamente
no `DiagnosticoTempoManualTest`.

**Próximos passos, em ordem (propostos, ainda não validados):**
1. Descobrir qual espera segura a leitura (ponto em aberto acima). Depois repetir `-Dcotacao.modos=0,1,2,0,1,2`
   e decidir entre disparo imediato simples e disparo + bloqueio no Chrome (lista `BLOQUEIO_CDP` do diagnóstico).
   Conferir se algum site passa a pedir verificação anti-robô com o bloqueio.
2. Aplicar o escolhido no `ColetorCotacao` (hoje: `navigate(... COMMIT)` em sequência) e ler as abas **na ordem
   em que ficam prontas**, não na ordem das lojas, fechando cada uma logo após ler (libera processador).
3. Mostrar o tempo de cada loja na tela e no log (`porFonte` com tempo), para o usuário ver qual loja pesa.
4. Manter o Chrome aberto entre cotações (economiza 1,5–9 s por busca); fechar sozinho após alguns minutos
   sem uso e reabrir se cair. Pede cuidado com a trava de uma cotação por vez.

**Outra observação da medição:** a Terabyte devolveu 80 ou 29 anúncios para o mesmo termo em rodadas
seguidas, sem relação com o modo. Parece variação do próprio site; vale confirmar abrindo a busca no navegador.

### Diagnóstico
1. **Coleta isolada** com o Chrome desta máquina: mostra quantos anúncios vieram de cada loja, os avisos e os 2
   primeiros de cada uma.
   ```bash
   cd backend && ./mvnw test -Dtest=ColetaRealManualTest -Dcotacao.real=true -Dcotacao.termo="ssd 256gb" -Dcotacao.fontes=kabum,pichau
   ```
   Nesta máquina, acrescente `-DargLine=-Djdk.net.unixdomain.tmpdir=<pasta sem ~>`.
2. **Mapear ou consertar uma loja**: a ferramenta de exploração abre a URL no mesmo Chrome e salva em
   `backend/target/exploracao/` o HTML, o JSON-LD, o `__NEXT_DATA__` e uma captura de tela (o que o site mostra para
   o robô). Se existir `extrair-<id>.js`, também roda o script na página e mostra quantos itens ele achou.
   ```bash
   cd backend && ./mvnw test -Dtest=ExplorarLojaManualTest "-Dcotacao.explorar=kabum=https://www.kabum.com.br/busca/ssd-256gb"
   ```
   Não use `&` nas URLs de teste (ver armadilhas).
3. Com `-Dcotacao.visivel=true` (ou `helpagent.cotacao.janela-visivel: true`), a janela aparece na tela. Se
   houver verificação de conta ou captcha, resolva nela uma vez: o perfil guarda o cookie.
4. Página carrega, mas a extração vem vazia → a estrutura do site mudou (tabela "De onde cada loja tira os dados").
   Antes de mexer, confira na página se a loja realmente tem resultado: a Terabyte, por exemplo, às vezes devolve
   só 1 produto para uma busca que antes trazia dezenas ("Exibindo 1 de 1").
5. O Chrome abre e fecha na hora → antivírus/EDR bloqueando o controle do navegador (CDP). Peça exceção para
   o `java.exe` e a pasta do projeto.

### Testes
- `MotorCotacaoParidadeTest`: 4 cenários (padrão, segmentado, rigoroso, com volume) comparados com o
  `motor.py` original. Gabarito em `src/test/resources/cotacao/paridade.json`, gerado por
  `python tools/paridade_cotacao.py` sobre a amostra real `amostra-ssd-256gb.json` (Mercado Livre). Só regere se a
  regra mudar de propósito, ou se regravar a amostra com `-Dcotacao.gravar=true -Dcotacao.fontes=mercadolivre`.
- `LojaPropriaTest`: loja própria sem nota fica (e sai com o critério desligado); nota baixa continua eliminando;
  o mesmo título em lojas diferentes não é duplicado.
- `PlanilhaCotacaoTest`: as 4 abas, a coluna Loja, o top 3 e os links.
- As extrações por loja (`extrair-*.js`) não têm teste automático: rodam dentro do Chrome, contra o site real.
  A verificação é a coleta isolada do item 1 do diagnóstico.

### Acrescentar outra loja
1. Mapeie com a ferramenta de exploração (diagnóstico, item 2). Procure primeiro dados em JSON (`__NEXT_DATA__`,
   `__next_f`, atributos `data-*`, JSON-LD) e só depois seletores de HTML. Anote de onde vem o **preço à vista**.
2. Escreva `resources/cotacao/extrair-<id>.js`: uma função sem argumentos que devolve a lista de anúncios com as
   chaves de `Anuncio.doJs` (snake_case). Comece o arquivo com um comentário dizendo de onde vem cada dado.
3. Crie a classe `Fonte<Loja>` estendendo `FonteComScript` como `@Component` com `@Order`: id, nome, grupo, o
   seletor que indica "lista pronta" e as URLs de busca. Veja `FonteKabum` como modelo.
4. Acrescente a loja na tabela acima e, se entrar num grupo novo, em `CotacaoService.NIVEIS`.
5. Rode a coleta isolada com `-Dcotacao.fontes=<id>` e confira preço, "de", nota e link de 2 ou 3 produtos na loja.

## 8. Orçamento por cotação (tela `/orcamento-cotacao`, desde 29/09/2026)

O impresso nasce da cotação em vez de orçamentos de fornecedor. A validação manual exige o print de cada cotação,
então cada item leva **3 prints de página de produto**: a opção escolhida (a que vai para a linha do impresso) e 2
alternativas para comparar. Lojas diferentes podem se misturar no mesmo impresso.

```
Cotação (/cotacao)                                  Servidor
──────────────────                                  ────────
"Escolher para o orçamento" num anúncio ─────────▶ POST /api/cotacao/{id}/prints {urls: [escolhida, alt1, alt2]}
  alternativas = 2 de maior score do mesmo grupo      PrintsCotacao: confere que as URLs são desta cotação,
  (alternativasPara, cesta.store.ts)                  enfileira; ColetorCotacao.capturar abre as 3 abas,
                                                      espera "R$ 1…" na página, fecha cookies, fotografa;
cesta (localStorage) consulta a cada 2,5 s ◀────── CarimboPrint grava loja + hora + link na imagem (JPEG)
  GET /api/cotacao/prints?ids=…                       dados/prints/<id>.jpg + <id>.json (7 dias)
Revisão (/orcamento-cotacao): itens, prints,
  tirar de novo / anexar print à mão, dados ────────▶ POST /api/orcamentos (itens[].prints = ids)
                                                      OrcamentoService: todo print com imagem? (senão 422)
                                                      PDF: formulário → chamado → Resumo da cotação → prints
                                                      grava origem=COTACAO e fornecedor/url/coletado_em por linha
```

- **Por que o servidor só aceita URL da própria cotação:** o Chrome do servidor abriria qualquer endereço que a
  tela mandasse (inclusive da rede interna). `PrintsCotacao.solicitar` procura cada URL nos anúncios da cotação
  guardada; se ela expirou (30 min), a tela pede para refazer a busca.
- **Captura em segundo plano, fila única:** o atendente segue pesquisando enquanto o Chrome fotografa. Os prints
  dividem a trava do Chrome com a coleta: uma busca pedida durante a captura espera (~15 s por item, medido em
  29/09/2026 nas 3 lojas do nível 1).
- **"Página pronta" sem seletor por loja:** espera aparecer um preço em reais no texto da página (`TEM_PRECO`) e
  só então fecha o aviso de cookies (`preparar-print.js`, clica em "Entendi/Aceitar…" só dentro de bloco que fala de
  cookies/privacidade). Sem preço em 25 s → falha "produto indisponível ou verificação anti-robô".
- **Conferência do print** (`validar-print.js`, logo antes de fotografar): aviso de cookies/privacidade que continue
  fixo na tela é **escondido** (não aceita nada); depois procura o menor elemento com o preço coletado no formato
  da loja ("1.657,25", comparando o texto sem espaços, porque o site quebra o preço em vários `<span>`), rola até ele
  se estiver fora da janela e confere com `elementFromPoint` que nada está desenhado por cima. Não achou → o print sai
  com `alerta`, que a revisão mostra em laranja e o resumo do PDF marca "CONFERIR PRINT". **Não bloqueia a geração**:
  a página pode mostrar outro preço por CEP/promoção, e quem decide é o atendente (tirar de novo, anexar à mão ou seguir).
- **EM ABERTO (29/09/2026): a conferência dá falso alerta na Kabum e na Terabyte.** Teste com perfil novo: o aviso de
  cookies foi fechado nas 3 lojas e o preço aparece nos 3 prints, mas só a Pichau passou na conferência.
  - *Kabum:* o preço principal é um componente animado `<number-flow-react>` com *shadow DOM*, que desenha cada casa
    com os dígitos 0–9 (texto "R$ 0123456789…"). O texto da página nunca contém "149,99"; o valor real está no
    atributo `data` (JSON com `integer`/`fraction`). Caminho: reconstruir o número a partir desse JSON (a biblioteca
    number-flow é comum) ou procurar o preço também dentro de shadow roots abertos.
  - *Terabyte:* o preço está em `p#valVista` ("R$ 299,90", texto normal), então o problema é outro — provavelmente
    na rolagem até ele ou no `elementFromPoint`. Reproduzir com o `validar-print.js` na página e ver qual passo falha.
  - Enquanto isso o alerta não bloqueia a geração; só pede para o atendente abrir o print.
- **Carimbo na imagem, não só no PDF:** o print do Chrome não tem barra de endereço. A faixa com loja, hora da captura
  e link vai na própria imagem; o carimbo âmbar do PDF marca a hora em que o PDF foi montado.
- **JPEG e página deitada:** 10 itens = 30 prints; em PNG o PDF passaria de 10 MB. No PDF, os prints entram em A4
  paisagem (`DadosImpresso.Anexo.paisagem`), o que deixa o texto da loja ~60% maior que em pé.
- **Print anexado à mão** (`PUT /api/cotacao/prints/{id}/imagem`) ganha o mesmo carimbo com "print anexado à mão em…"
  e fica como `MANUAL`. Serve quando a loja bloqueia o robô (Mercado Livre) ou a página sai diferente.
- **Reinício do backend:** o print é relido do disco; o que estava capturando vira falha ("o servidor reiniciou") e a
  tela oferece tirar de novo.
- **Unitário editável:** começa no preço coletado. Se o atendente mudar, a revisão avisa, e o resumo do PDF continua
  mostrando o coletado (é o que o print prova).
- **Banco (V5):** `orcamento.origem` (`DOCUMENTOS` | `COTACAO`) e, por linha, `fornecedor`, `url`, `coletado_em`.
  O backup JSON leva os campos novos (versão 3 continua: são opcionais e backups antigos importam como `DOCUMENTOS`).
- **Lojas testadas:** Kabum, Pichau e Terabyte (29/09/2026, print com o preço à vista visível e igual ao coletado).
  Mercado Livre, Dell e Lenovo usam o mesmo código, sem ajuste por loja ainda: conferir com a captura real
  antes de liberar (é o próximo passo).

### Testes
- `ResumoCotacaoPdfTest`: o resumo vem logo depois do formulário, com a escolhida marcada; cada print tem o rótulo
  "Item 01 · ESCOLHIDA · Kabum"; grava `target/resumo-cotacao.pdf` e PNGs das páginas para conferir a olho.
- A captura real (`ColetorCotacao.capturar`) roda contra os sites; conferir subindo a homologação (§9) e escolhendo um
  item de cada loja.

## 9. Homologação (testar antes de ir para a produção)

Branch **`homologacao`**, rodando numa pasta própria (git worktree) e aberta por **`iniciar-homologacao.bat`**:

| | Produção | Homologação |
|---|---|---|
| Pasta | a do repositório, branch `main` | `..\HelpAgent-homologacao`, branch `homologacao` (`git worktree add`) |
| Abrir / parar | `iniciar-helpagent.bat` / `parar-helpagent.bat` | `iniciar-homologacao.bat` / `parar-homologacao.bat` |
| Endereço | http://helpagent.rdamasio.com.br (porta 80, rede) | http://localhost:4201 (só a própria máquina) |
| Backend | 127.0.0.1:8080 | 127.0.0.1:8091 |
| Dados | `backend/dados/` | `backend/dados-homologacao/` (banco, PDFs, prints, logs, perfil do Chrome) |

- O `.bat` passa tudo por variável de ambiente (`PORT`, `SPRING_DATASOURCE_URL`, `ARMAZENAMENTO_DIR`, `COTACAO_PRINTS`,
  `COTACAO_PERFIL`, `LOGGING_FILE_NAME`, `HELPAGENT_AMBIENTE`), sem arquivo de configuração novo. O frontend usa a
  configuração `homologacao` do `ng serve` (`angular.json`: `127.0.0.1:4201`, `proxy.homologacao.json` → 8091).
- `HELPAGENT_AMBIENTE=homologacao` faz a tela mostrar a faixa listrada "Ambiente de homologação" (`/api/parametros`).
- **Fluxo:** desenvolver na branch `homologacao` → testar pela homologação → juntar na `main`
  (`git merge homologacao`) → reiniciar a produção. Migration nova roda no banco de homologação primeiro, que é
  justamente o teste; antes de juntar, exportar o backup JSON da produção (§3).
- A chave do Gemini fica em `backend/config/application-local.yml` de **cada pasta** (fora do git).
