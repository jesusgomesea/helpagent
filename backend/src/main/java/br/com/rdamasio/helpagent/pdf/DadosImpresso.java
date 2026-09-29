package br.com.rdamasio.helpagent.pdf;

import java.time.LocalDate;
import java.util.List;

import br.com.rdamasio.helpagent.common.Documento;
import br.com.rdamasio.helpagent.template.TemplateCodigo;

/** Tudo que vai para o impresso, já formatado como texto. Campos nulos ficam em branco. */
public record DadosImpresso(
        TemplateCodigo template,
        String titulo,
        String departamento,
        String empresa,
        String cnpj,
        String requerente,
        String gestor,
        LocalDate dataEmissao,
        /** "Válido até"; null deixa o campo em branco. */
        LocalDate validade,
        List<Linha> linhas,
        String subtotal,
        String frete,
        String acrescimos,
        String total,
        String observacoes,
        String variacao,
        /** Na ordem em que entram depois do formulário. */
        List<Apendice> anexos) {

    public record Linha(String produto, String descricao, String quantidade, String unitario, String total) {
    }

    /** O que vem depois do formulário: um documento, ou páginas que o próprio sistema gera. */
    public sealed interface Apendice permits Anexo, ResumoCotacao {
    }

    /**
     * Documento anexado depois do formulário, com o rótulo carimbado no topo da primeira página.
     *
     * @param paisagem imagem em página A4 deitada. Os prints da cotação são da janela inteira (1440 px de largura):
     *                 em pé, o texto da loja sairia com ~6 pt; deitado, ~9 pt, legível no papel
     */
    public record Anexo(Documento documento, String rotulo, boolean paisagem) implements Apendice {

        public Anexo(Documento documento, String rotulo) {
            this(documento, rotulo, false);
        }
    }

    /**
     * Orçamento por cotação: página com as 3 opções de cada item lado a lado, a escolhida marcada. Vem antes dos
     * prints, para quem valida ver o quadro inteiro e depois conferir cada print.
     */
    public record ResumoCotacao(List<ItemCotado> itens) implements Apendice {
    }

    /** @param quantidade como sai no impresso ("2") */
    public record ItemCotado(String ordem, String produto, String quantidade, List<OpcaoCotada> opcoes) {
    }

    /**
     * @param rotulo      "Escolhida", "Opção 2", "Opção 3" — o mesmo do carimbo do print
     * @param capturadoEm "28/09/2026 21:40:12"; null quando o print foi anexado à mão sem captura
     * @param alerta      conferência automática não achou o preço visível no print; null = conferido
     */
    public record OpcaoCotada(String rotulo, boolean escolhida, String loja, String titulo, String preco, String url,
            String capturadoEm, String alerta) {

        public OpcaoCotada(String rotulo, boolean escolhida, String loja, String titulo, String preco, String url,
                String capturadoEm) {
            this(rotulo, escolhida, loja, titulo, preco, url, capturadoEm, null);
        }
    }
}
