package br.com.rdamasio.helpagent.template;

/** Os impressos oficiais — um por grupo de empresas. O PDF de cada um fica em {@code pdf-templates/}. */
public enum TemplateCodigo {
    TD("TD MOTOPEÇAS"),
    DAM("DAMÁSIO"),
    RDAM("R DAMÁSIO"),
    CPL("CPL MOTOPARTS");

    private final String rotulo;

    TemplateCodigo(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
