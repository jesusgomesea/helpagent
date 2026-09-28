package br.com.rdamasio.helpagent.extracao;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Transforma o texto da IA em {@link DadosExtraidos}, tolerando cercas de markdown e texto em volta. */
@Component
public class RespostaIaParser {

    private final JsonMapper json;

    public RespostaIaParser(JsonMapper json) {
        this.json = json;
    }

    public DadosExtraidos parse(String texto) {
        String limpo = texto.replaceAll("```json|```", "").trim();
        int ini = limpo.indexOf('{');
        int fim = limpo.lastIndexOf('}');
        if (ini >= 0 && fim > ini) limpo = limpo.substring(ini, fim + 1);
        try {
            return json.readValue(limpo, DadosExtraidos.class);
        } catch (JacksonException e) {
            // Outra tentativa costuma vir formatada certa — por isso é retentável.
            String amostra = texto.length() > 200 ? texto.substring(0, 200) : texto;
            throw new FalhaIa("Resposta da IA não é JSON válido: " + amostra, 200, true, e);
        }
    }
}
