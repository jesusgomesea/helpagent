-- Três tipos de requisição (28/09/2026). Até aqui "OPEX" era o fluxo normal com chamado; ele passa a se
-- chamar REQUISICAO, e OPEX vira um tipo próprio (observação "OPEX. # chamado ..."), usado daqui em diante.
-- Backup do histórico feito antes desta migration: backend/dados/backups/historico-antes-dos-3-tipos-2026-09-28.json
UPDATE orcamento SET modo = 'REQUISICAO' WHERE modo = 'OPEX';

-- As abas do histórico filtram por tipo.
CREATE INDEX ix_orcamento_modo ON orcamento (modo, criado_em DESC);
