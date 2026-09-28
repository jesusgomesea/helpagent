"""Extrai do HTML legado (v3.5) os dados que viviam embutidos no arquivo.

Gera, dentro do backend:
  - src/main/resources/pdf-templates/<CODIGO>.pdf  (os 4 impressos, antes em base64)
  - src/main/resources/db/migration/V2__seed_lojas.sql  (as lojas do CADLOJAS)

Uso (a partir da raiz do repositório):
    python tools/extrair_legado.py legacy/HELP-AGENT-ORCAMENTO-v3_5.html

Rodar de novo sobrescreve os arquivos gerados — por isso o seed é uma migration
própria, fácil de regerar enquanto o projeto não foi para produção. Depois do
go-live, mudança de loja entra por migration nova ou pela tela de administração.
"""
import base64
import json
import re
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
RECURSOS = RAIZ / "backend" / "src" / "main" / "resources"


def extrair_lojas(html: str) -> list[dict]:
    m = re.search(r"const CADLOJAS = (\[.*?\]);", html)
    if not m:
        sys.exit("CADLOJAS não encontrado no HTML")
    return json.loads(m.group(1))


def extrair_templates(html: str) -> dict[str, bytes]:
    m = re.search(r"const TEMPLATES_B64 = \{(.*?)\n\};", html, re.S)
    if not m:
        sys.exit("TEMPLATES_B64 não encontrado no HTML")
    templates = {}
    for codigo, b64 in re.findall(r"(\w+):\s*`([^`]+)`", m.group(1)):
        dados = base64.b64decode(b64)
        if not dados.startswith(b"%PDF"):
            sys.exit(f"template {codigo} não decodificou para um PDF")
        templates[codigo] = dados
    return templates


def sql_str(valor: str) -> str:
    return "'" + valor.replace("'", "''") + "'"


def gerar_seed(lojas: list[dict]) -> str:
    linhas = [
        "-- GERADO por tools/extrair_legado.py a partir do CADLOJAS do HTML v3.5. Não editar à mão.",
        "INSERT INTO loja (numero, nome, cnpj, empresa, template_codigo) VALUES",
    ]
    valores = [
        f"  ({int(l['n'])}, {sql_str(l['nome'])}, {sql_str(l['cnpj'])}, "
        f"{sql_str(l['empresa'])}, {sql_str(l['template'])})"
        for l in lojas
    ]
    return "\n".join(linhas) + "\n" + ",\n".join(valores) + ";\n"


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    html = Path(sys.argv[1]).read_text(encoding="utf-8")

    lojas = extrair_lojas(html)
    seed = RECURSOS / "db" / "migration" / "V2__seed_lojas.sql"
    seed.parent.mkdir(parents=True, exist_ok=True)
    seed.write_text(gerar_seed(lojas), encoding="utf-8")
    print(f"{len(lojas)} lojas -> {seed.relative_to(RAIZ)}")

    destino = RECURSOS / "pdf-templates"
    destino.mkdir(parents=True, exist_ok=True)
    for codigo, dados in extrair_templates(html).items():
        arquivo = destino / f"{codigo}.pdf"
        arquivo.write_bytes(dados)
        print(f"template {codigo} ({len(dados) // 1024} KB) -> {arquivo.relative_to(RAIZ)}")


if __name__ == "__main__":
    main()
