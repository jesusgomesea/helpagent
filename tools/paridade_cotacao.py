# -*- coding: utf-8 -*-
"""Gera o gabarito de paridade do motor da cotação: roda o motor.py ORIGINAL do piloto em Python sobre a amostra
gravada pelo ColetaRealManualTest e salva o resultado esperado, que o MotorCotacaoParidadeTest (Java) confere.

Rodar de novo só quando a amostra for regravada ou a regra do motor mudar de propósito:

    python tools/paridade_cotacao.py

Precisa do piloto em legacy/cotacao-suprimentos/. Sem ele, o gabarito já gravado continua
valendo como teste de regressão.
"""
import json
import pathlib
import sys

RAIZ = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ / "legacy" / "cotacao-suprimentos"))
import motor  # noqa: E402  (o do piloto)

RECURSOS = RAIZ / "backend" / "src" / "test" / "resources" / "cotacao"
AMOSTRA = RECURSOS / "amostra-ssd-256gb.json"
SAIDA = RECURSOS / "paridade.json"

# a amostra real veio toda sem "vendidos" (layout compacto do ML); este cenário preenche um valor
# determinístico para exercitar o filtro e a escala de volume. A mesma fórmula está no teste Java.
VOLUMES = [None, 0, 30, 150, 800, 3000, 12000]

CENARIOS = [
    {"nome": "padrao", "volumeSintetico": False, "criterios": {}},
    {"nome": "segmentado", "volumeSintetico": False,
     "criterios": {"segmentos": "M.2, SATA", "naoPodeConter": "portátil, externo"}},
    {"nome": "rigoroso", "volumeSintetico": False,
     "criterios": {"notaMinima": 4.8, "exigirFull": True, "precoMax": 350, "origem": "nacional",
                   "pesoPreco": 90}},
    {"nome": "com_volume", "volumeSintetico": True,
     "criterios": {"marcasPreferidas": "kingston, sandisk", "deveConter": "256", "pesoFornecedor": 50}},
]

# camelCase (Java) -> snake_case (piloto)
CHAVES = {"pesoPreco": "peso_preco", "pesoEntrega": "peso_entrega", "pesoFornecedor": "peso_fornecedor",
          "pesoMarca": "peso_marca", "notaMinima": "nota_minima", "vendidosMinimo": "vendidos_minimo",
          "aceitaRecondicionado": "aceita_recondicionado", "exigirFull": "exigir_full", "origem": "origem",
          "precoMax": "preco_max", "deveConter": "deve_conter", "naoPodeConter": "nao_pode_conter",
          "pontosFull": "pontos_full", "pontosFreteGratis": "pontos_frete_gratis",
          "pontosSemFrete": "pontos_sem_frete", "marcasPreferidas": "marcas_preferidas", "segmentos": "segmentos"}


def para_piloto(a):
    return {"titulo": a["titulo"], "preco": a["preco"], "preco_de": a["precoDe"], "nota": a["nota"],
            "vendidos": a["vendidos"], "full": a["full"], "loja_oficial": a["lojaOficial"],
            "frete_gratis": a["freteGratis"], "recondicionado": a["recondicionado"],
            "internacional": a["internacional"], "pais": a["pais"], "vendedor": a["vendedor"],
            "url": a["url"], "patrocinado": a["patrocinado"], "fonte": a["fonte"]}


def main():
    amostra = [para_piloto(a) for a in json.loads(AMOSTRA.read_text(encoding="utf-8"))]
    saida = []
    for cen in CENARIOS:
        anuncios = [dict(a) for a in amostra]
        if cen["volumeSintetico"]:
            for i, a in enumerate(anuncios):
                a["vendidos"] = VOLUMES[i % len(VOLUMES)]
        r = motor.avaliar(anuncios, {CHAVES[k]: v for k, v in cen["criterios"].items()})
        res = r["resumo"]
        saida.append({
            **cen,
            "elegiveis": [{"titulo": a["titulo"], "score": a["score"], "parciais": a["parciais"],
                           "tier": a["tier"], "segmento": a["segmento"]} for a in r["elegiveis"]],
            "descartados": [{"titulo": a["titulo"], "motivo": a["motivo"]} for a in r["descartados"]],
            "grupos": [{"rotulo": g["rotulo"], "qtd": g["qtd"], "menorPreco": g["menor_preco"],
                        "precoMedio": g["preco_medio"], "top3": [a["titulo"] for a in g["top3"]]}
                       for g in r["grupos"]],
            "resumo": res and {"mediana": res["mediana"], "precoMedio": res["preco_medio"],
                               "pesosEfetivos": res["pesos_efetivos"], "semVolume": res["sem_volume"]},
        })
        print("%-11s elegiveis=%d descartados=%d grupos=%d"
              % (cen["nome"], len(r["elegiveis"]), len(r["descartados"]), len(r["grupos"])))
    SAIDA.write_text(json.dumps(saida, ensure_ascii=False, indent=1), encoding="utf-8")
    print("gabarito:", SAIDA)


if __name__ == "__main__":
    main()
