// Extrai os anúncios da página de busca do Mercado Livre. Roda DENTRO da página (page.evaluate).
// Copiado sem alteração do piloto em Python (legacy/cotacao-suprimentos/coletor.py, _JS_EXTRAIR, 28/09/2026).
// Quando o ML mudar o layout é AQUI que se mexe — tabela de seletores em docs/MANUTENCAO.md §7.
// Regra: textContent, nunca innerText (.andes-visually-hidden é escondido por CSS e innerText volta vazio).
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
