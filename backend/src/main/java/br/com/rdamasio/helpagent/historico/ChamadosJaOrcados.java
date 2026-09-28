package br.com.rdamasio.helpagent.historico;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.rdamasio.helpagent.orcamento.Orcamento;
import br.com.rdamasio.helpagent.orcamento.OrcamentoRepository;

/**
 * Procura no histórico orçamentos já gerados para os mesmos números de chamado, para avisar antes de
 * gerar um segundo impresso para o mesmo pedido.
 *
 * <p>O campo guarda texto livre ("1021071, 1021072"), por isso a busca no banco é por "contém" e a
 * confirmação é por número inteiro aqui: sem ela, o chamado 102107 casaria com 1021071.
 */
@Service
public class ChamadosJaOrcados {

    private static final Pattern NUMERO = Pattern.compile("\\d+");

    private final OrcamentoRepository repo;

    public ChamadosJaOrcados(OrcamentoRepository repo) {
        this.repo = repo;
    }

    /** Números de chamado contidos num texto livre ("# 1021069, 1021070" → [1021069, 1021070]). */
    public static Set<String> numeros(String texto) {
        Set<String> r = new java.util.LinkedHashSet<>();
        if (texto == null) return r;
        Matcher m = NUMERO.matcher(texto);
        while (m.find()) {
            if (m.group().length() >= 4) r.add(m.group()); // descarta "#", anos curtos e ruído
        }
        return r;
    }

    @Transactional(readOnly = true)
    public List<HistoricoController.Item> buscar(Collection<String> numerosChamado) {
        Map<Long, HistoricoController.Item> achados = new LinkedHashMap<>();
        for (String numero : numerosChamado) {
            for (Orcamento o : repo.buscarPorChamadoContendo(numero)) {
                if (numeros(o.getChamadoNum()).contains(numero)) achados.putIfAbsent(o.getId(), HistoricoController.Item.de(o));
            }
        }
        return List.copyOf(achados.values());
    }
}
