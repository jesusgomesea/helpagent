package br.com.rdamasio.helpagent.orcamento;

import java.math.BigDecimal;
import java.util.List;

/**
 * Evento: um orçamento foi gerado e gravado. Publicado dentro da transação; quem ouve com
 * {@code @TransactionalEventListener} só age depois do commit (o orçamento existe de fato). Hoje o {@code qualidadeia}
 * compara com a leitura da IA de onde ele veio.
 *
 * @param idLeitura    leitura da IA que preencheu a revisão (null = preenchido à mão ou por cotação)
 * @param total        total geral calculado no servidor
 * @param fornecedores nome final do fornecedor de cada linha impressa (null = sem fornecedor), na ordem das linhas
 */
public record OrcamentoGerado(Long orcamentoId, String idLeitura, GerarOrcamentoRequest dados, BigDecimal total,
        List<String> fornecedores) {
}
