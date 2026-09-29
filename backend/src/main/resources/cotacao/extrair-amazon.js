// Amazon — cartões div[data-component-type="s-search-result"][data-asin] (mapeado em 28/09/2026):
//   h2 = título (aria-label "Anúncio patrocinado – ..." nos pagos) · .a-price:not(.a-text-price) .a-offscreen = preço
//   .a-price[data-a-strike="true"] = "de" (atenção: .a-text-price sem strike é o valor da PARCELA, não usar)
//   span.a-icon-alt "4,5 de 5 estrelas" = nota · "Mais de 1 mil compras no mês passado" = vendidos (aproximado)
//   i.a-icon-prime = entrega Prime (conta como entrega rápida, o "full") · link limpo = /dp/<ASIN>
//   "Atualmente indisponível" / "Indisponível" no cartão → indisponível (descartado antes do ranking)
() => {
  const txt = e => (e && e.textContent ? e.textContent.replace(/\s+/g, ' ').trim() : '');
  const dinheiro = s => {
    const m = String(s || '').replace(/ /g, ' ').match(/R\$\s*([\d.]+,\d{2})/);
    return m ? parseFloat(m[1].replace(/\./g, '').replace(',', '.')) : null;
  };
  const out = [];
  document.querySelectorAll('div[data-component-type="s-search-result"][data-asin]').forEach(c => {
    const asin = c.getAttribute('data-asin');
    const h2 = c.querySelector('h2');
    if (!asin || !h2) return;
    const rotulo = h2.getAttribute('aria-label') || '';
    const patrocinado = /patrocinado/i.test(rotulo) || !!c.querySelector('.puis-sponsored-label-text, .s-sponsored-label-text');
    const titulo = txt(h2.querySelector('span')) || txt(h2);
    if (/esgotad|indispon[íi]vel|sem estoque|avise-me|temporariamente indispon/i.test(txt(c))) { out.push({ titulo: titulo, indisponivel: true }); return; }
    const preco = dinheiro(txt(c.querySelector('.a-price:not(.a-text-price) .a-offscreen')));
    if (!preco) return;
    const de = dinheiro(txt(c.querySelector('.a-price[data-a-strike="true"] .a-offscreen')));
    const mNota = txt(c.querySelector('span.a-icon-alt')).match(/([\d,.]+) de 5/);
    const mCompras = txt(c).match(/Mais de (\d+)\s*(mil)?\s+compras?/i);
    out.push({
      titulo: titulo,
      preco: preco,
      preco_de: de && de > preco + 0.005 ? de : null,
      nota: mNota ? parseFloat(mNota[1].replace(',', '.')) : null,
      vendidos: mCompras ? parseInt(mCompras[1], 10) * (mCompras[2] ? 1000 : 1) : null,
      full: !!c.querySelector('i.a-icon-prime, .a-icon-prime'),
      loja_oficial: false,
      frete_gratis: /frete gr[áa]tis/i.test(txt(c)),
      recondicionado: /recondicionad|renovado|seminov/i.test(titulo),
      internacional: /internacional|enviado do exterior/i.test(txt(c)),
      pais: '',
      vendedor: '',
      url: 'https://www.amazon.com.br/dp/' + asin,
      patrocinado: patrocinado,
      fonte: 'Amazon',
      vendedor_proprio: false
    });
  });
  return out;
}
