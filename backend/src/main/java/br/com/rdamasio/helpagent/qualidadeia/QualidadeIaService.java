package br.com.rdamasio.helpagent.qualidadeia;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import br.com.rdamasio.helpagent.extracao.DadosExtraidos;
import br.com.rdamasio.helpagent.extracao.LeituraConcluida;
import br.com.rdamasio.helpagent.orcamento.OrcamentoGerado;
import tools.jackson.databind.json.JsonMapper;

/**
 * Aprender com as correções (30/09/2026): guarda cada leitura da IA ({@link LeituraConcluida}) e, quando um
 * orçamento é gerado a partir dela ({@link OrcamentoGerado}), compara campo a campo o que a IA leu com o que o
 * atendente confirmou ({@link ComparacaoLeitura}). O relatório ({@code /swagger/qualidade-ia}) mostra onde a IA mais
 * erra — por campo, modelo e fornecedor — e exemplos, para ajustar o prompt com dado, não com impressão.
 *
 * <p>Nada aqui muda a leitura sozinho: o prompt continua sendo mudado por gente, e medido com
 * {@code tools/avaliar_extracao.py}. Falha aqui nunca derruba a extração nem a geração (só log).
 */
@Service
public class QualidadeIaService {

    private static final Logger log = LoggerFactory.getLogger(QualidadeIaService.class);
    /** Leitura que não virou orçamento em 90 dias sai (não há com o que comparar). */
    static final Duration RETENCAO_LEITURA = Duration.ofDays(90);

    /** Taxa de acerto de um agrupamento. {@code maisCorrigido} = campo com mais correções dentro dele. */
    public record Linha(String nome, long orcamentos, long comparados, long corrigidos, double acerto, String maisCorrigido) {
    }

    public record Exemplo(Instant momento, String campo, int item, String fornecedor, String lido, String confirmado,
            String modelo, Long orcamentoId) {
    }

    /**
     * @param leituras             leituras da IA no período
     * @param orcamentosComparados orçamentos gerados a partir de uma leitura (os que entram nas contas)
     * @param semCorrecao          desses, quantos saíram sem o atendente mudar nada
     */
    public record Relatorio(int dias, long leituras, long orcamentosComparados, long semCorrecao, List<Linha> porCampo,
            List<Linha> porModelo, List<Linha> porFornecedor, List<Exemplo> ultimas) {
    }

    private final LeituraIaRepository leituras;
    private final CorrecaoIaRepository correcoes;
    private final JsonMapper json;
    private final Clock relogio;

    @Autowired
    public QualidadeIaService(LeituraIaRepository leituras, CorrecaoIaRepository correcoes, JsonMapper json) {
        this(leituras, correcoes, json, Clock.systemUTC());
    }

    QualidadeIaService(LeituraIaRepository leituras, CorrecaoIaRepository correcoes, JsonMapper json, Clock relogio) {
        this.leituras = leituras;
        this.correcoes = correcoes;
        this.json = json;
        this.relogio = relogio;
    }

    @EventListener
    public void aoLer(LeituraConcluida e) {
        try {
            String dados = json.writeValueAsString(e.dados());
            if (dados.length() > 20000) dados = dados.substring(0, 20000); // leitura gigante: guarda o começo
            leituras.save(new LeituraIa(e.id(), e.momento(), e.modo(), e.modelo(), e.degrau(), e.arquivos(), dados));
        } catch (RuntimeException ex) {
            log.warn("[Qualidade IA] não consegui guardar a leitura {}: {}", e.id(), ex.getMessage());
        }
    }

    /** Depois do commit do orçamento: compara com a leitura de onde ele veio (se veio de uma). */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void aoGerar(OrcamentoGerado e) {
        if (e.idLeitura() == null || e.idLeitura().isBlank()) return;
        try {
            LeituraIa l = leituras.findById(e.idLeitura()).orElse(null);
            if (l == null || correcoes.existsByLeituraIdAndOrcamentoId(l.getId(), e.orcamentoId())) return;
            DadosExtraidos lido = json.readValue(l.getDados(), DadosExtraidos.class);
            List<ComparacaoLeitura.Campo> campos = ComparacaoLeitura.comparar(lido, e.dados(), e.total(), e.fornecedores());
            Instant agora = relogio.instant();
            correcoes.saveAll(campos.stream().map(c -> new CorrecaoIa(agora, l.getId(), e.orcamentoId(), l.getModelo(), c)).toList());
            long corrigidos = campos.stream().filter(ComparacaoLeitura.Campo::corrigido).count();
            log.info("[Qualidade IA] orçamento {} × leitura {} ({}): {} de {} campo(s) corrigido(s){}", e.orcamentoId(),
                    l.getId(), l.getModelo(), corrigidos, campos.size(), corrigidos == 0 ? "" : ": " + campos.stream()
                            .filter(ComparacaoLeitura.Campo::corrigido).map(c -> c.item() == 0 ? c.campo() : c.campo() + "#" + c.item())
                            .collect(Collectors.joining(", ")));
        } catch (RuntimeException ex) {
            log.warn("[Qualidade IA] não consegui comparar o orçamento {}: {}", e.orcamentoId(), ex.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void aoSubir() {
        try {
            int n = leituras.apagarSemUsoAntesDe(relogio.instant().minus(RETENCAO_LEITURA));
            if (n > 0) log.info("[Qualidade IA] {} leitura(s) sem orçamento com mais de {} dias apagadas.", n, RETENCAO_LEITURA.toDays());
        } catch (RuntimeException ex) {
            log.warn("[Qualidade IA] limpeza das leituras antigas falhou: {}", ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Relatorio relatorio(int dias) {
        int d = Math.clamp(dias, 1, 3650);
        Instant desde = relogio.instant().minus(Duration.ofDays(d));
        List<CorrecaoIa> todas = correcoes.findByCriadoEmGreaterThanEqual(desde);

        Map<String, List<CorrecaoIa>> porOrcamento = todas.stream()
                .collect(Collectors.groupingBy(c -> c.getLeituraId() + "/" + c.getOrcamentoId(), LinkedHashMap::new, Collectors.toList()));
        long semCorrecao = porOrcamento.values().stream().filter(l -> l.stream().noneMatch(CorrecaoIa::isCorrigido)).count();

        List<Linha> porCampo = agrupar(todas, CorrecaoIa::getCampo, Comparator.comparingLong(Linha::corrigidos).reversed());
        List<Linha> porModelo = agrupar(todas, CorrecaoIa::getModelo, Comparator.comparingLong(Linha::orcamentos).reversed());
        List<CorrecaoIa> deLinhas = todas.stream().filter(c -> c.getItem() > 0 && c.getFornecedor() != null).toList();
        List<Linha> porFornecedor = agrupar(deLinhas, CorrecaoIa::getFornecedor,
                Comparator.comparingLong(Linha::corrigidos).reversed()).stream().limit(20).toList();

        List<Exemplo> ultimas = correcoes.findTop40ByCorrigidoTrueOrderByCriadoEmDesc().stream()
                .map(c -> new Exemplo(c.getCriadoEm(), c.getCampo(), c.getItem(), c.getFornecedor(), c.getLido(),
                        c.getConfirmado(), c.getModelo(), c.getOrcamentoId()))
                .toList();
        return new Relatorio(d, leituras.countByCriadoEmGreaterThanEqual(desde), porOrcamento.size(), semCorrecao,
                porCampo, porModelo, porFornecedor, ultimas);
    }

    private static List<Linha> agrupar(List<CorrecaoIa> lista, Function<CorrecaoIa, String> chave, Comparator<Linha> ordem) {
        Map<String, List<CorrecaoIa>> grupos = lista.stream().collect(Collectors.groupingBy(chave, LinkedHashMap::new, Collectors.toList()));
        List<Linha> r = new ArrayList<>();
        for (var g : grupos.entrySet()) {
            List<CorrecaoIa> l = g.getValue();
            long corrigidos = l.stream().filter(CorrecaoIa::isCorrigido).count();
            Set<String> orcs = l.stream().map(c -> c.getLeituraId() + "/" + c.getOrcamentoId()).collect(Collectors.toSet());
            String mais = l.stream().filter(CorrecaoIa::isCorrigido)
                    .collect(Collectors.groupingBy(CorrecaoIa::getCampo, Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            r.add(new Linha(g.getKey(), orcs.size(), l.size(), corrigidos,
                    l.isEmpty() ? 1 : Math.round((1 - (double) corrigidos / l.size()) * 1000) / 1000.0, mais));
        }
        r.sort(ordem);
        return r;
    }
}
