package br.com.rdamasio.helpagent.historico;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import br.com.rdamasio.helpagent.loja.Loja;
import br.com.rdamasio.helpagent.orcamento.ModoAquisicao;
import br.com.rdamasio.helpagent.orcamento.Orcamento;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

/**
 * O que a tela do histórico pediu: busca, aba (tipo de requisição) e lixeira. Cada campo nulo não filtra. Vira uma
 * {@link Specification} em vez de JPQL fixo porque são vários filtros opcionais — escrever todas as combinações à
 * mão, na consulta e na contagem, era o que tornava o repositório difícil de mexer.
 *
 * @param termo   texto livre: título, loja (nome/número), empresa, chamado — a mesma busca da v3.5
 * @param modo    aba do tipo de requisição; null = todos
 * @param lixeira true = só o que está na lixeira; false = só os ativos
 */
public record FiltroHistorico(String termo, ModoAquisicao modo, boolean lixeira) {

    /** O mesmo filtro em outra aba (as contagens das abas usam os demais filtros iguais). */
    public FiltroHistorico comModo(ModoAquisicao outro) {
        return new FiltroHistorico(termo, outro, lixeira);
    }

    public FiltroHistorico naLixeira(boolean sim) {
        return new FiltroHistorico(termo, modo, sim);
    }

    public Specification<Orcamento> especificacao() {
        return (raiz, consulta, cb) -> {
            Join<Orcamento, Loja> loja;
            // na listagem, traz a loja junto (1 consulta em vez de 1 por linha); na contagem, fetch não é permitido
            if (consulta != null && consulta.getResultType() != Long.class && consulta.getResultType() != long.class) {
                // o Fetch do Hibernate também é um Join: dá para filtrar pela loja sem juntar a tabela duas vezes
                Fetch<Orcamento, Loja> fetch = raiz.fetch("loja", JoinType.INNER);
                @SuppressWarnings("unchecked")
                Join<Orcamento, Loja> j = (Join<Orcamento, Loja>) fetch;
                loja = j;
            } else {
                loja = raiz.join("loja", JoinType.INNER);
            }
            List<Predicate> p = new ArrayList<>();
            p.add(lixeira ? cb.isNotNull(raiz.get("excluidoEm")) : cb.isNull(raiz.get("excluidoEm")));
            if (modo != null) p.add(cb.equal(raiz.get("modo"), modo));
            if (termo != null && !termo.isBlank()) {
                String like = "%" + termo.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(
                        cb.like(cb.lower(raiz.get("titulo")), like),
                        cb.like(cb.lower(loja.get("nome")), like),
                        cb.like(cb.lower(loja.get("empresa")), like),
                        cb.like(cb.lower(cb.coalesce(raiz.get("chamadoNum"), "")), like),
                        cb.like(loja.get("numero").as(String.class), like)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }
}
