package br.com.rdamasio.helpagent.historico;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import br.com.rdamasio.helpagent.loja.Loja;
import br.com.rdamasio.helpagent.orcamento.ModoAquisicao;
import br.com.rdamasio.helpagent.orcamento.Orcamento;
import br.com.rdamasio.helpagent.orcamento.OrcamentoItem;
import br.com.rdamasio.helpagent.orcamento.OrigemOrcamento;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * O que a tela do histórico pediu: busca, aba (tipo de requisição), lixeira e os filtros do painel "Filtros".
 * Cada campo nulo não filtra. Vira uma {@link Specification} em vez de JPQL fixo porque são vários filtros
 * opcionais — escrever todas as combinações à mão, na consulta e na contagem, era o que tornava o repositório
 * difícil de mexer.
 *
 * @param termo      texto livre: título, loja (nome/número), empresa, chamado — a mesma busca da v3.5
 * @param modo       aba do tipo de requisição; null = todos
 * @param lixeira    true = só o que está na lixeira; false = só os ativos
 * @param de         data de emissão a partir de (inclusive)
 * @param ate        data de emissão até (inclusive)
 * @param loja       número da loja
 * @param valorMin   total a partir de (inclusive)
 * @param valorMax   total até (inclusive)
 * @param origem     DOCUMENTOS (fluxo de sempre) ou COTACAO (montado pela cotação)
 * @param fornecedor id do fornecedor: orçamentos com alguma linha dele
 */
public record FiltroHistorico(String termo, ModoAquisicao modo, boolean lixeira, LocalDate de, LocalDate ate,
        Integer loja, BigDecimal valorMin, BigDecimal valorMax, OrigemOrcamento origem, Long fornecedor) {

    /** Só busca, aba e lixeira (sem os filtros do painel). */
    public FiltroHistorico(String termo, ModoAquisicao modo, boolean lixeira) {
        this(termo, modo, lixeira, null, null, null, null, null, null, null);
    }

    /** O mesmo filtro em outra aba (as contagens das abas usam os demais filtros iguais). */
    public FiltroHistorico comModo(ModoAquisicao outro) {
        return new FiltroHistorico(termo, outro, lixeira, de, ate, loja, valorMin, valorMax, origem, fornecedor);
    }

    public FiltroHistorico naLixeira(boolean sim) {
        return new FiltroHistorico(termo, modo, sim, de, ate, loja, valorMin, valorMax, origem, fornecedor);
    }

    public Specification<Orcamento> especificacao() {
        return (raiz, consulta, cb) -> {
            Join<Orcamento, Loja> lojaJ;
            // na listagem, traz a loja junto (1 consulta em vez de 1 por linha); na contagem, fetch não é permitido
            if (consulta != null && consulta.getResultType() != Long.class && consulta.getResultType() != long.class) {
                // o Fetch do Hibernate também é um Join: dá para filtrar pela loja sem juntar a tabela duas vezes
                Fetch<Orcamento, Loja> fetch = raiz.fetch("loja", JoinType.INNER);
                @SuppressWarnings("unchecked")
                Join<Orcamento, Loja> j = (Join<Orcamento, Loja>) fetch;
                lojaJ = j;
            } else {
                lojaJ = raiz.join("loja", JoinType.INNER);
            }
            List<Predicate> p = new ArrayList<>();
            p.add(lixeira ? cb.isNotNull(raiz.get("excluidoEm")) : cb.isNull(raiz.get("excluidoEm")));
            if (modo != null) p.add(cb.equal(raiz.get("modo"), modo));
            if (termo != null && !termo.isBlank()) {
                String like = "%" + termo.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(
                        cb.like(cb.lower(raiz.get("titulo")), like),
                        cb.like(cb.lower(lojaJ.get("nome")), like),
                        cb.like(cb.lower(lojaJ.get("empresa")), like),
                        cb.like(cb.lower(cb.coalesce(raiz.get("chamadoNum"), "")), like),
                        cb.like(lojaJ.get("numero").as(String.class), like)));
            }
            if (de != null) p.add(cb.greaterThanOrEqualTo(raiz.get("dataEmissao"), de));
            if (ate != null) p.add(cb.lessThanOrEqualTo(raiz.get("dataEmissao"), ate));
            if (loja != null) p.add(cb.equal(lojaJ.get("numero"), loja));
            if (valorMin != null) p.add(cb.greaterThanOrEqualTo(raiz.get("total"), valorMin));
            if (valorMax != null) p.add(cb.lessThanOrEqualTo(raiz.get("total"), valorMax));
            if (origem != null) p.add(cb.equal(raiz.get("origem"), origem));
            if (fornecedor != null) {
                // existe alguma linha do orçamento com esse fornecedor (subconsulta: não duplica o orçamento na lista)
                Subquery<Long> sub = consulta.subquery(Long.class);
                Root<OrcamentoItem> item = sub.from(OrcamentoItem.class);
                sub.select(item.get("id")).where(cb.equal(item.get("orcamento"), raiz),
                        cb.equal(item.get("fornecedorRef").get("id"), fornecedor));
                p.add(cb.exists(sub));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }
}
