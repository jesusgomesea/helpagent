// Kabum — lê o JSON que o Next.js embute na página (script#__NEXT_DATA__), não o HTML dos cartões.
// Caminho: props.pageProps.data.catalogServer.data[] (mapeado em 28/09/2026).
//   priceWithDiscount = preço à vista no PIX (o que vale para o orçamento) · price = parcelado · oldPrice = "de"
//   flags.isMarketplace = vendido por lojista parceiro (não pela KaBuM!) · rating/ratingCount = avaliações
// Se o caminho mudar, a lista volta vazia e a tela avisa "Kabum não devolveu resultados" (MANUTENCAO §7).
() => {
  const el = document.getElementById('__NEXT_DATA__');
  if (!el) return [];
  let dados;
  try { dados = JSON.parse(el.textContent); } catch (e) { return []; }
  const lista = (((((dados || {}).props || {}).pageProps || {}).data || {}).catalogServer || {}).data || [];
  const num = v => (typeof v === 'number' && v > 0 ? v : null);
  return lista.filter(p => p && p.available !== false).map(p => {
    const flags = p.flags || {};
    const preco = num(p.priceWithDiscount) || num(p.price);
    const antes = [num(p.oldPrice), num(p.price)].filter(v => v && preco && v > preco + 0.005);
    const proprio = !flags.isMarketplace;
    return {
      titulo: p.name || '',
      preco: preco,
      preco_de: antes.length ? Math.max.apply(null, antes) : null,
      nota: p.ratingCount > 0 ? Math.round(p.rating * 10) / 10 : null, // vem como 4.800000190734863
      vendidos: null,                      // a Kabum não publica quantidade vendida
      full: false,
      loja_oficial: proprio,
      frete_gratis: !!flags.isFreeShipping,
      recondicionado: !!flags.isOpenbox || /recondicionad|seminov|open ?box/i.test(p.name || ''),
      internacional: false,
      pais: '',
      vendedor: p.sellerName || '',
      url: 'https://www.kabum.com.br/produto/' + p.code + '/' + (p.friendlyName || ''),
      patrocinado: false,
      fonte: 'Kabum',
      vendedor_proprio: proprio
    };
  });
}
