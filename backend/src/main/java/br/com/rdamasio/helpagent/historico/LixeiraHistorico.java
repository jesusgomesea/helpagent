package br.com.rdamasio.helpagent.historico;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.rdamasio.helpagent.armazenamento.ArmazenamentoArquivos;
import br.com.rdamasio.helpagent.common.ErroNegocio;
import br.com.rdamasio.helpagent.common.NaoEncontrado;
import br.com.rdamasio.helpagent.orcamento.Orcamento;
import br.com.rdamasio.helpagent.orcamento.OrcamentoRepository;

/**
 * Lixeira do histórico (30/09/2026). Antes, "apagar" removia o orçamento e o PDF na hora, sem volta — e sem login,
 * qualquer um podia apagar por engano. Agora:
 * <ul>
 *   <li>apagar manda para a lixeira: some das abas, do aviso de chamado já orçado e do backup;</li>
 *   <li>restaurar devolve como estava;</li>
 *   <li>depois de {@link #PRAZO}, é apagado de vez com o PDF (na subida e sempre que alguém abre a lixeira);</li>
 *   <li>excluir de vez antes do prazo só pela lixeira — nunca direto de um orçamento ativo.</li>
 * </ul>
 * Cada movimento vai para o log com quem fez (IP, ou o usuário quando houver login).
 */
@Service
public class LixeiraHistorico {

    private static final Logger log = LoggerFactory.getLogger(LixeiraHistorico.class);
    public static final Duration PRAZO = Duration.ofDays(30);

    private final OrcamentoRepository repo;
    private final ArmazenamentoArquivos armazenamento;
    private final Clock relogio;

    @Autowired
    public LixeiraHistorico(OrcamentoRepository repo, ArmazenamentoArquivos armazenamento) {
        this(repo, armazenamento, Clock.systemUTC());
    }

    LixeiraHistorico(OrcamentoRepository repo, ArmazenamentoArquivos armazenamento, Clock relogio) {
        this.repo = repo;
        this.armazenamento = armazenamento;
        this.relogio = relogio;
    }

    @Transactional
    public void mandar(Long id, String quem) {
        Orcamento o = buscar(id);
        if (o.naLixeira()) return;
        o.mandarParaLixeira(quem, relogio.instant());
        log.info("Orçamento {} (\"{}\") para a LIXEIRA por {}", id, o.getTitulo(), quem);
    }

    @Transactional
    public void restaurar(Long id, String quem) {
        Orcamento o = buscar(id);
        if (!o.naLixeira()) return;
        o.restaurar();
        log.info("Orçamento {} (\"{}\") RESTAURADO da lixeira por {}", id, o.getTitulo(), quem);
    }

    /** Excluir de vez (registro e PDF). Só vale para o que já está na lixeira. */
    @Transactional
    public void excluirDeVez(Long id, String quem) {
        Orcamento o = buscar(id);
        if (!o.naLixeira()) {
            throw new ErroNegocio("Só dá para excluir de vez o que está na lixeira.",
                    List.of("mande o orçamento para a lixeira primeiro"));
        }
        apagar(o);
        log.info("Orçamento {} (\"{}\") EXCLUÍDO DE VEZ por {}", id, o.getTitulo(), quem);
    }

    /** Apaga de vez o que passou do prazo. Barato: roda na subida e quando a lixeira é aberta. */
    @Transactional
    public int limparVencidos() {
        List<Orcamento> vencidos = repo.findByExcluidoEmBefore(relogio.instant().minus(PRAZO));
        for (Orcamento o : vencidos) apagar(o);
        if (!vencidos.isEmpty()) log.info("Lixeira: {} orçamento(s) com mais de {} dias apagados de vez.", vencidos.size(), PRAZO.toDays());
        return vencidos.size();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void aoSubir() {
        try {
            limparVencidos();
        } catch (RuntimeException e) {
            log.warn("Lixeira: não consegui limpar os vencidos na subida: {}", e.getMessage());
        }
    }

    private void apagar(Orcamento o) {
        repo.delete(o);
        armazenamento.remover(o.getPdfRef());
    }

    private Orcamento buscar(Long id) {
        return repo.findById(id).orElseThrow(() -> new NaoEncontrado("Orçamento " + id + " não encontrado"));
    }
}
