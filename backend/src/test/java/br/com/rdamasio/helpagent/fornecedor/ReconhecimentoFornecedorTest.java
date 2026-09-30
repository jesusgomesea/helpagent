package br.com.rdamasio.helpagent.fornecedor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Regra de reconhecimento de fornecedor. Conservadora: juntar dois fornecedores diferentes é pior que cadastrar
 * um repetido — por isso também há casos que NÃO podem casar.
 */
class ReconhecimentoFornecedorTest {

    private static Fornecedor f(String nome, String cnpj, String... apelidos) {
        Fornecedor x = new Fornecedor(nome, cnpj, Instant.now());
        x.atualizar(nome, cnpj, List.of(apelidos));
        return x;
    }

    private final List<Fornecedor> cadastro = List.of(
            f("Kabum", null),
            f("Infotec Soluções", "34.336.083/0001-06", "INFOTEC SOLUCOES EM INFORMATICA LTDA"),
            f("Tecno", null));

    @Test
    void chaveTiraAcentoPontuacaoESufixoSocietario() {
        assertThat(ReconhecimentoFornecedor.chave("KABUM! Comércio Eletrônico S.A.")).isEqualTo("kabum comercio eletronico");
        assertThat(ReconhecimentoFornecedor.chave("Infotec Soluções em Informática LTDA - ME")).isEqualTo("infotec solucoes em informatica");
    }

    @Test
    void cnpjValidoGanhaDeTudo() {
        assertThat(ReconhecimentoFornecedor.achar(cadastro, "nome qualquer", "34336083000106"))
                .map(Fornecedor::getNome).contains("Infotec Soluções");
    }

    @Test
    void apelidoAprendidoReconhece() {
        assertThat(ReconhecimentoFornecedor.achar(cadastro, "Infotec Soluções em Informática Ltda.", null))
                .map(Fornecedor::getNome).contains("Infotec Soluções");
    }

    @Test
    void nomeLidoQueComecaPeloCadastradoReconhece() {
        assertThat(ReconhecimentoFornecedor.achar(cadastro, "KABUM COMERCIO ELETRONICO S.A.", null))
                .map(Fornecedor::getNome).contains("Kabum");
    }

    @Test
    void naoJuntaNomeCurtoNemPalavraNoMeio() {
        // "Tecno" tem 5 letras mas "tecnologia" não é "tecno " em palavra inteira
        assertThat(ReconhecimentoFornecedor.achar(cadastro, "Tecnologia Avançada Ltda", null)).isEmpty();
        // o cadastrado no meio do nome lido não conta
        assertThat(ReconhecimentoFornecedor.achar(cadastro, "Loja do Kabum", null)).isEmpty();
    }

    @Test
    void apelidoNaoRepeteNemDuplicaONome() {
        Fornecedor k = f("Kabum", null);
        assertThat(k.aprenderApelido("KABUM COMERCIO ELETRONICO S.A.")).isTrue();
        assertThat(k.aprenderApelido("Kabum Comércio Eletrônico SA")).isFalse(); // mesma chave
        assertThat(k.aprenderApelido("KABUM")).isFalse(); // é o próprio nome
        assertThat(k.getApelidos()).hasSize(1);
    }
}
