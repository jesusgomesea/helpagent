// Prepara uma página de produto para o print do orçamento por cotação (ColetorCotacao.capturar).
// Vale para todas as lojas, por isso não depende de seletor de nenhuma:
//  1) fecha o aviso de cookies/LGPD, que costuma cobrir parte do preço (a Kabum mostra um "Entendi" no canto);
//     só clica em botão de texto conhecido DENTRO de um bloco que fala de cookies/privacidade — um "OK" ou
//     "Fechar" solto na página pode ser do próprio produto;
//  2) volta ao topo, onde ficam título, preço e vendedor.
() => {
  const rotulos = /^(entendi|ok|aceitar|aceito|aceitar todos|aceitar e fechar|aceitar cookies|concordo|continuar|fechar|prosseguir|permitir todos|allow all|accept|accept all)$/i;
  let fechados = 0;
  for (const b of document.querySelectorAll('button, a[role="button"], [role="button"]')) {
    const t = (b.textContent || '').trim();
    if (!rotulos.test(t)) continue;
    let el = b;
    for (let i = 0; i < 6 && el; i++, el = el.parentElement) {
      const txt = el.textContent || '';
      // bloco pequeno (o aviso), não a página inteira — que também menciona "privacidade" no rodapé
      if (txt.length < 2500 && /cookie|privacidade|lgpd/i.test(txt)) {
        try { b.click(); fechados++; } catch (e) { /* segue: o print sai com o aviso */ }
        break;
      }
    }
  }
  window.scrollTo(0, 0);
  return fechados;
}
