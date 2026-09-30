package br.com.rdamasio.helpagent.qualidadeia;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Leituras da IA guardadas (tabela {@code leitura_ia}). */
public interface LeituraIaRepository extends JpaRepository<LeituraIa, String> {

    long countByCriadoEmGreaterThanEqual(Instant desde);

    /** Leitura antiga que nunca virou orçamento não serve mais para comparar. */
    @Modifying
    @Transactional
    @Query("delete from LeituraIa l where l.criadoEm < :antes and not exists (select 1 from CorrecaoIa c where c.leituraId = l.id)")
    int apagarSemUsoAntesDe(@Param("antes") Instant antes);
}
