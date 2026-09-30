package br.com.rdamasio.helpagent.fornecedor;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Fornecedores conhecidos (tabela {@code fornecedor}). */
public interface FornecedorRepository extends JpaRepository<Fornecedor, Long> {

    List<Fornecedor> findAllByOrderByNome();

    List<Fornecedor> findByAtivoTrueOrderByNome();

    /** Uso de cada fornecedor: [id, orçamentos ativos (fora da lixeira), último uso] — para a tela de cadastro. */
    @Query("""
            select i.fornecedorRef.id, count(distinct o.id), max(o.criadoEm)
            from OrcamentoItem i join i.orcamento o
            where i.fornecedorRef is not null and o.excluidoEm is null
            group by i.fornecedorRef.id
            """)
    List<Object[]> uso();
}
