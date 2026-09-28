package br.com.rdamasio.helpagent.loja;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acesso às lojas. Lojas inativas nunca aparecem na busca nem na geração.
 */
public interface LojaRepository extends JpaRepository<Loja, Long> {

    Optional<Loja> findByNumeroAndAtivaTrue(int numero);

    List<Loja> findByAtivaTrueOrderByNumero();

    /** Inclui inativas: para a tela de cadastro e para não repetir número. */
    Optional<Loja> findByNumero(int numero);

    List<Loja> findAllByOrderByNumero();
}
