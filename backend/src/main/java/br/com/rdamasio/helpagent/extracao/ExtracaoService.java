package br.com.rdamasio.helpagent.extracao;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import br.com.rdamasio.helpagent.common.Documento;
import br.com.rdamasio.helpagent.common.ErroNegocio;
import br.com.rdamasio.helpagent.config.HelpAgentProperties;
import br.com.rdamasio.helpagent.fornecedor.FornecedorController;
import br.com.rdamasio.helpagent.fornecedor.FornecedorService;
import br.com.rdamasio.helpagent.historico.ChamadosJaOrcados;
import br.com.rdamasio.helpagent.historico.HistoricoController;
import br.com.rdamasio.helpagent.loja.LojaDto;
import br.com.rdamasio.helpagent.loja.LojaService;
import br.com.rdamasio.helpagent.orcamento.ModoAquisicao;

/**
 * Orquestra a leitura por IA: valida o que foi enviado para o modo (OPEX exige chamado), monta o prompt,
 * chama o {@link ExtratorIa} (ou reaproveita o {@link CacheExtracao}), resolve a loja no cadastro e calcula
 * os avisos A2/C2. Não grava nada no banco — a extração é descartável até o usuário gerar o PDF.
 */
@Service
public class ExtracaoService {

    private static final Logger log = LoggerFactory.getLogger(ExtracaoService.class);

    /**
     * @param loja    loja já resolvida no cadastro a partir do que a IA leu (null se não achou)
     * @param avisos  conferências A2/C2 para o usuário revisar antes de gerar
     * @param duracaoMs tempo da leitura no servidor (a tela mostra; é o número a acompanhar)
     * @param doCache  true quando os mesmos arquivos já tinham sido lidos há pouco (resposta instantânea)
     * @param validadeSugerida "válido até" para o impresso: a data escrita no documento, ou hoje + os dias
     *                 de validade escritos nele; null quando o documento não diz (comum em print de e-commerce)
     * @param chamadosJaOrcados orçamentos já gerados para os mesmos chamados (aviso de duplicidade)
     * @param fornecedores      um por item (mesma ordem de {@code dados.itens}): o que a IA leu e o cadastrado que o
     *                          sistema reconheceu (null = fornecedor novo; vira cadastro ao gerar)
     * @param idLeitura         volta no pedido de gerar: o servidor compara o que a IA leu com o que foi confirmado
     */
    public record Resposta(DadosExtraidos dados, LojaDto loja, List<String> avisos, String modelo, int tentativas,
            long duracaoMs, boolean doCache, LocalDate validadeSugerida,
            List<HistoricoController.Item> chamadosJaOrcados, List<FornecedorSugerido> fornecedores, String idLeitura) {
    }

    /** Fornecedor de um item lido: como veio no documento e o cadastrado correspondente, se houver. */
    public record FornecedorSugerido(String lido, String cnpjLido, FornecedorController.FornecedorDto cadastrado) {
    }

    private final ExtratorIa extrator;
    private final CacheExtracao cache;
    private final PromptExtracao prompt;
    private final LojaService lojas;
    private final HelpAgentProperties props;
    private final ChamadosJaOrcados jaOrcados;
    private final FornecedorService fornecedores;
    private final ApplicationEventPublisher eventos;

    public ExtracaoService(ExtratorIa extrator, CacheExtracao cache, PromptExtracao prompt, LojaService lojas,
            HelpAgentProperties props, ChamadosJaOrcados jaOrcados, FornecedorService fornecedores,
            ApplicationEventPublisher eventos) {
        this.jaOrcados = jaOrcados;
        this.fornecedores = fornecedores;
        this.eventos = eventos;
        this.extrator = extrator;
        this.cache = cache;
        this.prompt = prompt;
        this.lojas = lojas;
        this.props = props;
    }

    public Resposta extrair(ModoAquisicao modo, List<Documento> chamados, List<Documento> orcamentos) {
        return extrair(modo, chamados, orcamentos, null);
    }

    /** @param origem IP de quem pediu (vai para o registro de uso da IA) */
    public Resposta extrair(ModoAquisicao modo, List<Documento> chamados, List<Documento> orcamentos, String origem) {
        if (orcamentos.isEmpty()) throw new ErroNegocio("Suba pelo menos 1 orçamento.");
        if (modo.exigeChamado() && chamados.isEmpty()) {
            throw new ErroNegocio("Suba o chamado e pelo menos 1 orçamento.");
        }

        // Em CAPEX um chamado eventualmente anexado não vai para a IA (o prompt diz que não existe).
        List<Documento> enviados = new ArrayList<>();
        int totalChamados = modo.exigeChamado() ? chamados.size() : 0;
        if (totalChamados > 0) enviados.addAll(chamados);
        enviados.addAll(orcamentos);

        long inicio = System.currentTimeMillis();
        String textoPrompt = prompt.montar(modo, totalChamados, orcamentos.size());
        String chave = CacheExtracao.chave(modo.name(), textoPrompt, enviados);
        var contexto = new ExtratorIa.Contexto(modo.name(), origem);
        CacheExtracao.Obtido obtido = cache.obter(chave, () -> extrator.extrair(enviados, textoPrompt, contexto));
        ExtratorIa.Resultado r = obtido.resultado();
        long ms = System.currentTimeMillis() - inicio;
        long bytes = enviados.stream().mapToLong(d -> d.conteudo().length).sum();
        log.info("Extração {} concluída: modelo={} degrau={} tentativas={} duracaoMs={} arquivos={} tamanhoKB={} cache={}",
                modo, r.modelo(), r.degrau(), r.tentativas(), ms, enviados.size(), bytes / 1024, obtido.reaproveitado());

        DadosExtraidos d = r.dados();
        // leitura nova (não reaproveitada): guardada para comparar com o que o atendente confirmar (qualidadeia)
        if (!obtido.reaproveitado() && r.leitura() != null) {
            eventos.publishEvent(new LeituraConcluida(r.leitura(), java.time.Instant.now(), modo.name(), r.modelo(),
                    r.degrau(), enviados.size(), d));
        }
        // Rastro de auditoria: de onde a IA tirou cada valor. Não aparece na tela; serve para investigar
        // uma leitura errada depois (ex.: somou anotação à mão da proposta).
        for (int i = 0; i < d.itens().size(); i++) {
            DadosExtraidos.Item it = d.itens().get(i);
            log.info("Extração item {}: produto=\"{}\" valor={} fonte=\"{}\" fornecedor=\"{}\" cnpj={}", i + 1,
                    it.produto(), it.valorTotal(), it.fonte(), it.fornecedor(), it.fornecedorCnpj());
        }
        List<FornecedorSugerido> sugeridos = d.itens().stream()
                .map(it -> new FornecedorSugerido(it.fornecedor(), it.fornecedorCnpj(),
                        fornecedores.reconhecer(it.fornecedor(), it.fornecedorCnpj())
                                .map(FornecedorController.FornecedorDto::de).orElse(null)))
                .toList();
        LojaDto loja = lojas.resolver(d.lojaNum(), d.lojaNome()).map(LojaDto::de).orElse(null);
        var duplicados = modo.exigeChamado() ? jaOrcados.buscar(ChamadosJaOrcados.numeros(d.chamadoNum())) : List.<HistoricoController.Item>of();
        List<String> avisos = new ArrayList<>(RegrasExtracao.avisos(d, props.orcamento().maxItens()));
        if (r.degrau() > 0) {
            // modelo de baixo erra mais: a revisão humana é a proteção, então ela precisa saber
            avisos.add("Lido pelo modelo reserva " + r.modelo() + " (os de cima estavam sem cota ou sobrecarregados). "
                    + "Confira valores e quantidades com atenção.");
        }
        return new Resposta(d, loja, avisos, r.modelo(), r.tentativas(),
                ms, obtido.reaproveitado(), validadeSugerida(d, LocalDate.now(props.fuso())), duplicados, sugeridos,
                r.leitura());
    }

    private static final DateTimeFormatter DATA_BR = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    /** Data explícita do documento tem prioridade; senão, prazo em dias contado a partir de hoje (a emissão). */
    static LocalDate validadeSugerida(DadosExtraidos d, LocalDate hoje) {
        if (d.validadeAte() != null && !d.validadeAte().isBlank()) {
            try {
                return LocalDate.parse(d.validadeAte().trim(), DATA_BR);
            } catch (DateTimeParseException e) {
                // a IA devolveu num formato inesperado: melhor sem sugestão do que com data errada
            }
        }
        if (d.validadeDias() != null) {
            String dias = d.validadeDias().replaceAll("\\D", "");
            if (!dias.isEmpty() && dias.length() <= 3) return hoje.plusDays(Integer.parseInt(dias));
        }
        return null;
    }
}
