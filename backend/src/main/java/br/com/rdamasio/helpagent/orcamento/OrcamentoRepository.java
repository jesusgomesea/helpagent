package br.com.rdamasio.helpagent.orcamento;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Acesso ao histórico de orçamentos gerados.
 */
public interface OrcamentoRepository extends JpaRepository<Orcamento, Long> {

    @Query("select distinct o from Orcamento o join fetch o.loja left join fetch o.itens order by o.criadoEm")
    List<Orcamento> findAllByOrderByCriadoEmAsc();

    /** Candidatos para o aviso de chamado já orçado; a confirmação por número exato é em ChamadosJaOrcados. */
    @Query("select o from Orcamento o join fetch o.loja where o.chamadoNum like concat('%', :numero, '%') order by o.criadoEm desc")
    List<Orcamento> buscarPorChamadoContendo(@Param("numero") String numero);

    /** Chave de duplicidade na importação de backup. */
    boolean existsByTituloAndCriadoEm(String titulo, Instant criadoEm);

    /**
     * Busca do histórico: termo (título, loja por nome/número, chamado, empresa — a mesma da v3.5) e,
     * opcionalmente, o tipo de requisição da aba escolhida. {@code modo} null = todos os tipos.
     */
    @Query(value = """
            select o from Orcamento o join fetch o.loja l
            where (:modo is null or o.modo = :modo)
              and (:termo is null
                or lower(o.titulo) like :termo
                or lower(l.nome) like :termo
                or lower(l.empresa) like :termo
                or lower(coalesce(o.chamadoNum, '')) like :termo
                or cast(l.numero as String) like :termo)
            """,
            countQuery = """
            select count(o) from Orcamento o join o.loja l
            where (:modo is null or o.modo = :modo)
              and (:termo is null
                or lower(o.titulo) like :termo
                or lower(l.nome) like :termo
                or lower(l.empresa) like :termo
                or lower(coalesce(o.chamadoNum, '')) like :termo
                or cast(l.numero as String) like :termo)
            """)
    Page<Orcamento> buscar(@Param("termo") String termoLike, @Param("modo") ModoAquisicao modo, Pageable pagina);

    /** Quantos orçamentos de cada tipo batem com a busca — os números das abas do histórico. */
    @Query("""
            select o.modo, count(o) from Orcamento o join o.loja l
            where :termo is null
               or lower(o.titulo) like :termo
               or lower(l.nome) like :termo
               or lower(l.empresa) like :termo
               or lower(coalesce(o.chamadoNum, '')) like :termo
               or cast(l.numero as String) like :termo
            group by o.modo
            """)
    List<Object[]> contarPorModo(@Param("termo") String termoLike);
}
