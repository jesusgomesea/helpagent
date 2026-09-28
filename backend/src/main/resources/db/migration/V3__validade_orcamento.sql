-- "Válido até" do impresso (campo que existia no template e a v3.5 nunca preenchia). Opcional:
-- prints de e-commerce costumam não ter validade.
ALTER TABLE orcamento ADD COLUMN validade DATE;
