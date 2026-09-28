# -*- coding: utf-8 -*-
"""Coleta de anuncios nos marketplaces.

O Mercado Livre bloqueia requisicao HTTP direta (devolve a pagina de trafego
suspeito) e tambem bloqueia Chrome headless. O que passa e o Chrome real com
perfil persistente; a janela sobe fora da area visivel para nao atrapalhar quem
esta usando a maquina.

Para acrescentar uma loja (Kabum, Pichau, Terabyte), escreva uma funcao
buscar_<loja>(page, termo) -> list[dict] com as mesmas chaves de um anuncio e
registre-a em FONTES. O resto do sistema nao muda.
"""
import pathlib
import re
import threading
import urllib.error
import urllib.request
from concurrent import futures

from playwright.sync_api import sync_playwright

PERFIL = str((pathlib.Path(__file__).parent / ".navegador").absolute())
_lock = threading.Lock()

CAMPOS = ("titulo", "preco", "preco_de", "nota", "vendidos", "full", "loja_oficial",
          "frete_gratis", "recondicionado", "internacional", "pais", "vendedor",
          "url", "patrocinado", "fonte")

UA_HTTP = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
           "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")


def _slug(termo):
    s = re.sub(r"[^\w\s-]", "", termo.lower(), flags=re.UNICODE)
    return re.sub(r"[\s_]+", "-", s.strip())


# --------------------------------------------------------------- extracao ML
_JS_EXTRAIR = r"""
() => {
  const num = s => {
    if (!s) return null;
    const v = parseFloat(String(s).replace(/\./g, '').replace(',', '.'));
    return isNaN(v) ? null : v;
  };
  // textContent, nao innerText: .andes-visually-hidden e escondido por CSS e
  // innerText devolve string vazia em elemento nao renderizado.
  const txt = e => (e && e.textContent ? e.textContent.replace(/\s+/g, ' ').trim() : '');
  const out = [];
  document.querySelectorAll('li.ui-search-layout__item').forEach(c => {
    const a = c.querySelector('.poly-component__title');
    if (!a) return;
    const hid = [...c.querySelectorAll('.andes-visually-hidden')]
                  .map(txt).join(' | ');
    const tudo = txt(c);
    const uses  = [...c.querySelectorAll('use')].map(u => u.getAttribute('href') || '');

    // O ML serve variantes de layout diferentes para a mesma busca. Tentamos as
    // duas: a completa (texto acessivel) e a compacta (so a nota num chip).
    // O que nao aparece fica null - desconhecido nao e zero.
    let nota = null;
    let m = hid.match(/Classificação ([\d,\.]+) de 5/) || tudo.match(/([\d],[\d]) de 5 estrelas/);
    if (m) nota = parseFloat(m[1].replace(',', '.'));
    if (nota === null) {
      const chip = c.querySelector('.poly-component__review-compacted, .poly-reviews__rating');
      if (chip) {
        const v = parseFloat(txt(chip).replace(',', '.'));
        if (!isNaN(v) && v >= 0 && v <= 5) nota = v;
      }
    }

    let vendidos = null;
    let mv = hid.match(/Mais de (\d+mil|\d+) produtos vendidos/)
          || tudo.match(/\+\s?(\d+mil|\d+)\s+vendidos/);
    if (!mv) {
      const sq = c.querySelector('.poly-component__sold-quantity');
      if (sq) mv = txt(sq).match(/(\d+mil|\d+)/);
    }
    if (mv) {
      vendidos = mv[1].endsWith('mil') ? parseInt(mv[1]) * 1000 : parseInt(mv[1]);
    }
    const f     = c.querySelector('.poly-price__current .andes-money-amount__fraction');
    const ce    = c.querySelector('.poly-price__current .andes-money-amount__cents');
    const velho = c.querySelector('s .andes-money-amount__fraction');

    // envio internacional: o ML marca com um texto acessivel "Internacional <pais>"
    // e um chip visivel com o nome do pais
    const mInt = hid.match(/Internacional\s+([A-Za-zÀ-ÿ][A-Za-zÀ-ÿ ]*)/)
              || tudo.match(/Internacional\s+([A-Za-zÀ-ÿ][A-Za-zÀ-ÿ ]*)/);
    const internacional = !!mInt || /Enviado do exterior|Importado de/i.test(tudo);
    // o textContent do card cola os nos sem separador, entao a captura do pais
    // vem grudada no rotulo seguinte ("China Enviado pelo FULL") - cortamos ali
    const pais = mInt
      ? mInt[1].replace(/\s*(Enviado|Frete|Chegar|Entrega|Vendido|Dispon|Outra)\b.*$/i, '').trim()
      : '';

    // Anuncio patrocinado nao expoe a URL do produto no DOM - so um link de
    // rastreamento (click1...), que responde 302 para o anuncio de verdade.
    // Guardamos esse link inteiro, com a query, e o Python resolve o destino.
    let url = '', patrocinado = false;
    const link = c.querySelector('a.poly-component__title') || c.querySelector('a');
    if (link) {
      try {
        const x = new URL(link.href);
        patrocinado = x.hostname.includes('click1');
        url = patrocinado ? link.href : x.origin + x.pathname;
      } catch (e) {}
    }
    out.push({
      titulo: txt(a),
      preco: num(f ? txt(f) + (ce ? ',' + txt(ce) : '') : null),
      preco_de: num(velho ? txt(velho) : null),
      nota: nota,
      vendidos: vendidos,
      full: uses.includes('#poly_full'),
      loja_oficial: uses.includes('#poly_cockade'),
      frete_gratis: /Frete grátis/.test(hid) || /Frete grátis/.test(tudo),
      recondicionado: /recondicionado/i.test(tudo),
      internacional: internacional,
      pais: pais,
      vendedor: txt(c.querySelector('.poly-component__seller')),
      url: url,
      patrocinado: patrocinado,
      fonte: 'Mercado Livre'
    });
  });
  return out;
}
"""


def buscar_mercadolivre(page, termo, paginas=1):
    anuncios = []
    for p in range(paginas):
        desde = p * 50 + 1
        url = "https://lista.mercadolivre.com.br/" + _slug(termo)
        if desde > 1:
            url += "_Desde_%d" % desde
        page.goto(url, timeout=60000)
        try:
            page.wait_for_selector("li.ui-search-layout__item", timeout=45000)
        except Exception:
            break
        anuncios.extend(page.evaluate(_JS_EXTRAIR))
    return anuncios


FONTES = {"mercadolivre": buscar_mercadolivre}


class _SemRedirect(urllib.request.HTTPRedirectHandler):
    """Interrompe no primeiro 3xx para ler o Location em vez de seguir."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(req.full_url, code, newurl, headers, fp)


def _destino_do_tracking(url, timeout=10):
    """Troca o link de rastreamento pela URL real do anuncio.

    O endpoint click1 responde 302 apontando para o produto. Devolve a URL
    limpa, ou None se nao der - e ai o chamador mantem o link de rastreamento,
    que tambem abre o anuncio, so que feio e com prazo de validade.
    """
    op = urllib.request.build_opener(_SemRedirect)
    try:
        r = op.open(urllib.request.Request(url, headers={"User-Agent": UA_HTTP}), timeout=timeout)
        r.close()
        return None
    except urllib.error.HTTPError as e:
        destino = e.reason if isinstance(e.reason, str) else e.headers.get("Location", "")
        if destino and destino.startswith("http"):
            return destino.split("?")[0].split("#")[0]
    except Exception:
        pass
    return None


def resolver_patrocinados(anuncios, timeout=10, paralelos=8):
    """Resolve em paralelo os links de rastreamento dos anuncios patrocinados."""
    alvos = [a for a in anuncios if a.get("patrocinado") and a.get("url")]
    if not alvos:
        return 0
    resolvidos = 0
    with futures.ThreadPoolExecutor(max_workers=paralelos) as ex:
        mapa = {ex.submit(_destino_do_tracking, a["url"], timeout): a for a in alvos}
        for fut in futures.as_completed(mapa, timeout=timeout * 3):
            a = mapa[fut]
            try:
                destino = fut.result()
            except Exception:
                destino = None
            if destino:
                a["url"] = destino
                resolvidos += 1
    return resolvidos


def coletar(termo, fontes=("mercadolivre",), paginas=1):
    """Roda a coleta e devolve (anuncios, avisos).

    Serializado por lock: o Chrome sobe uma vez por cotacao e uma cotacao nao
    atropela a outra.
    """
    anuncios, avisos = [], []
    with _lock:
        with sync_playwright() as pw:
            ctx = pw.chromium.launch_persistent_context(
                PERFIL, channel="chrome", headless=False, locale="pt-BR",
                viewport={"width": 1440, "height": 900},
                args=["--disable-blink-features=AutomationControlled",
                      "--window-position=-3000,-3000",
                      "--window-size=1440,900",
                      "--no-first-run", "--no-default-browser-check"])
            try:
                page = ctx.pages[0] if ctx.pages else ctx.new_page()
                for nome in fontes:
                    fn = FONTES.get(nome)
                    if not fn:
                        avisos.append("fonte desconhecida: %s" % nome)
                        continue
                    try:
                        achados = fn(page, termo, paginas)
                        if not achados:
                            avisos.append("%s nao devolveu resultados para o termo" % nome)
                        anuncios.extend(achados)
                    except Exception as e:
                        avisos.append("%s falhou: %s" % (nome, type(e).__name__))
            finally:
                ctx.close()

    # patrocinado tambem precisa de link clicavel: troca o rastreamento pelo destino
    try:
        n = resolver_patrocinados(anuncios)
        faltou = sum(1 for a in anuncios if a.get("patrocinado")) - n
        if faltou > 0:
            avisos.append("%d link(s) patrocinado(s) mantidos como redirecionamento" % faltou)
    except Exception:
        avisos.append("nao foi possivel resolver os links patrocinados; "
                      "eles abrem por redirecionamento")

    # dedup por titulo mantendo o menor preco
    melhor = {}
    for a in anuncios:
        if not a.get("preco"):
            continue
        a["vendedor"] = (a.get("vendedor") or "").strip()
        k = a["titulo"].strip().lower()
        if k not in melhor or a["preco"] < melhor[k]["preco"]:
            melhor[k] = a
    return list(melhor.values()), avisos
