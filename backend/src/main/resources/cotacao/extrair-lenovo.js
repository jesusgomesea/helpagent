// Lenovo — cartões .product_item[data-product-code] montados por JavaScript (mapeado em 28/09/2026):
//   data-dlp-url = caminho do produto · o nome completo vem com a configuração ("ThinkPad E14 Ryzen 5 16GB 256GB SSD")
//   .strike-through-price = "de" · .price-summary-info .price-title = preço atual
//   .card-rating-container "4.5 (96)" = nota e quantidade de avaliações · a Lenovo vende direto: vendedor próprio
() => {
  const txt = e => (e && e.textContent ? e.textContent.replace(/\s+/g, ' ').trim() : '');
  const dinheiro = s => {
    const m = String(s || '').replace(/ /g, ' ').match(/R\$\s*([\d.]+,\d{2})/);
    return m ? parseFloat(m[1].replace(/\./g, '').replace(',', '.')) : null;
  };
  const out = [];
  document.querySelectorAll('.product_item[data-product-code]').forEach(c => {
    const caminho = c.getAttribute('data-dlp-url');
    let titulo = txt(c.querySelector('.product_title, [class*="product_title"], .product-title, h3'));
    if (!titulo) {
      const cmp = c.querySelector('.common-compare-container input[aria-label]');
      titulo = cmp ? cmp.getAttribute('aria-label').replace(/^Comparar\s+/i, '').replace(/\s+Product Information Button$/i, '') : '';
    }
    if (!titulo || !caminho) return;
    const riscado = c.querySelector('.strike-through-price');
    const de = dinheiro(txt(riscado));
    // preço atual: .price-summary-info .price-title. A varredura genérica de "R$" pegava frete/cupom
    // ("R$ 15,00") antes do preço — fica só como plano B, e dentro do bloco de preço (.price-stack)
    let preco = dinheiro(txt(c.querySelector('.price-summary-info .price-title')));
    if (!preco) {
      const bloco = c.querySelector('.price-stack') || c;
      const folhas = [...bloco.querySelectorAll('*')].filter(e => e.children.length === 0 && /R\$\s*\d/.test(e.textContent));
      for (const e of folhas) {
        if (e.closest('.strike-through-price, [class*="popup"], [class*="tooltip"], [class*="saving"], [class*="installment"]')) continue;
        preco = dinheiro(txt(e));
        if (preco) break;
      }
    }
    if (!preco) return;
    const aval = txt(c.querySelector('.card-rating-container')).match(/([\d.,]+)\s*\((\d+)\)/);
    out.push({
      titulo: titulo,
      preco: preco,
      preco_de: de && de > preco + 0.005 ? de : null,
      nota: aval && parseInt(aval[2], 10) > 0 ? parseFloat(aval[1].replace(',', '.')) : null,
      vendidos: null,
      full: false,
      loja_oficial: true,
      frete_gratis: /frete gr[áa]tis/i.test(txt(c)),
      recondicionado: /recondicionad|outlet|renovad/i.test(titulo),
      internacional: false,
      pais: '',
      vendedor: 'Lenovo',
      url: new URL('/br/pt' + (caminho.startsWith('/br/pt') ? caminho.slice(6) : caminho), location.origin).href,
      patrocinado: false,
      fonte: 'Lenovo',
      vendedor_proprio: true
    });
  });
  return out;
}
