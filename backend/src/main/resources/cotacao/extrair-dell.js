// Dell — cada cartão de produto traz um JSON no atributo data-product-detail (mapeado em 28/09/2026):
//   [{Key, Value: {productId, title, dellPrice "R$ 5.198,67", marketPrice, pdUrl}}]
// O título da Dell é genérico ("Notebook Dell 15"): juntamos a configuração do cartão (processador,
// memória, disco...) para o filtro "Título deve conter" funcionar. A Dell vende direto: vendedor próprio.
() => {
  const txt = e => (e && e.textContent ? e.textContent.replace(/\s+/g, ' ').trim() : '');
  const dinheiro = s => {
    const m = String(s || '').replace(/ /g, ' ').match(/R\$\s*([\d.]+,\d{2})/);
    return m ? parseFloat(m[1].replace(/\./g, '').replace(',', '.')) : null;
  };
  const out = [];
  document.querySelectorAll('[data-product-detail]').forEach(c => {
    let det;
    try { det = JSON.parse(c.getAttribute('data-product-detail')); } catch (e) { return; }
    const v = det && det[0] && det[0].Value;
    if (!v || !v.title) return;
    if (c.getAttribute('data-is-sold-out') === 'True') { out.push({ titulo: v.title, indisponivel: true }); return; }
    const preco = dinheiro(v.dellPrice);
    if (!preco) return;
    const de = dinheiro(v.marketPrice);
    // configuração: linhas curtas das listas de especificação do cartão, sem repetir. Os rótulos ("Processor",
    // "Graphics"...) vêm como itens próprios e o nome do produto se repete: ficam de fora
    const ROTULO = /^(processor|processador|graphics|placa de v[íi]deo|gr[áa]ficos|memory|mem[óo]ria|storage|armazenamento|display|tela|operating system|sistema operacional|os|windows)$/i;
    const specs = [];
    c.querySelectorAll('[class*="spec"] li, [class*="specs"] span, [class*="spec-"]').forEach(e => {
      if (e.children.length > 2) return;
      const t = txt(e);
      if (!t || t.length <= 2 || t.length >= 90 || ROTULO.test(t) || t === v.title) return;
      if (/R\$|compar/i.test(t) || specs.some(s => s.indexOf(t) >= 0 || t.indexOf(s) >= 0)) return;
      specs.push(t);
    });
    const titulo = v.title + (specs.length ? ' — ' + specs.slice(0, 6).join(' · ') : '');
    out.push({
      titulo: titulo,
      preco: preco,
      preco_de: de && de > preco + 0.005 ? de : null,
      nota: null,
      vendidos: null,
      full: false,
      loja_oficial: true,
      frete_gratis: /frete gr[áa]tis/i.test(txt(c)),
      recondicionado: /recondicionad|outlet|renovad/i.test(titulo),
      internacional: false,
      pais: '',
      vendedor: 'Dell',
      url: v.pdUrl ? new URL(v.pdUrl, location.origin).href : location.href,
      patrocinado: false,
      fonte: 'Dell',
      vendedor_proprio: true
    });
  });
  return out;
}
