package br.com.rdamasio.helpagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.rdamasio.helpagent.common.Cnpj;
import br.com.rdamasio.helpagent.extracao.DadosExtraidos;
import br.com.rdamasio.helpagent.extracao.ExtracaoServiceAcesso;
import br.com.rdamasio.helpagent.historico.ChamadosJaOrcados;

/** Regras pequenas mas que saem no impresso ou disparam avisos: CNPJ, validade, números de chamado. */
class RegrasNovasTest {

    @Test
    void cnpjValidaPelosDigitosVerificadores() {
        assertThat(Cnpj.valido("34.336.083/0001-06")).isTrue();   // DAMASIO PE, do cadastro
        assertThat(Cnpj.valido("34336083000106")).isTrue();
        assertThat(Cnpj.valido("34.336.083/0001-07")).isFalse();  // um dígito trocado
        assertThat(Cnpj.valido("11.111.111/1111-11")).isFalse();  // repetido passa na conta, mas não existe
        assertThat(Cnpj.valido("123")).isFalse();
        assertThat(Cnpj.formatar("34336083000106")).isEqualTo("34.336.083/0001-06");
    }

    @Test
    void numerosDeChamadoSaemDoTextoLivre() {
        assertThat(ChamadosJaOrcados.numeros("# 1021069, 1021070. Manutenção")).containsExactly("1021069", "1021070");
        assertThat(ChamadosJaOrcados.numeros("#12")).isEmpty(); // curto demais para ser chamado
        assertThat(ChamadosJaOrcados.numeros(null)).isEmpty();
    }

    @Test
    void validadeUsaDataEscritaPrimeiroDepoisDiasENuncaInventa() {
        LocalDate hoje = LocalDate.of(2026, 9, 25);
        assertThat(ExtracaoServiceAcesso.validade(dados("31/10/2026", "7"), hoje)).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(ExtracaoServiceAcesso.validade(dados("", "7"), hoje)).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(ExtracaoServiceAcesso.validade(dados("", "7 dias"), hoje)).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(ExtracaoServiceAcesso.validade(dados("", ""), hoje)).isNull();        // e-commerce: sem validade
        assertThat(ExtracaoServiceAcesso.validade(dados("em breve", ""), hoje)).isNull(); // formato estranho: sem chute
    }

    private static DadosExtraidos dados(String ate, String dias) {
        return new DadosExtraidos("", "", "", "t", List.of(), "", "", ate, dias);
    }
}
