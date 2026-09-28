// Pichau — lê os dados que o Next.js (App Router) embute em <script>self.__next_f.push([1,"..."])</script>,
// não o HTML dos cartões. Os pedaços juntos formam linhas "id:JSON"; a que tem products.items é a busca.
//   pichau_prices.avista = preço no PIX (o que vale) · final_price = parcelado · base_price = "de"
//   stock_status = IN_STOCK | OUT_OF_STOCK · url_key = caminho do produto · a listagem não traz avaliação
// ATENÇÃO: ler as tags <script>, não o array self.__next_f. Depois que a página hidrata, o Next troca o push
// e os pedaços que chegam depois (o dos produtos, que é grande) não ficam no array — ele vinha sem a busca.
() => {
  const pedacos = [...document.scripts].map(s => {
    const t = s.textContent || '';
    if (t.indexOf('self.__next_f.push(') !== 0) return '';
    try {
      const arg = JSON.parse(t.slice('self.__next_f.push('.length, t.lastIndexOf(')')));
      return arg && typeof arg[1] === 'string' ? arg[1] : '';
    } catch (e) { return ''; }
  }).join('');
  const acha = o => {
    if (!o || typeof o !== 'object') return null;
    if (o.products && typeof o.products === 'object' && Array.isArray(o.products.items)) return o.products;
    for (const k in o) { const r = acha(o[k]); if (r) return r; }
    return null;
  };
  let produtos = null;
  for (const linha of pedacos.split('\n')) {
    if (linha.indexOf('"products"') < 0 || linha.indexOf('"items"') < 0) continue;
    try { produtos = acha(JSON.parse(linha.slice(linha.indexOf(':') + 1))); } catch (e) { produtos = null; }
    if (produtos) break;
  }
  if (!produtos) return [];
  const num = v => (typeof v === 'number' && v > 0 ? v : null);
  return produtos.items.filter(p => p && p.stock_status !== 'OUT_OF_STOCK').map(p => {
    const pr = p.pichau_prices || {};
    const preco = num(pr.avista) || num(pr.final_price) || num(p.special_price);
    const de = num(pr.base_price);
    return {
      titulo: p.name || '',
      preco: preco,
      preco_de: de && preco && de > preco + 0.005 ? de : null,
      nota: null,
      vendidos: null,
      full: false,
      loja_oficial: true,
      frete_gratis: false,
      recondicionado: !!p.is_openbox || /recondicionad|seminov|open ?box/i.test(p.name || ''),
      internacional: false,
      pais: '',
      vendedor: 'Pichau',
      url: 'https://www.pichau.com.br/' + (p.url_key || ''),
      patrocinado: false,
      fonte: 'Pichau',
      vendedor_proprio: true
    };
  });
}
