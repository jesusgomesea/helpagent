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

## Decisões em aberto

- Como o backend entra (serviço próprio × módulo de um backend maior) e como o frontend entra (sub-rota de um
  portal, micro-frontend, telas num Angular maior).
