package br.com.rdamasio.helpagent.historico;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import br.com.rdamasio.helpagent.orcamento.ModoAquisicao;
import br.com.rdamasio.helpagent.orcamento.OrigemOrcamento;

/**
 * Formato do backup do histórico (versão 3; a 2 é lida também).
 *
 * <p>Versão 3 (28/09/2026): três tipos de requisição. Na versão 2, "OPEX" significava o fluxo normal com
 * chamado, que hoje é {@code REQUISICAO} — a importação converte, igual à migration V4 fez no banco. Autocontido: leva o PDF de cada orçamento em base64,
 * para restaurar mesmo sem o diretório de arquivos. Enquanto o banco definitivo não existe, é
 * isto que protege o histórico numa atualização de versão.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BackupHistorico(int versao, String origem, Instant exportadoEm, List<Registro> registros) {

    public static final int VERSAO = 3;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Registro(
            ModoAquisicao modo,
            int lojaNumero,
            String lojaNome,
            String empresa,
            String titulo,
            LocalDate dataEmissao,
            /** Ausente em backups feitos antes da validade existir — a importação aceita null. */
            LocalDate validade,
            String chamadoNum,
            BigDecimal subtotal,
            BigDecimal frete,
            BigDecimal acrescimos,
            BigDecimal total,
            String observacoes,
            String requerente,
            String gestor,
            String nomeArquivo,
            String criadoPor,
            Instant criadoEm,
            List<Item> itens,
            /** Jackson grava/lê byte[] como base64. */
            byte[] pdf,
            /** Ausente em backups anteriores ao orçamento por cotação: vale DOCUMENTOS. */
            OrigemOrcamento origem) {
    }

    /** {@code fornecedor}, {@code url} e {@code coletadoEm}: só nas linhas vindas da cotação (null nas demais). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(int ordem, String produto, String descricao, BigDecimal quantidade,
            BigDecimal valorUnitario, BigDecimal valorTotal, String fornecedor, String url, Instant coletadoEm) {
    }

    /** Formato exportado pelo modal de histórico do HTML v3.5 (IndexedDB). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Legado(int versao, List<RegistroLegado> registros) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegistroLegado(String data, String titulo, String loja_num, String loja_nome, String empresa,
            String chamado_num, String total, String observacao, String fileName, byte[] pdfBlob) {
    }

    public record ResultadoImportacao(int importados, int ignorados, List<String> problemas) {
    }
}
