package br.com.rdamasio.helpagent.orcamento;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Acesso ao histórico de orçamentos gerados. A busca da tela (abas, filtros, lixeira) é por
 * {@code FiltroHistorico} ({@link JpaSpecificationExecutor}); as consultas abaixo ignoram o que está na lixeira.
 */
public interface OrcamentoRepository extends JpaRepository<Orcamento, Long>, JpaSpecificationExecutor<Orcamento> {

    /** Backup: só os ativos (a lixeira não viaja para outro banco). */
    @Query("select distinct o from Orcamento o join fetch o.loja left join fetch o.itens where o.excluidoEm is null order by o.criadoEm")
    List<Orcamento> findAllByOrderByCriadoEmAsc();

    /** Candidatos para o aviso de chamado já orçado; a confirmação por número exato é em ChamadosJaOrcados. */
    @Query("select o from Orcamento o join fetch o.loja where o.excluidoEm is null and o.chamadoNum like concat('%', :numero, '%') order by o.criadoEm desc")
    List<Orcamento> buscarPorChamadoContendo(@Param("numero") String numero);

    /** Na lixeira há mais tempo que o prazo: apagar de vez. */
    List<Orcamento> findByExcluidoEmBefore(Instant limite);

    /** Chave de duplicidade na importação de backup. */
    boolean existsByTituloAndCriadoEm(String titulo, Instant criadoEm);
}
