"""Avalia a precisão e o tempo da leitura por IA contra orçamentos REAIS do histórico.

Para que serve: antes de trocar modelo, nível de raciocínio, formato de resposta ou o prompt, medir se o
valor lido continua certo — em especial o preço à vista em prints de e-commerce — e quanto tempo leva.

Como funciona:
  1. Baixa do backend em execução o PDF de cada orçamento do histórico escolhido.
  2. Separa só as páginas de orçamento (a faixa carimbada no topo diz "Orçamento 1/3", "Orçamento original").
  3. Manda essas páginas ao Gemini com o mesmo prompt do sistema (modo CAPEX: só lê os orçamentos).
  4. Compara o total lido com o total que o atendente conferiu e gravou (o gabarito).

Uso (backend rodando; chave lida de backend/config/application-local.yml ou da variável GEMINI_API_KEY):
    python tools/avaliar_extracao.py --ids 36 40 51 --configs atual minimal schema
    python tools/avaliar_extracao.py --ids 193 --paralelo      # compara 1 chamada x N em paralelo

Cada chamada gasta 1 requisição da cota do Gemini. O script para ao primeiro 429 de cota.
Os resultados (dados reais da empresa) vão para a pasta indicada em --saida, fora do repositório.
"""
import argparse
import base64
import concurrent.futures as cf
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from pypdf import PdfReader, PdfWriter

RAIZ = Path(__file__).resolve().parent.parent
PROMPT = RAIZ / "backend/src/main/resources/prompts/extracao.txt"
URL_GEMINI = "https://generativelanguage.googleapis.com/v1beta/models/{m}:generateContent"

# Mesmos trechos que PromptExtracao.java usa no modo CAPEX. Se o prompt mudar lá, mudar aqui também.
CAPEX = {
    "abertura": "Analise o(s) {n} orçamento(s) de fornecedor(es) anexado(s). Esta é uma aquisição CAPEX "
                "(investimento sem chamado de referência).",
    "campo_chamado": "", "campo_loja_num": "", "campo_loja_nome": "",
    "campo_titulo": "título curto e técnico para o orçamento (baseado no conjunto dos orçamentos)",
    "campo_observacao": "frase curta e genérica em uma linha começando com 'CAPEX. ', no formato "
                        "'natureza da despesa + objeto'. Exemplos válidos: 'CAPEX. Aquisição de servidor para datacenter.', "
                        "'CAPEX. Aquisição de switches gerenciáveis para rede corporativa.', 'CAPEX. Renovação de parque de notebooks.'. "
                        "NUNCA escreva parágrafos longos, NUNCA mencione chamado (não existe), NUNCA justifique. "
                        "Máximo 1 frase após 'CAPEX.'.",
    "regra_extra": "\n- Como não há chamado, deixe chamado_num, loja_num e loja_nome como string vazia "
                   "— o usuário preencherá loja manualmente.",
}

# Formato fixo de resposta (structured output). Espelha DadosExtraidos.java e GeminiClient.ESQUEMA_RESPOSTA.
SCHEMA = {
    "type": "object",
    "properties": {
        "chamado_num": {"type": "string"}, "loja_num": {"type": "string"}, "loja_nome": {"type": "string"},
        "titulo": {"type": "string"},
        "itens": {"type": "array", "items": {"type": "object", "properties": {
            "produto": {"type": "string"}, "descricao": {"type": "string"}, "qtd": {"type": "string"},
            "valor_unit": {"type": "string"}, "valor_total": {"type": "string"}, "fonte": {"type": "string"},
            "fornecedor": {"type": "string"}, "fornecedor_cnpj": {"type": "string"}},
            "required": ["produto", "descricao", "qtd", "valor_unit", "valor_total", "fonte", "fornecedor",
                         "fornecedor_cnpj"]}},
        "total": {"type": "string"}, "observacao": {"type": "string"},
        "validade_ate": {"type": "string"}, "validade_dias": {"type": "string"},
    },
    "required": ["titulo", "itens", "total", "observacao", "validade_ate", "validade_dias"],
}

CONFIGS = {
    "atual": {"thinkingConfig": {"thinkingLevel": "low"}, "responseMimeType": "application/json"},
    "minimal": {"thinkingConfig": {"thinkingLevel": "minimal"}, "responseMimeType": "application/json"},
    "schema": {"thinkingConfig": {"thinkingLevel": "low"}, "responseMimeType": "application/json",
               "responseJsonSchema": SCHEMA},
}


class CotaEsgotada(Exception):
    pass


def chave_api() -> str:
    if os.environ.get("GEMINI_API_KEY"):
        return os.environ["GEMINI_API_KEY"]
    cfg = (RAIZ / "backend/config/application-local.yml").read_text(encoding="utf-8")
    m = re.search(r"api-key:\s*(\S+)", cfg)
    if not m:
        sys.exit("Chave do Gemini não encontrada")
    return m.group(1)


def montar_prompt(n_orcamentos: int) -> str:
    texto = PROMPT.read_text(encoding="utf-8")
    valores = dict(CAPEX, abertura=CAPEX["abertura"].format(n=n_orcamentos), total_orcs=str(n_orcamentos))
    for k, v in valores.items():
        texto = texto.replace("{{" + k + "}}", v)
    return texto


def brl(texto) -> float:
    if texto is None:
        return 0.0
    s = re.sub(r"[R$\s ]", "", str(texto)).replace(".", "").replace(",", ".")
    try:
        return round(float(s), 2)
    except ValueError:
        return 0.0


def get_json(url: str):
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.load(r)


def paginas_de_orcamento(pdf_bytes: bytes) -> list[bytes]:
    """Um PDF por orçamento anexado: da página com a faixa 'Orçamento…' até antes da próxima faixa.

    O impresso é sempre formulário → chamado(s) → orçamento(s). A faixa é desenhada por último, então
    aparece em qualquer ponto do texto extraído (não só no começo). Página sem faixa depois de um
    orçamento é continuação do mesmo PDF de fornecedor.
    """
    leitor = PdfReader(io.BytesIO(pdf_bytes))
    grupos: list[list[int]] = []
    atual: list[int] | None = None
    for i, pg in enumerate(leitor.pages):
        texto = pg.extract_text() or ""
        if re.search(r"Or[çc]amento (original|\d+/\d+)", texto):
            atual = [i]
            grupos.append(atual)
        elif atual is not None:
            atual.append(i)
    saida = []
    for g in grupos:
        w = PdfWriter()
        for i in g:
            w.add_page(leitor.pages[i])
        buf = io.BytesIO()
        w.write(buf)
        saida.append(buf.getvalue())
    return saida


def chamar(modelo: str, chave: str, pdfs: list[bytes], prompt: str, config: dict, _repetindo: bool = False) -> dict:
    parts = [{"inline_data": {"mime_type": "application/pdf", "data": base64.b64encode(p).decode()}} for p in pdfs]
    parts.append({"text": prompt})
    corpo = {"contents": [{"parts": parts}], "generationConfig": dict(config, maxOutputTokens=32768)}
    req = urllib.request.Request(URL_GEMINI.format(m=modelo), data=json.dumps(corpo).encode(),
                                 headers={"Content-Type": "application/json", "x-goog-api-key": chave})
    inicio = time.time()
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            resp = json.load(r)
    except urllib.error.HTTPError as e:
        corpo_erro = e.read().decode(errors="replace")
        if e.code == 429 and "PerDay" in corpo_erro:
            raise CotaEsgotada(corpo_erro[:200])
        if e.code == 429 and not _repetindo:
            # limite por minuto: o próprio Google diz quanto esperar ("retryDelay": "16s")
            espera = re.search(r'"retryDelay":\s*"(\d+)', corpo_erro)
            time.sleep(int(espera.group(1)) + 1 if espera else 20)
            return chamar(modelo, chave, pdfs, prompt, config, _repetindo=True)
        return {"erro": f"HTTP {e.code}: {corpo_erro[:300]}", "ms": int((time.time() - inicio) * 1000)}
    ms = int((time.time() - inicio) * 1000)
    texto = "".join(p.get("text", "") for p in resp["candidates"][0]["content"]["parts"])
    uso = resp.get("usageMetadata", {})
    limpo = re.sub(r"```json|```", "", texto).strip()
    m = re.search(r"\{.*\}", limpo, re.S)
    try:
        dados = json.loads(m.group(0) if m else limpo)
    except json.JSONDecodeError:
        return {"erro": "JSON inválido", "ms": ms, "texto": texto[:200]}
    return {"ms": ms, "dados": dados, "entrada": uso.get("promptTokenCount"),
            "saida": uso.get("candidatesTokenCount"), "raciocinio": uso.get("thoughtsTokenCount")}


def total_lido(dados: dict) -> float:
    itens = dados.get("itens") or []
    soma = round(sum(brl(i.get("valor_total")) or brl(i.get("valor_unit")) for i in itens), 2)
    return soma or brl(dados.get("total"))


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ids", nargs="+", type=int, required=True, help="ids do histórico a avaliar")
    ap.add_argument("--configs", nargs="+", default=["atual"], choices=list(CONFIGS))
    ap.add_argument("--modelo", default="gemini-3.6-flash")
    ap.add_argument("--backend", default="http://localhost")
    ap.add_argument("--paralelo", action="store_true", help="compara 1 chamada com todos x 1 por orçamento em paralelo")
    ap.add_argument("--saida", default=".", help="pasta para resultados.json (fica fora do git)")
    ap.add_argument("--seco", action="store_true", help="só separa as páginas e mostra; não chama a IA")
    a = ap.parse_args()
    chave = chave_api()

    historico = {}
    pagina = 0
    while True:
        p = get_json(f"{a.backend}/api/historico?tamanho=100&pagina={pagina}")
        historico.update({r["id"]: r for r in p["itens"]})
        if (pagina + 1) * p["tamanho"] >= p["total"]:
            break
        pagina += 1
    resultados = []
    try:
        for id_ in a.ids:
            reg = historico[id_]
            with urllib.request.urlopen(f"{a.backend}/api/historico/{id_}/pdf", timeout=60) as r:
                orcs = paginas_de_orcamento(r.read())
            if not orcs:
                print(f"#{id_}: sem páginas de orçamento identificáveis, pulando")
                continue
            esperado = round(float(reg["total"]), 2)
            print(f"\n#{id_} {reg['titulo'][:60]} · {len(orcs)} orçamento(s) · gabarito R$ {esperado:.2f}")
            if a.seco:
                print("  páginas por orçamento:", [len(PdfReader(io.BytesIO(o)).pages) for o in orcs],
                      "· KB:", [len(o) // 1024 for o in orcs])
                continue

            if a.paralelo:
                junto = chamar(a.modelo, chave, orcs, montar_prompt(len(orcs)), CONFIGS["atual"])
                inicio = time.time()
                with cf.ThreadPoolExecutor(len(orcs)) as ex:
                    partes = list(ex.map(lambda p: chamar(a.modelo, chave, [p], montar_prompt(1), CONFIGS["atual"]), orcs))
                ms_par = int((time.time() - inicio) * 1000)
                t_junto = total_lido(junto["dados"]) if "dados" in junto else None
                t_par = round(sum(total_lido(p["dados"]) for p in partes if "dados" in p), 2)
                print(f"  1 chamada com todos : {junto['ms']:>6} ms · total {t_junto}")
                print(f"  {len(orcs)} em paralelo       : {ms_par:>6} ms · total {t_par} · "
                      f"individuais {[p['ms'] for p in partes]} ms")
                resultados.append({"id": id_, "esperado": esperado, "junto": junto, "paralelo_ms": ms_par,
                                   "partes": partes})
                continue

            for nome in a.configs:
                r = chamar(a.modelo, chave, orcs, montar_prompt(len(orcs)), CONFIGS[nome])
                if "erro" in r:
                    print(f"  {nome:8} ERRO {r['erro'][:120]}")
                else:
                    lido = total_lido(r["dados"])
                    ok = abs(lido - esperado) <= 0.05
                    print(f"  {nome:8} {'OK ' if ok else 'ERR'} lido R$ {lido:>10.2f} · {r['ms']:>6} ms · "
                          f"tokens entrada={r['entrada']} saída={r['saida']} raciocínio={r['raciocinio']}")
                    r["acertou"] = ok
                    r["lido"] = lido
                resultados.append({"id": id_, "config": nome, "esperado": esperado, **r})
    except CotaEsgotada as e:
        print(f"\nCota diária esgotada — parando para não prejudicar o uso do helpdesk. ({e})")

    destino = Path(a.saida) / "resultados-avaliacao.json"
    destino.write_text(json.dumps(resultados, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    print(f"\nResultados: {destino}")


if __name__ == "__main__":
    main()
