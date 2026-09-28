# HELP-AGENT Orçamentos

Monta o impresso oficial de orçamento da R Damásio a partir de prints/PDFs de chamados e de orçamentos de
fornecedores: a IA (Gemini) extrai os dados, o usuário revisa e o sistema preenche o template PDF da empresa
e anexa os documentos originais. Migração do HTML único `legacy/HELP-AGENT-ORCAMENTO-v3_5.html` —
escopo e plano em [docs/ESCOPO-E-PLANO-MIGRACAO.md](docs/ESCOPO-E-PLANO-MIGRACAO.md).

```
backend/                Spring Boot 4.1 · Java 21 · PDFBox · Flyway · PostgreSQL (H2 no perfil local)
frontend/               Angular 22 · standalone + signals · zoneless
tools/                  extrair_legado.py (lojas e templates do HTML legado) · avaliar_extracao.py · paridade_cotacao.py
legacy/                 o HTML v3.5 original e o piloto em Python da cotação (com o HANDOFF), só para referência
docs/                   escopo e plano de migração · guia de manutenção
iniciar-helpagent.bat   sobe backend + frontend para uso na rede
parar-helpagent.bat     derruba os dois
```

**Vai mexer no código?** Leia primeiro [docs/MANUTENCAO.md](docs/MANUTENCAO.md): fluxo, mapa do código,
receitas (trocar prompt, loja, template, marca…) e armadilhas conhecidas.

## Subir para o helpdesk (Windows)

Dar dois cliques em **`iniciar-helpagent.bat`**. Ele:
1. confere Java e Node, e instala as dependências do frontend na primeira vez;
2. abre a janela **HelpAgent - backend**, que escuta só em `127.0.0.1:8080`, e espera ela responder;
3. abre a janela **HelpAgent - frontend** na porta **80** da rede, com proxy de `/api` para o backend;
4. abre o navegador em http://localhost.

As duas janelas precisam ficar abertas. Para parar, use **`parar-helpagent.bat`**. A porta e o nome ficam
no topo do `.bat` (`PORTA_WEB`, `ENDERECO`).

Perfil `local`: H2 em arquivo (`backend/dados/`), API sem autenticação. Isso é aceitável na rede
segmentada do helpdesk, mas não expor para fora dela.

### Endereço por nome (sem digitar IP)

O nome recomendado é **`http://helpagent.rdamasio.com.br`**, um subdomínio do domínio da empresa.
Evitamos `helpagent.com.br`: é um domínio público **livre para registro**. Se alguém registrar, quem estiver
fora da VPN, ou com o "DNS seguro" (DoH) do Chrome/Edge ligado, que ignora o DNS interno, cai no site dessa pessoa.

Para funcionar para todos, a TI cria no **DNS interno** um registro **A**
`helpagent.rdamasio.com.br → 10.4.31.150` (IP da máquina que roda o `.bat`; de preferência fixo ou reservado no DHCP).
Para testar numa máquina só, antes do DNS, dá para adicionar a linha
`10.4.31.150  helpagent.rdamasio.com.br` no arquivo `C:\Windows\System32\drivers\etc\hosts`, como administrador.

O servidor do Angular só atende os nomes listados em `allowedHosts` (`frontend/angular.json`) e responde
403 para os demais. Nome novo precisa entrar ali.

### Rodar manualmente (desenvolvimento)

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

```bash
cd frontend && npm install && npm start
```

Abrir http://localhost:4200. Para escutar na rede: `npm run start:rede`.

**Chave do Gemini**: nunca no código. Localmente, em `backend/config/application-local.yml` (fora do git):

```yaml
helpagent:
  gemini:
    api-key: SUA-CHAVE
```

Em produção: variável `GEMINI_API_KEY`.

### Particularidades desta máquina

- **TEMP com nome curto 8.3** (`ELUAN~1.JES`) quebra o socket AF_UNIX que o NIO da JVM usa internamente, e o
  Tomcat não sobe (`Unable to establish loopback connection`). Contorno:
  `-Dspring-boot.run.jvmArguments=-Djdk.net.unixdomain.tmpdir=C:/Users/<você>/.helpagent-tmp` (criar a pasta antes).
- **registry.npmjs.org bloqueado na rede**: `npm install --registry=https://registry.yarnpkg.com`.

## Cotação em lojas online

Tela **Cotação**: digita o produto e escolhe onde buscar. O servidor coleta os anúncios das lojas ao mesmo tempo
e devolve um ranking por preço, entrega, fornecedor e marca, conforme os critérios do painel, além do melhor
anúncio de cada loja lado a lado. Também exporta uma planilha `.xlsx`. Mudar um critério reordena na hora, sem
nova busca. O preço é **referência**: confira na loja antes de comprar.

| Nível | Lojas | Tempo típico |
|---|---|---|
| 1 · Varejo de TI (padrão) | Kabum, Pichau, Terabyte | ~20 s |
| 2 · + Marketplaces | + Amazon, Mercado Livre | — |
| 3 · + Fabricantes | + Dell, Lenovo | 25–40 s |

Também dá para marcar loja por loja.

Requisitos na máquina que roda o backend:
- **Google Chrome instalado** (ou Edge, com `COTACAO_CANAL=msedge`);
- **usuário logado**: o backend não pode rodar como Serviço do Windows.

A primeira cotação depois de subir o backend é mais lenta, porque o Chrome cria o perfil em
`backend/dados/navegador/`. Lojas, fontes dos dados, diagnóstico e como acrescentar uma loja:
[docs/MANUTENCAO.md](docs/MANUTENCAO.md) §7.

## Visual

**Design System R Damásio**: fundo claro, marinho e vermelho, fontes Archivo e JetBrains Mono. O seletor no
canto do cabeçalho troca entre **Damásio Motopeças** (marinho) e **TD Motopeças** (vermelho da TD), com as
logos de cada uma. A escolha é de cada pessoa e fica salva no navegador. É só visual: o impresso usado
continua sendo o da loja escolhida.

## API

| Método | Rota | O quê |
|---|---|---|
| GET | `/api/lojas?busca=` · `?incluirInativas=true` · `/api/lojas/{numero}` | cadastro de lojas ("023" = "23") |
| POST · PUT `/{numero}` · PATCH `/{numero}/ativa` | `/api/lojas` | cadastrar, editar, desativar/reativar (tela **Lojas**) |
| GET | `/api/historico/por-chamado?numeros=` | orçamentos já gerados para os chamados (aviso de duplicidade) |
| GET | `/api/parametros` | máx. de itens, requerente/gestor padrão |
| POST | `/api/extracoes` (multipart: `modo`, `chamados[]`, `orcamentos[]`) | leitura por IA + avisos A2/C2 |
| POST | `/api/orcamentos` (multipart: `dados` JSON + arquivos) | valida (A1), gera e devolve o PDF, grava no histórico |
| GET/DELETE | `/api/historico?modo=&busca=&pagina=&tamanho=` · `/api/historico/{id}/pdf` | histórico central, filtrável por tipo |
| GET | `/api/historico/contagem?busca=` | quantos de cada tipo (`REQUISICAO`, `OPEX`, `CAPEX`, `TODOS`), para as abas |
| GET | `/api/historico/exportar` | backup JSON (versão 3) com todos os orçamentos, itens e PDFs em base64 |
| POST | `/api/historico/importar` (multipart: `arquivo`) | adiciona um backup — deste sistema ou do HTML v3.5; repetidos (mesmo título + data) são ignorados |
| GET | `/api/cotacao/estado` | `{ocupado, padrao, maxPaginas, lojas, niveis}`: fila, critérios padrão, lojas e níveis de busca |
| POST | `/api/cotacao` `{termo, paginas, fontes, criterios}` | coleta nas lojas (`fontes` vazio = nível 1) e devolve o ranking com um `id` e `porFonte` |
| POST | `/api/cotacao/{id}/reavaliar` `{criterios}` | mesmos anúncios, critérios novos (sem nova coleta) |
| GET | `/api/cotacao/{id}/planilha` | `.xlsx` com Resumo, Critérios, Análise e Descartados |

**Antes de atualizar a versão ou trocar de banco**: Histórico → *Exportar JSON*. Depois: *Importar JSON*.

Erros vêm como `ProblemDetail` (RFC 9457) com a lista `problemas`.

## Próximos passos (ver plano, §7)

- Testes automatizados (backend: Dinheiro, ValidadorOrcamento, ExtratorIa com respostas simuladas, PDF por template; frontend: componentes).
- Login corporativo (OIDC) — decisão em aberto; hoje `helpagent.seguranca.habilitada=true` exige issuer configurado.
- Telas de administração (lojas, templates, parâmetros).
- Servidor fixo (não depender do PC de alguém ligado) com PostgreSQL.
- Dockerfile/compose e CI.
