// Terabyte — cartões .product-item, que trazem os dados em atributos (mapeado em 28/09/2026):
//   data-tss-price = preço à vista no Pix · data-tss-brand = marca · data-tss-estoque = "1" em estoque, "0" esgotado
//   (esgotado também leva a etiqueta .esgotadoL "Esgotado" — na busca "ssd 256gb" eram 223 de 300 cartões)
//   a.product-item__name (href, title) · .product-item__old-price = "de" · .tss-rating-value/-count = avaliação
// Use textContent, nunca innerText: parte do cartão fica escondida por CSS e innerText volta vazio.
() => {
  const txt = e => (e && e.textContent ? e.textContent.replace(/\s+/g, ' ').trim() : '');
  const dinheiro = s => {
    const m = String(s || '').match(/R\$\s*([\d.]+,\d{2})/);
    return m ? parseFloat(m[1].replace(/\./g, '').replace(',', '.')) : null;
  };
  const out = [];
  document.querySelectorAll('.product-item').forEach(c => {
    const link = c.querySelector('a.product-item__name');
    if (!link) return;
    const titulo0 = link.getAttribute('title') || txt(link);
    if (c.getAttribute('data-tss-estoque') === '0' || c.querySelector('.esgotadoL')) {
      out.push({ titulo: titulo0, indisponivel: true });
      return;
    }
    let preco = parseFloat(c.getAttribute('data-tss-price') || '');
    if (!(preco > 0)) preco = dinheiro(txt(c.querySelector('.product-item__new-price')));
    if (!(preco > 0)) return;
    const de = dinheiro(txt(c.querySelector('.product-item__old-price')));
    const nota = parseFloat(txt(c.querySelector('.tss-rating-value')).replace(',', '.'));
    const qtdAval = parseInt(txt(c.querySelector('.tss-rating-count')).replace(/\D/g, ''), 10);
    const titulo = link.getAttribute('title') || txt(link);
    out.push({
      titulo: titulo,
      preco: preco,
      preco_de: de && de > preco + 0.005 ? de : null,
      nota: nota >= 0 && nota <= 5 && !(qtdAval === 0) ? nota : null,
      vendidos: null,
      full: false,
      loja_oficial: true,
      frete_gratis: c.getAttribute('data-tss-frete') === '1',
      recondicionado: /recondicionad|seminov|open ?box/i.test(titulo),
      internacional: false,
      pais: '',
      vendedor: 'Terabyte',
      url: new URL(link.getAttribute('href'), location.origin).href,
      patrocinado: false,
      fonte: 'Terabyte',
      vendedor_proprio: true
    });
  });
  return out;
}
