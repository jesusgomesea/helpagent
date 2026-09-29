package br.com.rdamasio.helpagent.usoia;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Chamadas ao Gemini registradas (tabela {@code uso_ia}). */
public interface UsoIaRepository extends JpaRepository<UsoIa, Long> {

    List<UsoIa> findByMomentoGreaterThanEqualOrderByMomento(Instant desde);

    List<UsoIa> findTop40ByOrderByMomentoDesc();

    @Modifying
    @Transactional
    @Query("delete from UsoIa u where u.momento < :antes")
    int apagarAntesDe(@Param("antes") Instant antes);
}
