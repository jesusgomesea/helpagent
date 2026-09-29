-- Orçamento por cotação (29/09/2026): o impresso pode nascer da cotação em lojas online, com os prints das
-- páginas de produto anexados em vez dos orçamentos de fornecedor. Guardamos de onde veio cada linha para a
-- auditoria saber em que loja, em que página e quando o preço foi coletado.

-- DOCUMENTOS = fluxo de sempre (prints/PDFs de fornecedor, com ou sem IA) · COTACAO = montado pela cotação
ALTER TABLE orcamento ADD COLUMN origem VARCHAR(12) DEFAULT 'DOCUMENTOS' NOT NULL;

-- Só preenchidos nas linhas vindas da cotação: loja fornecedora, página do produto, momento do print.
ALTER TABLE orcamento_item ADD COLUMN fornecedor VARCHAR(60);
ALTER TABLE orcamento_item ADD COLUMN url VARCHAR(1000);
ALTER TABLE orcamento_item ADD COLUMN coletado_em TIMESTAMP WITH TIME ZONE;
