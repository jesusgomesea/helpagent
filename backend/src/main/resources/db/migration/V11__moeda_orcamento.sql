-- Moeda do orçamento (15/10/2026): orçamento de fornecedor em dólar ou euro sai na moeda original, sem conversão.
-- Tudo o que já existe é em real. Valores continuam em orcamento.total / orcamento_item.*, na moeda desta coluna.
ALTER TABLE orcamento ADD COLUMN moeda VARCHAR(3) DEFAULT 'BRL' NOT NULL;
