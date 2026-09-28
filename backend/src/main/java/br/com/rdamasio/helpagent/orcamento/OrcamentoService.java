package br.com.rdamasio.helpagent.orcamento;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.rdamasio.helpagent.armazenamento.ArmazenamentoArquivos;
import br.com.rdamasio.helpagent.common.Dinheiro;
import br.com.rdamasio.helpagent.common.Documento;
import br.com.rdamasio.helpagent.common.ErroNegocio;
import br.com.rdamasio.helpagent.config.HelpAgentProperties;
import br.com.rdamasio.helpagent.config.UsuarioAtual;
import br.com.rdamasio.helpagent.loja.Loja;
import br.com.rdamasio.helpagent.loja.LojaService;
import br.com.rdamasio.helpagent.pdf.DadosImpresso;
import br.com.rdamasio.helpagent.pdf.PdfOrcamentoService;

/**
 * Geração do impresso, do começo ao fim: valida (A1), recalcula os totais, monta os anexos com rótulo,
 * pede o PDF ao {@link br.com.rdamasio.helpagent.pdf.PdfOrcamentoService}, guarda o arquivo e registra no histórico.
 *
 * <p>O servidor não confia nos totais da tela: tudo é recalculado aqui a partir de qtd e unitário.
 */
@Service
public class OrcamentoService {

    private static final Pattern NUMERO = Pattern.compile("\\d+");

    public record Gerado(Long id, String nomeArquivo, byte[] pdf) {
    }

    private final LojaService lojas;
    private final PdfOrcamentoService pdf;
    private final ArmazenamentoArquivos armazenamento;
    private final OrcamentoRepository repo;
    private final UsuarioAtual usuario;
    private final HelpAgentProperties.Orcamento cfg;

    public OrcamentoService(LojaService lojas, PdfOrcamentoService pdf, ArmazenamentoArquivos armazenamento,
            OrcamentoRepository repo, UsuarioAtual usuario, HelpAgentProperties props) {
        this.lojas = lojas;
        this.pdf = pdf;
        this.armazenamento = armazenamento;
        this.repo = repo;
        this.usuario = usuario;
        this.cfg = props.orcamento();
    }

    @Transactional
    public Gerado gerar(GerarOrcamentoRequest r, List<Documento> chamados, List<Documento> orcamentos) {
        Loja loja = lojas.porNumero(r.lojaNumero())
                .orElseThrow(() -> new ErroNegocio("Selecione a loja.", List.of("loja " + r.lojaNumero() + " não cadastrada")));

        List<String> problemas = ValidadorOrcamento.problemas(r, cfg.maxItens());
        if (!problemas.isEmpty()) {
            throw new ErroNegocio("Não gerei o PDF porque o total não fecharia com a lista de itens.", problemas);
        }
        if (r.validade() != null && r.validade().isBefore(r.dataEmissao())) {
            throw new ErroNegocio("Validade inválida.", List.of("a validade (" + r.validade().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                    + ") é anterior à data de emissão"));
        }

        String requerente = padrao(r.requerente(), cfg.requerentePadrao());
        String gestor = padrao(r.gestor(), cfg.gestorPadrao());
        BigDecimal total = CalculoOrcamento.totalGeral(r);
        List<GerarOrcamentoRequest.Item> impressos = r.itens().stream().filter(CalculoOrcamento::temProduto).toList();

        Orcamento o = new Orcamento(r.modo(), loja, r.titulo().trim(), r.dataEmissao(), vazioComoNulo(r.chamadoNum()),
                r.subtotal(), r.frete(), r.acrescimos(), total, r.observacoes(), requerente, gestor,
                usuario.identificador(), Instant.now());
        o.definirValidade(r.validade());
        List<DadosImpresso.Linha> linhas = new ArrayList<>();
        for (int i = 0; i < impressos.size(); i++) {
            GerarOrcamentoRequest.Item it = impressos.get(i);
            BigDecimal qtd = CalculoOrcamento.quantidade(it);
            BigDecimal unit = Dinheiro.ouZero(it.valorUnitario());
            BigDecimal tot = CalculoOrcamento.totalItem(it);
            o.adicionarItem(new OrcamentoItem(i + 1, it.produto().trim(), it.descricao(), qtd, unit, tot));
            linhas.add(new DadosImpresso.Linha(it.produto().trim(), it.descricao(), qtd.stripTrailingZeros().toPlainString(),
                    Dinheiro.formatar(unit), Dinheiro.formatar(tot)));
        }

        List<String> numerosChamado = numeros(r.chamadoNum());
        List<DadosImpresso.Anexo> anexos = new ArrayList<>();
        for (int i = 0; i < chamados.size(); i++) {
            String rotulo = i < numerosChamado.size() ? "Chamado " + numerosChamado.get(i)
                    : chamados.size() > 1 ? "Chamado " + (i + 1) : "Chamado";
            anexos.add(new DadosImpresso.Anexo(chamados.get(i), rotulo));
        }
        for (int i = 0; i < orcamentos.size(); i++) {
            String rotulo = orcamentos.size() > 1 ? "Orçamento " + (i + 1) + "/" + orcamentos.size() : "Orçamento original";
            anexos.add(new DadosImpresso.Anexo(orcamentos.get(i), rotulo));
        }

        byte[] bytes = pdf.gerar(new DadosImpresso(loja.getTemplate(), o.getTitulo(), cfg.departamento(), loja.getEmpresa(),
                loja.getCnpj(), requerente, gestor, r.dataEmissao(), r.validade(), linhas, formatarOpcional(r.subtotal()),
                formatarOpcional(r.frete()), formatarOpcional(r.acrescimos()), Dinheiro.formatar(total),
                r.observacoes(), cfg.variacao(), anexos));

        String nome = nomeArquivo(numerosChamado, o.getTitulo());
        String ref = armazenamento.salvar(bytes, "pdf");
        o.registrarPdf(nome, ref);
        repo.save(o);
        return new Gerado(o.getId(), nome, bytes);
    }

    /**
     * "Orçamento 1021069.pdf". Com vários chamados, junta com hífen — a v3.5 concatenava os dígitos
     * ("1021071, 1021072" virava "10210711021072"), gerando um número que não existe.
     */
    static String nomeArquivo(List<String> numerosChamado, String titulo) {
        if (!numerosChamado.isEmpty()) return "Orçamento " + String.join("-", numerosChamado) + ".pdf";
        String slug = titulo.replaceAll("[^a-zA-Z0-9À-ÿ ]", "").trim();
        if (slug.length() > 60) slug = slug.substring(0, 60).trim();
        return "Orçamento " + (slug.isEmpty() ? "sem-chamado" : slug) + ".pdf";
    }

    static List<String> numeros(String chamadoNum) {
        List<String> r = new ArrayList<>();
        if (chamadoNum == null) return r;
        Matcher m = NUMERO.matcher(chamadoNum);
        while (m.find()) r.add(m.group());
        return r;
    }

    private static String formatarOpcional(BigDecimal v) {
        return v == null || v.signum() == 0 ? null : Dinheiro.formatar(v);
    }

    private static String padrao(String valor, String padrao) {
        return valor == null || valor.isBlank() ? padrao : valor.trim();
    }

    private static String vazioComoNulo(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
