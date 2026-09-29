# Integração com uma aplicação maior

Como este sistema foi preparado para entrar numa aplicação maior (portal, gateway, outro backend) **sem ainda ter
decidido como** (29/09/2026: "depois vemos isso"). Tudo aqui vale para qualquer caminho: serviço próprio atrás de
um gateway, módulo de um backend maior, telas num portal. O que depende da escolha está em "Decisões em aberto".

Para o funcionamento interno, veja [MANUTENCAO.md](MANUTENCAO.md); para subir e usar, o [README](../README.md).

## 1. Contrato da API

- **OpenAPI gerado do código**: `GET /v3/api-docs` (JSON) e a tela `/swagger-ui.html`. É o próprio springdoc
  lendo os controllers (`config/OpenApiConfig`): mudou um controller, o contrato muda junto. `API_DOCS=false` desliga.
  Dá para gerar o cliente da aplicação maior a partir dele (openapi-generator, orval...).
- Só `/api/**` entra no contrato. Todas as rotas estão também na tabela "API" do README.
- **Erros**: sempre `ProblemDetail` (RFC 9457) com `problemas` (lista para mostrar ao usuário) e `idRequisicao`.
- Com a segurança ligada, o contrato declara o Bearer JWT (esquema `oidc`).

## 2. Rastreio entre sistemas

- Cabeçalho **`X-Request-Id`**: se o gateway/portal mandar, o sistema usa o mesmo id; senão gera um. Volta na
  resposta e no corpo de erro (`idRequisicao`). Aceita até 64 caracteres `[A-Za-z0-9._-]`; outro valor é trocado,
  para não forjar linhas de log (`common/IdRequisicaoFiltro`).
- Toda linha de log sai com `[req=<id>]`, inclusive as das chamadas ao Gemini, que rodam em outras threads.

## 3. Saúde, versão e métricas (Actuator)

| Rota | Para quê | Aberta com login ligado? |
|---|---|---|
| `/actuator/health` | saúde geral: banco, disco e `ia` (chave e situação de cada modelo da cadeia) | sim |
| `/actuator/health/liveness` · `/readiness` | sondas do orquestrador (Kubernetes, etc.) | sim |
| `/actuator/info` | versão e hora do build, versão do Java | sim |
| `/actuator/prometheus` | métricas; **só existe** se `ACTUATOR_EXPOR` incluir `prometheus` | sim — deixe numa rede interna |

- O componente `ia` nunca derruba a saúde: sem chave ou sem modelo com cota fica `UNKNOWN`, porque o sistema
  continua funcionando sem IA (orçamento à mão, cotação).
- `ACTUATOR_DETALHES=always` mostra os detalhes da saúde (padrão: `never`; no perfil local, `always`).
- Métricas próprias (além das do Spring: HTTP, JVM, banco):

| Métrica | Tags | O que é |
|---|---|---|
| `helpagent_ia_requisicoes_total` | `modelo`, `ocorrencia` (OK, SOBRECARGA, COTA_MINUTO, COTA_DIA, INDISPONIVEL, ERRO), `papel` | requisições enviadas ao Gemini |
| `helpagent_ia_tokens_total` | `modelo`, `tipo` (entrada, saida) | tokens contados pelo Google |
| `helpagent_ia_duracao_seconds` | `modelo` | tempo de cada chamada |
| `helpagent_ia_cota_dia_usada` · `_limite` | `modelo` | uso e limite do dia do Google, por modelo da cadeia |

Alerta sugerido: `helpagent_ia_cota_dia_usada / helpagent_ia_cota_dia_limite > 0.8` no primeiro modelo da cadeia.

## 4. Configuração por variável de ambiente

| Variável | Padrão | O quê |
|---|---|---|
| `PORT` | 8080 | porta HTTP |
| `DB_URL` · `DB_USER` · `DB_PASSWORD` | PostgreSQL local | banco (Flyway cria/atualiza o schema) |
| `GEMINI_API_KEY` | — | chave da IA (nunca em arquivo versionado) |
| `GEMINI_MODELO_FORCADO` | — | usa um modelo só, sem cadeia (comparar modelos) |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | — | provedor OIDC; obrigatório com a segurança ligada |
| `CORS_ORIGENS` | `http://localhost:4200` | origens do frontend quando servido de outro domínio |
| `ACTUATOR_EXPOR` | `health,info` | endpoints do Actuator expostos (ex.: `health,info,prometheus`) |
| `ACTUATOR_DETALHES` | `never` | detalhes da saúde |
| `API_DOCS` | `true` | contrato OpenAPI e Swagger UI |
| `RECURSO_IA` | `true` | leitura por IA e painel "Uso da IA" (§5) |
| `RECURSO_COTACAO` | `true` | cotação em lojas online e orçamento por cotação (§5) |

## 5. Recursos que dependem do ambiente

O núcleo (orçamento, impresso PDF, histórico, lojas) roda em qualquer servidor Java 21 com PostgreSQL. Dois
recursos não:

| Recurso | Depende de | Se o servidor não tiver |
|---|---|---|
| **Cotação** (`RECURSO_COTACAO`) | um **Chrome com janela**, numa sessão de desktop: o Mercado Livre bloqueia navegador headless e o Chromium do Playwright (MANUTENCAO §7), e o perfil do Chrome fica em disco | desligue; ou rode uma instância só para a cotação numa máquina Windows com usuário logado |
| **IA** (`RECURSO_IA`) | chave do Gemini e saída HTTPS para `generativelanguage.googleapis.com` | desligue: o atendente preenche o orçamento à mão |

Desligado (`config/Recursos`, `config/GuardaRecursos`):
- as rotas do recurso respondem **503** com `problemas` explicando (não 500 lá de dentro);
- `GET /api/parametros` devolve `recursos: {ia, cotacao}`, e o frontend esconde o link do menu, barra a rota
  (volta ao início) e, sem IA, oferece "Preencher manualmente" na tela de novo orçamento.

## 6. Frontend embutível

O mesmo build serve sozinho, atrás de um gateway ou dentro de um portal, **sem recompilar**:

- **`config.json`** (ao lado do `index.html`, lido antes da primeira tela; `core/configuracao.ts`):
  ```json
  { "apiBase": "", "enviarCookies": false }
  ```
  `apiBase` vazio = API na mesma origem (proxy). Com valor (ex.: `https://gateway.empresa/helpagent`), toda chamada
  `/api/...` vai para lá. `enviarCookies: true` manda os cookies (SSO por cookie no gateway). Sem o arquivo, vale
  o padrão.
- **Token do portal**: a página hospedeira define, antes de carregar o sistema,
  `window.helpAgent = { token: () => tokenAtual() }` (pode devolver Promise). Toda chamada sai com
  `Authorization: Bearer <token>` — o que o backend exige com a segurança ligada. Testado em 29/09 com um token de
  exemplo: o cabeçalho sai em todas as chamadas.
- **`X-Request-Id`**: o navegador gera um por chamada; em erro 5xx a tela mostra "Código para o suporte: ...", o
  mesmo id das linhas de log daquela chamada no servidor.
- **Imagens da API** (prints da cotação) são carregadas pelo HttpClient (`core/imagem-api.ts`, `<img haSrcApi>`), não
  por `<img src="/api/...">` — senão iriam sem token e sem `apiBase` e quebrariam no portal.
- **Sub-caminho**: para servir em `https://portal/helpagent/`, gere com `npx ng build --base-href /helpagent/`.
  Rotas, logos e o `config.json` seguem o `<base href>`; as chamadas `/api` seguem o `apiBase` (ou um proxy de
  `/api` no mesmo servidor).
- O visual vem todo de `src/styles.scss` (tokens `--rd-*` e semânticos); os componentes não têm CSS próprio. Dentro
  de um app maior, os estilos globais podem colidir — é um dos pontos da decisão em aberto abaixo.

## 7. Contêineres e CI

| Arquivo | O quê |
|---|---|
| `backend/Dockerfile` | compila com Maven e roda no JRE 21 como usuário sem privilégio; `/app/dados` (PDFs, prints) é volume; **`RECURSO_COTACAO=false`** (não há Chrome nem desktop na imagem) |
| `frontend/Dockerfile` + `nginx.conf.template` | compila o Angular (`--build-arg BASE_HREF=/helpagent/` para sub-caminho) e serve com nginx, que encaminha `/api` para `BACKEND_URL` (mesma origem, sem CORS), aceita envio de até 250 MB e espera a IA até 120 s |
| `docker-compose.yml` | PostgreSQL + backend + frontend em `http://localhost:8081`, para testar a integração; login desligado **só** ali |
| `.github/workflows/ci.yml` | a cada push: testes do backend (inclusive a aplicação subindo inteira e as migrations num **PostgreSQL de verdade**, `MigracoesPostgresTest`), build do frontend e das duas imagens |

```bash
GEMINI_API_KEY=... docker compose up --build
```

A máquina de desenvolvimento atual não tem Docker: as imagens são montadas e conferidas pelo CI no GitHub.

A cotação continua precisando de uma máquina Windows com usuário logado (o `iniciar-helpagent.bat` de hoje). Numa
integração, dá para manter uma instância só para ela e desligá-la nas demais.

## Decisões em aberto

- Como o backend entra (serviço próprio × módulo de um backend maior) e como o frontend entra (sub-rota de um
  portal, micro-frontend, telas num Angular maior).
