# HELP-AGENT Orçamentos — CLAUDE.md

Gera o impresso oficial de orçamento da R Damásio: IA (Gemini) lê chamado + orçamentos de fornecedor,
usuário revisa, sistema preenche o template PDF da empresa e anexa os originais. Uso compartilhado pelo helpdesk.

- **Documentação geral (ponto de partida):** [docs/DOCUMENTACAO.md](docs/DOCUMENTACAO.md)
- Visão geral e como subir: [README.md](README.md)
- **Antes de mudar código**: [docs/MANUTENCAO.md](docs/MANUTENCAO.md) — fluxo, mapa, receitas, armadilhas
- Escopo e plano de migração do HTML v3.5: [docs/ESCOPO-E-PLANO-MIGRACAO.md](docs/ESCOPO-E-PLANO-MIGRACAO.md)
- Integração com uma aplicação maior (contrato, rastreio, saúde, métricas, config): [docs/INTEGRACAO.md](docs/INTEGRACAO.md)

## Regras de trabalho neste repositório

- **Manter o código documentado para outras pessoas.** Toda classe/arquivo novo começa com um comentário
  curto dizendo seu papel; comentários explicam o *porquê* (regra de negócio, armadilha, decisão). Mudou
  comportamento, fluxo ou armadilha → atualizar `docs/MANUTENCAO.md` e, se for de uso, o README.
- Português em nomes, mensagens e comentários.
- Regras A1/A2/A4/C2 (ver MANUTENCAO §1) valem no servidor; o frontend só mostra.
- Segredo nunca em arquivo versionado (`backend/config/` está no `.gitignore`).
- Schema só por migration Flyway nova, em SQL portável (testes e perfil local usam H2).
- Visual só por tokens em `frontend/src/styles.scss`; componentes não têm CSS próprio.

## Verificação

```bash
cd backend && ./mvnw -q compile
```

```bash
cd frontend && npx ng build
```

Nesta máquina: JVM precisa de `-Djdk.net.unixdomain.tmpdir=<pasta sem ~>` para abrir sockets (o `.bat`
já passa); `npm install` só pelo espelho `--registry=https://registry.yarnpkg.com`.
