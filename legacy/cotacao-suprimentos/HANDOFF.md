# HANDOFF — Cotação Assistida (Suprimentos)

Documento de repasse técnico. Destinatário: equipe que vai assumir a continuidade e
executar a aplicação nas estações Windows.

| | |
|---|---|
| **Estado** | Piloto funcional, validado ponta a ponta |
| **Data do repasse** | 28/09/2026 |
| **Repositório** | https://gitlab.rdamasio.com.br/antonio.silva/cotacao-suprimentos |
| **Linhas de código** | 2.141 (4 módulos Python + 3 arquivos estáticos) |
| **Tamanho do pacote** | 1,6 MB (sem o perfil do navegador) |
| **Fonte de dados** | Mercado Livre Brasil (única, hoje) |

---

## 1. Escopo

### 1.1 O que o sistema faz

Recebe o nome de um produto, coleta os anúncios da busca no Mercado Livre, descarta os
que não atendem a requisitos mínimos, pontua os restantes segundo quatro critérios
ponderados e devolve um ranking — na tela e em planilha `.xlsx`.

### 1.2 Dentro do escopo

- Coleta de 1 a 3 páginas de resultado de busca (~50 anúncios por página)
- Extração por anúncio: título, preço atual, preço anterior, nota do vendedor, quantidade
  vendida, envio FULL, loja oficial, frete grátis, recondicionado, origem
  (nacional/internacional + país), vendedor, link e flag de patrocinado
- Filtros eliminatórios configuráveis pelo usuário na própria página
- Score ponderado em quatro dimensões: preço, entrega, fornecedor, marca
- Segmentação opcional do ranking (ex.: `M.2, SATA`) com comparação de preço
  restrita ao segmento
- Interface web local com o design system R Damásio
- Exportação `.xlsx` com 4 abas (Resumo, Critérios, Análise, Descartados)

### 1.3 Fora do escopo (não implementado, e não é bug)

- Qualquer loja além do Mercado Livre
- Execução de compra, reserva, carrinho ou negociação
- Autenticação, controle de acesso ou multiusuário
- Persistência: não há banco de dados. O resultado da última cotação por termo fica em
  memória apenas para permitir o download da planilha
- Histórico ou série temporal de preços
- Avaliação de adequação técnica do produto ao uso pretendido
- Execução headless / em servidor sem interface gráfica (ver seção 7)
- Cálculo de frete real, prazo de entrega em dias, tributação de importação
- Integração com ERP, Camunda ou fila de mensagens

### 1.4 Premissas assumidas

- Uso interno, em rede corporativa, por pessoas do departamento de Suprimentos
- Um usuário por estação, uso sob demanda (não é serviço de alto volume)
- O resultado é insumo para decisão humana, nunca decisão automática

---

## 2. Arquitetura

```
navegador do usuário
        │  HTTP (localhost:8760)
        ▼
    app.py ──────────────► coletor.py ──► Chrome real (Playwright)
  servidor HTTP              │                    │
  stdlib, sem framework      │                    └── lista.mercadolivre.com.br
        │                    │
        │                    └── urllib (resolve links patrocinados via 302)
        ▼
    motor.py  ──►  eliminatórios + score ponderado (Python puro)
        │
        ▼
   planilha.py ──►  .xlsx (openpyxl)
        │
        ▼
    static/  ──►  index.html + style.css + app.js
```

**Princípio de separação:** o único módulo que depende de navegador é o `coletor.py`.
`motor.py`, `planilha.py` e `app.py` são Python puro e rodam em qualquer lugar, inclusive
shell sem GUI. Isso é intencional — mantenha assim ao evoluir.

---

## 3. Estrutura de arquivos

| Arquivo | Linhas | Responsabilidade |
|---|---:|---|
| `app.py` | 149 | Servidor HTTP, rotas, serve os estáticos, cache em memória |
| `coletor.py` | 251 | Chrome via Playwright, extração dos anúncios, resolução de links patrocinados |
| `motor.py` | 199 | Eliminatórios, normalização de pesos, score, segmentação |
| `planilha.py` | 236 | Geração do `.xlsx` |
| `static/index.html` | 247 | Estrutura da página |
| `static/style.css` | 488 | Design system R Damásio (tokens + componentes) |
| `static/app.js` | 430 | Estado da UI, chamadas à API, renderização |
| `README.md` | 141 | Documentação de uso |
| `.gitignore` | 4 | Exclui perfil do navegador, cache e temporários |
| `.navegador/` | — | **Gerado em runtime.** Perfil do Chrome (~329 MB). Não versionar, não empacotar |

---

## 4. Contratos de dados

### 4.1 Anúncio (saída do `coletor.py`)

Constante `CAMPOS` em `coletor.py`. Todo extrator de loja nova deve produzir esta mesma
forma:

```python
{
  "titulo": str,
  "preco": float,            # obrigatório; anúncio sem preço é descartado na coleta
  "preco_de": float | None,  # preço anterior, quando há desconto
  "nota": float | None,      # 0–5. None = vendedor sem avaliação pública
  "vendidos": int | None,    # None = o site não informou (≠ zero). Ver seção 8.3
  "full": bool,
  "loja_oficial": bool,
  "frete_gratis": bool,
  "recondicionado": bool,
  "internacional": bool,
  "pais": str,               # "" quando nacional
  "vendedor": str,
  "url": str,
  "patrocinado": bool,
  "fonte": str               # "Mercado Livre"
}
```

### 4.2 Critérios (entrada do `motor.py`)

Dicionário `PADRAO` em `motor.py`. O que a página não enviar cai no padrão:

| Chave | Padrão | Efeito |
|---|---|---|
| `peso_preco` | 40 | peso no score (normalizado pela soma dos quatro) |
| `peso_entrega` | 20 | idem |
| `peso_fornecedor` | 25 | idem |
| `peso_marca` | 15 | idem |
| `nota_minima` | 4.5 | eliminatório |
| `vendidos_minimo` | 100 | eliminatório, **só aplicado quando o dado existe** |
| `aceita_recondicionado` | False | eliminatório |
| `exigir_full` | False | eliminatório |
| `origem` | `"qualquer"` | `qualquer` \| `nacional` \| `internacional` |
| `preco_max` | None | teto de preço |
| `deve_conter` | `""` | palavras obrigatórias no título, separadas por vírgula |
| `nao_pode_conter` | `""` | palavras proibidas no título |
| `pontos_full` | 100 | pontuação de entrega |
| `pontos_frete_gratis` | 60 | pontuação de entrega |
| `pontos_sem_frete` | 20 | pontuação de entrega |
| `marcas_preferidas` | `""` | vazio = usa `MARCAS_TI` |
| `segmentos` | `""` | ex.: `"M.2, SATA"` — gera um top 3 por segmento |

### 4.3 Ordem dos eliminatórios

**A ordem importa**: o primeiro critério que bate vira o motivo exibido ao usuário.
A sequência é produto → logística → reputação, para que o motivo reflita o filtro que a
pessoa acabou de aplicar:

1. `deve_conter` 2. `nao_pode_conter` 3. recondicionado 4. origem 5. FULL
6. teto de preço 7. sem avaliação 8. nota mínima 9. volume mínimo

### 4.4 API HTTP

| Rota | Método | Corpo | Retorno |
|---|---|---|---|
| `/` e `/style.css`, `/app.js` | GET | — | arquivos de `static/` |
| `/api/cotar` | POST | `{termo, paginas, criterios}` | JSON com `elegiveis`, `descartados`, `grupos`, `resumo`, `avisos`, `segundos` |
| `/api/planilha` | POST | `{termo}` | binário `.xlsx` (usa a última cotação daquele termo) |

---

## 5. Requisitos para executar nas estações Windows

### 5.1 Obrigatórios

| # | Requisito | Detalhe | Como verificar |
|---|---|---|---|
| 1 | **Windows 10/11** | testado em Windows 11 Pro build 26200 | `winver` |
| 2 | **Sessão de usuário interativa ativa** | **crítico** — ver 5.3 | usuário logado, tela não bloqueada |
| 3 | **Google Chrome instalado** | testado com 153.0.8010.47 | `"C:\Program Files\Google\Chrome\Application\chrome.exe"` existe |
| 4 | **Python 3.10+** | testado em 3.13.7, 64 bits | `python -V` |
| 5 | **`pip install playwright`** | 1.63.0 — traz `greenlet` e `pyee` | `python -m pip show playwright` |
| 6 | **`pip install openpyxl`** | 3.1.5 | `python -m pip show openpyxl` |
| 7 | **Acesso HTTPS ao Mercado Livre** | navegação normal | abrir `mercadolivre.com.br` no Chrome |
| 8 | **~400 MB livres em disco** | 1,6 MB de código + ~329 MB do perfil do Chrome criado no 1º uso | — |
| 9 | **Porta TCP local livre** | 8760 por padrão, configurável | `netstat -ano \| findstr 8760` |

### 5.2 Não são necessários

- **Claude, extensão Claude in Chrome ou qualquer conta de IA.** A aplicação é Python +
  Playwright dirigindo o Chrome; roda sozinha, sem conexão com nenhum serviço de IA.
  A extensão foi usada apenas na fase de descoberta, para inspecionar o DOM do Mercado
  Livre e identificar os seletores — o resultado dessa investigação está congelado no
  bloco `_JS_EXTRAIR` de `coletor.py`. Continua sendo útil para **manutenção** (quando o
  layout mudar, ou ao mapear uma loja nova), nunca para **operação**
- Privilégio de administrador (o `pip install --user` resolve)
- Download de navegador pelo Playwright (usamos o Chrome já instalado, via `channel="chrome"`)
- Servidor web, banco de dados, IIS, container, VPN ou porta liberada no firewall
  (o servidor escuta em `127.0.0.1`, só é acessível da própria máquina)
- Licença de qualquer natureza

### 5.3 O requisito que costuma ser esquecido: sessão interativa

O Chrome **não sobe** na sessão 0 do Windows. Consequências práticas:

- ❌ não funciona como Serviço do Windows
- ❌ não funciona em Tarefa Agendada marcada como "Executar estando o usuário conectado ou não"
- ✅ funciona com o usuário logado, executando a aplicação manualmente
- ✅ funciona em Tarefa Agendada marcada como **"Executar somente quando o usuário estiver conectado"**

A janela do Chrome é posicionada em `--window-position=-3000,-3000`, fora da área visível,
para não atrapalhar quem está usando a estação. Ela existe, só não aparece. Bloqueio de
tela (Win+L) não derruba a sessão e não impede a execução; **logoff derruba**.

### 5.4 Se a estação tiver Edge e não Chrome

Em `coletor.py`, trocar `channel="chrome"` por `channel="msedge"` nas duas ocorrências
(`launch_persistent_context`). Não foi testado, mas é a única mudança prevista.

### 5.5 Rede corporativa com proxy

Se o `pip` não alcançar o PyPI:

```powershell
python -m pip install --user --proxy http://usuario:senha@proxy:porta playwright openpyxl
```

O Chrome usa as configurações de proxy do Windows automaticamente — o Playwright herda.

### 5.6 Antivírus / EDR

O Playwright controla o Chrome pelo protocolo de depuração (CDP), abrindo um canal local
com o navegador. Algumas soluções de EDR classificam isso como injeção. Se a coleta falhar
com o Chrome abrindo e fechando na hora, é o primeiro suspeito — leve o executável do
Python e a pasta do projeto para exceção.

---

## 6. Instalação e execução

### 6.1 O que empacotar para transferir

**Incluir:**
```
app.py  coletor.py  motor.py  planilha.py
static/index.html  static/style.css  static/app.js
README.md  HANDOFF.md  requirements.txt
instalar.ps1  iniciar.bat  .gitignore
```

**Não incluir:**
```
.navegador/     perfil do Chrome, ~329 MB, é recriado no 1º uso e contém cookies
__pycache__/    cache do Python
_shot_*.png     capturas de tela da validação
*.xlsx          planilhas geradas em testes
```

Recomendo publicar num repositório Git interno em vez de mandar `.zip` — o `.gitignore`
já exclui tudo acima.

### 6.2 Passo a passo na estação

```powershell
# 1. copiar a pasta do projeto para a estação
# 2. abrir o PowerShell dentro dela
cd C:\caminho\para\cotacao-produtos

# 3. instalar as dependências
python -m pip install --user -r requirements.txt

# 4. subir
python app.py
```

O navegador abre sozinho em `http://localhost:8760`.
Para outra porta: `python app.py 9000`.

Ou use os scripts prontos: `instalar.ps1` (verifica tudo e instala) e `iniciar.bat`
(sobe a aplicação com duplo clique).

### 6.3 Primeira execução

A primeira cotação demora mais (~15 s): o Chrome precisa subir e criar o perfil em
`.navegador/`. As seguintes ficam em ~4 a 6 s.

### 6.4 Checklist de validação pós-instalação

| # | Teste | Resultado esperado |
|---|---|---|
| 1 | `python app.py` | mensagem "no ar em http://localhost:8760", navegador abre |
| 2 | Buscar `ssd 256gb` | 50+ anúncios analisados, top 3 renderizado em ~6 s |
| 3 | Abrir "Critérios de decisão" | painel expande, sliders somam 100% |
| 4 | Mover o peso de Preço para 90 e recotar | **o primeiro colocado muda** |
| 5 | Preencher "Segmentar por" com `M.2, SATA` | dois blocos de top 3 |
| 6 | Origem do envio = "só nacional" | descartados mostram "envio internacional (País)" |
| 7 | Expandir "anúncios descartados" | todos com motivo; todos com link "abrir" |
| 8 | Clicar "Baixar planilha" | `.xlsx` com 4 abas, links clicáveis |
| 9 | Rodar `python app.py` numa 2ª janela | recusa com "A porta 8760 já está em uso" |

Se o item 4 falhar, provavelmente há dois servidores rodando (ver 8.1).

---

## 7. Por que a arquitetura é assim (decisões a não desfazer sem medir)

### 7.1 Por que Chrome real, e não HTTP direto

Quatro abordagens testadas contra o Mercado Livre:

| Abordagem | Resultado |
|---|---|
| `urllib` / `requests` direto | bloqueado — devolve `suspicious-traffic-frontend` |
| API pública `api.mercadolibre.com/sites/MLB/search` | **HTTP 403** — exige token de aplicação |
| `chrome.exe --headless --dump-dom` | bloqueado — retorna página de erro vazia |
| Playwright `headless=True` | redireciona para `/gz/account-verification` |
| Playwright `headless=True` **com perfil já aquecido** | ainda bloqueado |
| **Playwright `headless=False` + perfil persistente** | **funciona**, ~4 s |

Conclusão: o bloqueio é do **modo headless**, não da biblioteca. Trocar Playwright por
Selenium não resolve — o ChromeDriver é mais detectável ainda (injeta variáveis `$cdc_` no
documento). Antes de tentar de novo, leia esta tabela.

### 7.2 Caminhos conhecidos para rodar sem GUI

- **Linux com display virtual:** `xvfb-run -a python app.py`. O Chrome roda em modo gráfico
  normal num framebuffer em memória, sem os sinais de headless. **Não testado** (não havia
  Linux disponível), mas é a abordagem padrão para esse cenário.
- **API oficial do Mercado Livre:** cadastrando uma aplicação no devcenter, a coleta vira
  HTTP puro — sem navegador, sem tela, e com dados que o site não entrega (reputação
  detalhada do vendedor, `logistic_type`, tributos). É a solução definitiva; depende de um
  cadastro na conta da empresa.
- **Estação como worker de fila:** a estação com GUI consome pedidos de uma fila (RabbitMQ)
  e devolve o resultado; o resto da arquitetura fica livre da dependência de interface. Foi
  discutido e não implementado.

### 7.3 Por que sem framework

`http.server` da stdlib atende o volume com folga e elimina Flask/FastAPI/uvicorn da lista
de dependências a manter e aprovar. Se um dia precisar de autenticação ou múltiplos
usuários, aí sim troque.

---

## 8. Manutenção e pontos frágeis

### 8.1 Porta duplicada no Windows

`ThreadingHTTPServer` vem com `allow_reuse_address = True`, e no Windows isso permite que
um **segundo** processo binde a mesma porta em vez de falhar. Os dois ficam de pé e as
requisições caem em qualquer um — o sintoma é "alterei o código e nada mudou".

Já está corrigido (`allow_reuse_address = False` em `app.py`), e a segunda instância agora
falha com mensagem clara. **Não reverta.** Para conferir processos:

```powershell
Get-CimInstance Win32_Process -Filter "Name='python.exe'" |
  Where-Object { $_.CommandLine -like '*app.py*' } |
  Select-Object ProcessId, CommandLine
```

### 8.2 Seletores dependentes do site

Estes são os pontos que quebram quando o Mercado Livre mexe no layout. Todos vivem no
bloco `_JS_EXTRAIR` em `coletor.py`:

| Seletor | Extrai | Sintoma se quebrar |
|---|---|---|
| `li.ui-search-layout__item` | o card do anúncio | coleta zero resultados / timeout |
| `.poly-component__title` | título e link | anúncio ignorado |
| `.poly-price__current .andes-money-amount__fraction` | preço | anúncio descartado (sem preço) |
| `.andes-visually-hidden` | nota, vendidos, frete grátis, origem | tudo cai para `None` |
| `.poly-component__review-compacted` | nota (layout alternativo) | nota `None` → tudo cortado por "sem avaliação" |
| `use[href="#poly_full"]` | selo FULL | entrega pontua errado |
| `use[href="#poly_cockade"]` | loja oficial | perde o bônus de reputação |
| `click1.mercadolivre.com.br` no href | detecta patrocinado | links de anúncio pago ficam sujos |

**Regra ao mexer aqui:** use `textContent`, nunca `innerText`. O `.andes-visually-hidden` é
escondido por CSS, e `innerText` retorna vazio em elemento não renderizado. Esse foi um bug
real durante a construção.

### 8.3 O Mercado Livre serve variantes de layout

Para a **mesma busca**, o site às vezes devolve um layout compacto em que a quantidade
vendida simplesmente não existe. O sistema trata isso explicitamente:

- `vendidos = None` (desconhecido), nunca `0`
- o eliminatório de volume mínimo **não** é aplicado nesses anúncios
- a reputação passa a ser calculada só pela nota (peso total nela)
- a página avisa quantos anúncios estão nessa condição

**Não "simplifique" isso para `vendidos = 0`.** Com o filtro padrão de 100 unidades, isso
derruba a lista inteira.

### 8.4 Links patrocinados

Anúncio patrocinado não expõe a URL do produto no DOM — só um link de rastreamento
`click1.mercadolivre.com.br`, que responde **302** para o anúncio real. O coletor resolve
esses redirecionamentos em paralelo (8 threads, ~0,3 s cada) e grava a URL limpa; se algum
falhar, mantém o link de rastreamento, que abre o mesmo anúncio. Nenhum item fica sem link.

### 8.5 Como diagnosticar quando a coleta parar

1. Rode o coletor isolado e veja o que volta:
   ```powershell
   python -c "import coletor; a,v = coletor.coletar('ssd 256gb'); print(len(a), v); print(a[0] if a else '')"
   ```
2. Se vier zero, rode com `headless=False` e **sem** `--window-position` (comente a linha)
   para ver a janela do Chrome e o que o site está mostrando.
3. Se aparecer verificação de conta ou captcha: resolva **manualmente uma vez** nessa
   janela. O perfil `.navegador/` guarda o cookie e as execuções seguintes voltam a passar.
4. Se a página carrega mas a extração vem vazia: os seletores mudaram (seção 8.2).

---

## 9. Riscos assumidos

| Risco | Impacto | Mitigação |
|---|---|---|
| Mercado Livre muda o layout | coleta para | seletores centralizados num único bloco; seção 8.2 |
| Mercado Livre endurece o anti-bot | coleta para | migrar para a API oficial (7.2) |
| Estação deslogada | aplicação indisponível | máquina dedicada com política de não-logoff |
| Preço muda entre cotação e compra | valor errado na planilha | aviso impresso na planilha e na página |
| Termos de uso do site | jurídico | uso interno, volume baixo, sem revenda de dados. A API oficial elimina a questão |

---

## 10. Backlog sugerido, em ordem de valor

1. **Calibrar com itens reais** de categorias diferentes — os cortes padrão foram
   ajustados olhando TI; em outras categorias `deve_conter` e `nota_minima` mudam de sentido
2. **Kabum, Pichau, Terabyte** — a estrutura já prevê: escrever
   `buscar_<loja>(page, termo, paginas)` devolvendo o contrato da seção 4.1 e registrar em
   `FONTES`. Motor, página e planilha não mudam. Esperar a mesma barreira anti-bot
3. **Worker de fila (RabbitMQ)** — desacopla o resto da arquitetura da dependência de GUI e
   permite disparar cotação de outros sistemas. Usar `prefetch_count=1`, ack só no fim, DLQ
   com contador, e manter o browser vivo entre mensagens
4. **API oficial do Mercado Livre** — elimina a maior limitação do projeto
5. **Histórico de preços** — só depois que o uso estabilizar

---

## 11. Contato

Construído e validado em 28/09/2026 com Antonio Silva (`antonio.silva@rdamasio.com.br`),
que conhece as decisões e o contexto de negócio. A política de pesos e cortes padrão é
decisão do departamento de Suprimentos, não técnica — qualquer mudança nos valores de
`PADRAO` em `motor.py` deve passar por eles.
