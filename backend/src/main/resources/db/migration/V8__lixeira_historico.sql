-- Lixeira do histórico (30/09/2026): "apagar" deixa de ser definitivo. O orçamento vai para a lixeira (some das
-- abas, do aviso de chamado já orçado e do backup), pode ser restaurado por 30 dias e depois é apagado de vez,
-- com o PDF (LixeiraHistorico). Excluir de vez antes disso só pela própria lixeira.
ALTER TABLE orcamento ADD COLUMN excluido_em TIMESTAMP WITH TIME ZONE;
ALTER TABLE orcamento ADD COLUMN excluido_por VARCHAR(120);

CREATE INDEX ix_orcamento_excluido ON orcamento (excluido_em);
