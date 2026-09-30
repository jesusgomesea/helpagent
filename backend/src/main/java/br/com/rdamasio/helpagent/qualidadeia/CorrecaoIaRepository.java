package br.com.rdamasio.helpagent.qualidadeia;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Campos comparados entre leitura e orçamento (tabela {@code correcao_ia}). O relatório agrega em memória. */
public interface CorrecaoIaRepository extends JpaRepository<CorrecaoIa, Long> {

    List<CorrecaoIa> findByCriadoEmGreaterThanEqual(Instant desde);

    List<CorrecaoIa> findTop40ByCorrigidoTrueOrderByCriadoEmDesc();

    boolean existsByLeituraIdAndOrcamentoId(String leituraId, Long orcamentoId);
}
