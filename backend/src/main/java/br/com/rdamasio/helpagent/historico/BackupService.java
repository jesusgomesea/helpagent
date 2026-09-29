package br.com.rdamasio.helpagent.historico;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.rdamasio.helpagent.armazenamento.ArmazenamentoArquivos;
import br.com.rdamasio.helpagent.common.Dinheiro;
import br.com.rdamasio.helpagent.common.ErroNegocio;
import br.com.rdamasio.helpagent.common.NaoEncontrado;
import br.com.rdamasio.helpagent.config.HelpAgentProperties;
import br.com.rdamasio.helpagent.historico.BackupHistorico.Registro;
import br.com.rdamasio.helpagent.historico.BackupHistorico.RegistroLegado;
import br.com.rdamasio.helpagent.historico.BackupHistorico.ResultadoImportacao;
import br.com.rdamasio.helpagent.loja.Loja;
import br.com.rdamasio.helpagent.loja.LojaService;
import br.com.rdamasio.helpagent.orcamento.ModoAquisicao;
import br.com.rdamasio.helpagent.orcamento.Orcamento;
import br.com.rdamasio.helpagent.orcamento.OrcamentoItem;
import br.com.rdamasio.helpagent.orcamento.OrcamentoRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exportação e importação do histórico em JSON ({@link BackupHistorico}).
 *
 * <p>Existe para não perder o histórico enquanto o banco definitivo não está decidido: antes de
 * atualizar a versão ou trocar de banco, exportar; depois, importar. Também traz o histórico que o
 * HTML v3.5 guardava no IndexedDB de cada navegador (formato versão 1).
 */
@Service
public class BackupService {

    private final OrcamentoRepository repo;
    private final ArmazenamentoArquivos armazenamento;
    private final LojaService lojas;
    private final JsonMapper json;
    private final HelpAgentProperties props;

    public BackupService(OrcamentoRepository repo, ArmazenamentoArquivos armazenamento, LojaService lojas,
            JsonMapper json, HelpAgentProperties props) {
        this.repo = repo;
        this.armazenamento = armazenamento;
        this.lojas = lojas;
        this.json = json;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public byte[] exportar() {
        List<Registro> registros = repo.findAllByOrderByCriadoEmAsc().stream().map(this::paraRegistro).toList();
        return json.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                new BackupHistorico(BackupHistorico.VERSAO, "helpagent", Instant.now(), registros));
    }

    private Registro paraRegistro(Orcamento o) {
        byte[] pdf;
        try {
            pdf = armazenamento.ler(o.getPdfRef());
        } catch (NaoEncontrado e) {
            pdf = null; // o registro vale mesmo sem o arquivo; a importação avisa
        }
        return new Registro(o.getModo(), o.getLoja().getNumero(), o.getLoja().getNome(), o.getLoja().getEmpresa(),
                o.getTitulo(), o.getDataEmissao(), o.getValidade(), o.getChamadoNum(), o.getSubtotal(), o.getFrete(), o.getAcrescimos(),
                o.getTotal(), o.getObservacoes(), o.getRequerente(), o.getGestor(), o.getNomeArquivo(), o.getCriadoPor(),
                o.getCriadoEm(),
                o.getItens().stream().map(i -> new BackupHistorico.Item(i.getOrdem(), i.getProduto(), i.getDescricao(),
                        i.getQuantidade(), i.getValorUnitario(), i.getValorTotal(), i.getFornecedor(), i.getUrl(),
                        i.getColetadoEm())).toList(),
                pdf, o.getOrigem());
    }

    /**
     * Adiciona ao histórico o que ainda não existe (mesmo título + mesmo instante de criação = repetido).
     * Aceita o backup deste sistema (versão 2) e o JSON exportado pelo HTML v3.5 (versão 1).
     */
    @Transactional
    public ResultadoImportacao importar(byte[] arquivo) {
        JsonNode raiz;
        try {
            raiz = json.readTree(arquivo);
        } catch (JacksonException e) {
            throw new ErroNegocio("O arquivo não é um JSON válido.");
        }
        if (raiz == null || !raiz.has("registros") || !raiz.get("registros").isArray()) {
            throw new ErroNegocio("Arquivo sem o campo \"registros\" — não parece um backup do histórico.");
        }
        int versao = raiz.path("versao").asInt(1);
        return versao >= 2
                ? importarAtual(json.treeToValue(raiz, BackupHistorico.class).registros(), versao)
                : importarLegado(json.treeToValue(raiz, BackupHistorico.Legado.class).registros());
    }

    /** Versão 2: "OPEX" era o fluxo normal com chamado, que hoje é REQUISICAO (ver BackupHistorico). */
    static ModoAquisicao modoDoBackup(ModoAquisicao modo, int versao) {
        if (modo == null) return ModoAquisicao.REQUISICAO;
        return versao < 3 && modo == ModoAquisicao.OPEX ? ModoAquisicao.REQUISICAO : modo;
    }

    private ResultadoImportacao importarAtual(List<Registro> registros, int versao) {
        Contagem c = new Contagem();
        for (int i = 0; i < registros.size(); i++) {
            Registro r = registros.get(i);
            String ref = "registro " + (i + 1) + " (\"" + r.titulo() + "\")";
            Optional<Loja> loja = lojas.porNumero(String.valueOf(r.lojaNumero()));
            if (loja.isEmpty()) { c.problema(ref + ": loja " + r.lojaNumero() + " não cadastrada"); continue; }
            if (r.criadoEm() == null || r.titulo() == null) { c.problema(ref + ": sem título ou data"); continue; }
            if (repo.existsByTituloAndCriadoEm(r.titulo(), r.criadoEm())) { c.ignorados++; continue; }
            if (r.pdf() == null) { c.problema(ref + ": sem o PDF"); continue; }

            Orcamento o = new Orcamento(modoDoBackup(r.modo(), versao), loja.get(), r.titulo(),
                    r.dataEmissao() == null ? dataNoFuso(r.criadoEm()) : r.dataEmissao(), r.chamadoNum(),
                    r.subtotal(), r.frete(), r.acrescimos(), Dinheiro.ouZero(r.total()), r.observacoes(),
                    padrao(r.requerente(), props.orcamento().requerentePadrao()),
                    padrao(r.gestor(), props.orcamento().gestorPadrao()),
                    padrao(r.criadoPor(), "importado"), r.criadoEm());
            o.definirValidade(r.validade());
            o.definirOrigem(r.origem());
            if (r.itens() != null) {
                r.itens().forEach(it -> {
                    OrcamentoItem item = new OrcamentoItem(it.ordem(), it.produto(), it.descricao(),
                            it.quantidade(), it.valorUnitario(), it.valorTotal());
                    if (it.fornecedor() != null) item.registrarCotacao(it.fornecedor(), it.url(), it.coletadoEm());
                    o.adicionarItem(item);
                });
            }
            salvar(o, padrao(r.nomeArquivo(), "Orçamento importado.pdf"), r.pdf());
            c.importados++;
        }
        return c.resultado();
    }

    private ResultadoImportacao importarLegado(List<RegistroLegado> registros) {
        Contagem c = new Contagem();
        for (int i = 0; i < registros.size(); i++) {
            RegistroLegado r = registros.get(i);
            String ref = "registro " + (i + 1) + " (\"" + r.titulo() + "\")";
            Optional<Loja> loja = lojas.resolver(r.loja_num(), r.loja_nome());
            Instant criadoEm = instante(r.data());
            if (loja.isEmpty()) { c.problema(ref + ": loja " + r.loja_num() + " não cadastrada"); continue; }
            if (criadoEm == null || r.titulo() == null) { c.problema(ref + ": sem título ou data"); continue; }
            if (repo.existsByTituloAndCriadoEm(r.titulo(), criadoEm)) { c.ignorados++; continue; }
            if (r.pdfBlob() == null) { c.problema(ref + ": sem o PDF"); continue; }

            // A v3.5 não guardava itens nem modo; o modo sai da observação ("CAPEX. ..." vs "# 1021069. ...").
            // O fluxo normal da v3.5 (chamado + orçamentos) é o que hoje se chama Requisição/Chamado.
            boolean capex = r.observacao() != null && r.observacao().trim().toUpperCase().startsWith("CAPEX");
            String chamado = r.chamado_num() == null || r.chamado_num().isBlank() ? null : r.chamado_num().trim();
            Orcamento o = new Orcamento(capex ? ModoAquisicao.CAPEX : ModoAquisicao.REQUISICAO, loja.get(), r.titulo(),
                    dataNoFuso(criadoEm), chamado, null, null, null, Dinheiro.parse(r.total()), r.observacao(),
                    props.orcamento().requerentePadrao(), props.orcamento().gestorPadrao(), "importado-v3.5", criadoEm);
            salvar(o, padrao(r.fileName(), "Orçamento importado.pdf"), r.pdfBlob());
            c.importados++;
        }
        return c.resultado();
    }

    private void salvar(Orcamento o, String nomeArquivo, byte[] pdf) {
        o.registrarPdf(nomeArquivo, armazenamento.salvar(pdf, "pdf"));
        repo.save(o);
    }

    private java.time.LocalDate dataNoFuso(Instant i) {
        return i.atZone(props.fuso() == null ? ZoneId.systemDefault() : props.fuso()).toLocalDate();
    }

    private static Instant instante(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String padrao(String v, String p) {
        return v == null || v.isBlank() ? p : v;
    }

    private static final class Contagem {
        int importados;
        int ignorados;
        final List<String> problemas = new ArrayList<>();

        void problema(String p) { problemas.add(p); }

        ResultadoImportacao resultado() { return new ResultadoImportacao(importados, ignorados, problemas); }
    }
}
