# -*- coding: utf-8 -*-
"""Eliminatorios e pontuacao da cotacao.

Dois estagios, nesta ordem:

1. ELIMINATORIOS - cortam o anuncio antes de qualquer pontuacao. Um anuncio sem
   reputacao ou de produto errado nao deve competir por preco, entao nem entra.
2. SCORE ponderado - so entre os que sobraram.

Todo parametro vem do dicionario `criterios` que a pagina monta. O que a pagina
nao mandar cai no PADRAO.
"""
import re
import unicodedata

PADRAO = {
    # pesos (normalizados para somar 1)
    "peso_preco": 40,
    "peso_entrega": 20,
    "peso_fornecedor": 25,
    "peso_marca": 15,
    # eliminatorios
    "nota_minima": 4.5,
    "vendidos_minimo": 100,
    "aceita_recondicionado": False,
    "exigir_full": False,
    "origem": "qualquer",   # qualquer | nacional | internacional
    "preco_max": None,
    "deve_conter": "",      # palavras obrigatorias no titulo, separadas por virgula
    "nao_pode_conter": "",  # palavras proibidas no titulo
    # pontuacao
    "pontos_full": 100,
    "pontos_frete_gratis": 60,
    "pontos_sem_frete": 20,
    "marcas_preferidas": "",  # vazio = usa MARCAS_TI
    "segmentos": "",          # ex.: "M.2, SATA" -> um top 3 por segmento
}

MARCAS_TI = ["samsung", "kingston", "western digital", "wd ", "crucial", "sandisk",
             "seagate", "adata", "lexar", "patriot", "kioxia", "micron", "intel",
             "logitech", "dell", "hp ", "lenovo", "asus", "acer", "aoc", "lg ",
             "philips", "epson", "brother", "tp-link", "intelbras", "multilaser"]

ESCALA_VOLUME = [(10000, 1.00), (5000, 0.95), (1000, 0.85),
                 (500, 0.70), (100, 0.50), (25, 0.30), (0, 0.0)]


def norm(s):
    s = unicodedata.normalize("NFKD", (s or "").lower())
    return "".join(c for c in s if not unicodedata.combining(c))


def _lista(txt):
    return [t.strip() for t in re.split(r"[,;]", txt or "") if t.strip()]


def score_volume(v):
    for limite, pontos in ESCALA_VOLUME:
        if (v or 0) >= limite:
            return pontos
    return 0.0


def tier_marca(titulo, preferidas):
    t = norm(titulo)
    if preferidas:
        return (1.00, "Preferida") if any(norm(m) in t for m in preferidas) else (0.35, "Outra")
    if any(m in t for m in MARCAS_TI):
        return 1.00, "A"
    return 0.35, "Sem marca"


def segmentar(titulo, segmentos):
    """Devolve o rotulo do segmento a que o anuncio pertence, ou None."""
    t = norm(titulo)
    for s in segmentos:
        if norm(s) in t:
            return s
    return None


def avaliar(anuncios, criterios=None):
    c = dict(PADRAO)
    c.update(criterios or {})

    deve = [norm(x) for x in _lista(c["deve_conter"])]
    nao = [norm(x) for x in _lista(c["nao_pode_conter"])]
    preferidas = _lista(c["marcas_preferidas"])
    segmentos = _lista(c["segmentos"])
    preco_max = c.get("preco_max") or None

    elegiveis, descartados = [], []
    for a in anuncios:
        t = norm(a["titulo"])
        motivo = None
        if deve and not all(p in t for p in deve):
            faltando = [p for p in deve if p not in t]
            motivo = "titulo nao contem: %s" % ", ".join(faltando)
        elif nao and any(p in t for p in nao):
            achou = [p for p in nao if p in t]
            motivo = "titulo contem termo excluido: %s" % ", ".join(achou)
        elif a["recondicionado"] and not c["aceita_recondicionado"]:
            motivo = "produto recondicionado"
        # logistica e preco antes de reputacao: sao decisoes de politica de compra,
        # e assim o motivo exibido reflete o filtro que a pessoa acabou de aplicar
        elif c["origem"] == "nacional" and a.get("internacional"):
            motivo = "envio internacional%s" % (" (%s)" % a["pais"] if a.get("pais") else "")
        elif c["origem"] == "internacional" and not a.get("internacional"):
            motivo = "envio nacional"
        elif c["exigir_full"] and not a["full"]:
            motivo = "nao e enviado pelo FULL"
        elif preco_max and a["preco"] > float(preco_max):
            motivo = "acima do teto de R$ %.2f" % float(preco_max)
        elif a["nota"] is None:
            motivo = "vendedor sem avaliacao publica"
        elif a["nota"] < float(c["nota_minima"]):
            motivo = "nota %.1f abaixo do minimo %.1f" % (a["nota"], float(c["nota_minima"]))
        # volume so elimina quando foi observado: o ML as vezes serve um layout
        # sem a quantidade vendida, e ausencia de dado nao e prova de pouca venda
        elif a["vendidos"] is not None and a["vendidos"] < int(c["vendidos_minimo"]):
            motivo = "menos de %d unidades vendidas" % int(c["vendidos_minimo"])

        if motivo:
            descartados.append(dict(a, motivo=motivo))
        else:
            elegiveis.append(dict(a))

    if not elegiveis:
        return {"elegiveis": [], "descartados": descartados, "grupos": [], "resumo": {}}

    soma = sum(float(c[k]) for k in ("peso_preco", "peso_entrega", "peso_fornecedor", "peso_marca")) or 1
    w = {k: float(c[k]) / soma for k in ("peso_preco", "peso_entrega", "peso_fornecedor", "peso_marca")}

    # o score de preco compara dentro do segmento: M.2 nao concorre com SATA
    for a in elegiveis:
        a["segmento"] = segmentar(a["titulo"], segmentos) if segmentos else None

    def menor_do_grupo(seg):
        return min(x["preco"] for x in elegiveis if x["segmento"] == seg)

    for a in elegiveis:
        pmin = menor_do_grupo(a["segmento"])
        s_preco = pmin / a["preco"]
        if a["full"]:
            s_entrega = float(c["pontos_full"]) / 100
        elif a["frete_gratis"]:
            s_entrega = float(c["pontos_frete_gratis"]) / 100
        else:
            s_entrega = float(c["pontos_sem_frete"]) / 100
        nota_norm = max(0.0, a["nota"] - 4.0)
        if a["vendidos"] is None:
            # sem volume observado, a nota carrega os 100% da reputacao em vez de
            # 60% - assim o anuncio nao e penalizado por um dado que a pagina nao deu
            s_forn = min(1.0, nota_norm + (0.10 if a["loja_oficial"] else 0))
        else:
            s_forn = min(1.0, 0.60 * nota_norm + 0.40 * score_volume(a["vendidos"])
                         + (0.10 if a["loja_oficial"] else 0))
        s_marca, tier = tier_marca(a["titulo"], preferidas)
        a["tier"] = tier
        a["parciais"] = {"preco": round(s_preco * 100), "entrega": round(s_entrega * 100),
                         "fornecedor": round(s_forn * 100), "marca": round(s_marca * 100)}
        a["score"] = round((w["peso_preco"] * s_preco + w["peso_entrega"] * s_entrega
                            + w["peso_fornecedor"] * s_forn + w["peso_marca"] * s_marca) * 100, 1)

    elegiveis.sort(key=lambda x: -x["score"])

    grupos = []
    rotulos = segmentos + [None] if segmentos else [None]
    for seg in rotulos:
        itens = [a for a in elegiveis if a["segmento"] == seg]
        if not itens:
            continue
        precos = [a["preco"] for a in itens]
        grupos.append({
            "rotulo": seg or ("Sem segmento" if segmentos else "Todos os resultados"),
            "sem_segmento": seg is None and bool(segmentos),
            "qtd": len(itens),
            "menor_preco": min(precos),
            "preco_medio": round(sum(precos) / len(precos), 2),
            "top3": itens[:3],
            "demais": itens[3:],
        })

    precos = [a["preco"] for a in elegiveis]
    ordenados = sorted(precos)
    resumo = {
        "analisados": len(anuncios),
        "elegiveis": len(elegiveis),
        "descartados": len(descartados),
        "menor_preco": min(precos),
        "maior_preco": max(precos),
        "mediana": ordenados[len(ordenados) // 2],
        "preco_medio": round(sum(precos) / len(precos), 2),
        "pesos_efetivos": {k: round(v * 100) for k, v in w.items()},
        "sem_volume": sum(1 for a in elegiveis if a["vendidos"] is None),
        "internacionais": sum(1 for a in elegiveis if a.get("internacional")),
    }
    return {"elegiveis": elegiveis, "descartados": descartados,
            "grupos": grupos, "resumo": resumo, "criterios": c}
