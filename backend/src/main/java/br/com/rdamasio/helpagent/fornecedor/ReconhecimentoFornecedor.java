package br.com.rdamasio.helpagent.fornecedor;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import br.com.rdamasio.helpagent.common.Cnpj;

/**
 * Regra para achar o fornecedor cadastrado a partir do que a IA leu (nome e CNPJ como aparecem no documento).
 * Conservadora de propósito: errar juntando dois fornecedores diferentes é pior que cadastrar um repetido (o
 * repetido se corrige na tela de fornecedores; o juntado some no histórico).
 * <ol>
 *   <li><b>CNPJ</b> válido e igual → é ele;</li>
 *   <li><b>nome</b>: a "chave" (minúsculas, sem acento, sem pontuação e sem sufixo societário — LTDA, ME, EPP,
 *       EIRELI, S/A...) igual à do nome ou de um apelido;</li>
 *   <li>ou a chave lida <b>começa</b> pela chave cadastrada, em palavra inteira e com pelo menos 5 letras
 *       ("kabum comercio eletronico" → "kabum"). Nunca o contrário, nem "contém no meio".</li>
 * </ol>
 */
public final class ReconhecimentoFornecedor {

    private static final Set<String> SUFIXOS = Set.of("ltda", "limitada", "me", "epp", "eireli", "sa", "ss", "mei",
            "cia", "e", "de", "da", "do");

    private ReconhecimentoFornecedor() {
    }

    /** "KABUM! Comércio Eletrônico S.A." → "kabum comercio eletronico". */
    public static String chave(String nome) {
        if (nome == null) return "";
        String s = Normalizer.normalize(nome.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        // "S.A.", "S/A", "S A" viram "sa" antes de tirar a pontuação (senão sobra "s a" e não sai como sufixo)
        s = s.replaceAll("(?<![a-z0-9])s\\s*[./]?\\s*a\\.?(?![a-z0-9])", " sa ").replaceAll("[^a-z0-9]+", " ").strip();
        // tira sufixos societários só do FIM (um "e" no meio do nome é parte dele)
        String[] p = s.split(" ");
        int fim = p.length;
        while (fim > 1 && SUFIXOS.contains(p[fim - 1])) fim--;
        return String.join(" ", java.util.Arrays.copyOf(p, fim)).strip();
    }

    public static Optional<Fornecedor> achar(Collection<Fornecedor> cadastrados, String nomeLido, String cnpjLido) {
        String cnpj = cnpjLido != null && Cnpj.valido(cnpjLido) ? Cnpj.formatar(cnpjLido) : null;
        if (cnpj != null) {
            Optional<Fornecedor> porCnpj = cadastrados.stream().filter(f -> cnpj.equals(f.getCnpj())).findFirst();
            if (porCnpj.isPresent()) return porCnpj;
        }
        String lida = chave(nomeLido);
        if (lida.isEmpty()) return Optional.empty();
        Optional<Fornecedor> exato = cadastrados.stream()
                .filter(f -> lida.equals(chave(f.getNome())) || f.getApelidos().stream().anyMatch(a -> lida.equals(chave(a))))
                .findFirst();
        if (exato.isPresent()) return exato;
        // prefixo em palavra inteira: o cadastrado mais longo ganha ("kabum" vs "kabum marketplace")
        return cadastrados.stream()
                .filter(f -> {
                    String c = chave(f.getNome());
                    return c.replace(" ", "").length() >= 5 && lida.startsWith(c + " ");
                })
                .max(java.util.Comparator.comparingInt(f -> chave(f.getNome()).length()));
    }
}
