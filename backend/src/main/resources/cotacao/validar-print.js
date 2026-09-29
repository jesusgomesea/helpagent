// Confere, logo antes do print, se o preço coletado está VISÍVEL na janela (ColetorCotacao.fotografar).
// Recebe o preço à vista coletado (número) e devolve { avisos, precoVisivel, rolou }:
//  1) aviso de cookies/LGPD que continua na tela depois do preparar-print.js (fixo/sticky, com texto de cookies ou
//     privacidade) é ESCONDIDO — não aceita nada, só tira da frente do print. "avisos" = quantos foram escondidos;
//  2) procura o menor elemento cujo texto contém o preço no formato da loja ("1.657,25"; o site pode quebrar o
//     preço em vários <span>, por isso compara o texto sem espaços). Se ele está fora da janela, rola até ele;
//  3) "visível" = dentro da janela e, no centro dele, o que está desenhado é ele mesmo (elementFromPoint) —
//     nada por cima (aviso, chat, modal). É o que a validação manual precisa ver no print.
(preco) => {
  const semEspaco = (t) => (t || '').replace(/[\s ]+/g, '');
  const naJanela = (r) => r.width > 0 && r.height > 0 && r.top >= 0 && r.left >= 0 && r.bottom <= innerHeight && r.right <= innerWidth;

  // 1) avisos de cookies que ficaram abertos
  let avisos = 0;
  for (const el of document.querySelectorAll('body *')) {
    const st = getComputedStyle(el);
    if (st.position !== 'fixed' && st.position !== 'sticky') continue;
    if (st.display === 'none' || st.visibility === 'hidden' || Number(st.opacity) === 0) continue;
    const txt = el.textContent || '';
    if (txt.length > 2500 || !/cookie|privacidade|lgpd/i.test(txt)) continue;
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) continue;
    el.style.setProperty('display', 'none', 'important');
    avisos++;
  }

  // 2) onde está o preço
  if (typeof preco !== 'number' || !(preco > 0)) return { avisos, precoVisivel: false, rolou: false };
  const alvo = preco.toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  let melhor = null;
  for (const el of document.querySelectorAll('body *')) {
    const txt = semEspaco(el.textContent);
    if (txt.length > 60 || !txt.includes(alvo)) continue;
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) continue;
    if (!melhor || txt.length < semEspaco(melhor.textContent).length) melhor = el;
  }
  if (!melhor) return { avisos, precoVisivel: false, rolou: false };

  let rolou = false;
  if (!naJanela(melhor.getBoundingClientRect())) {
    melhor.scrollIntoView({ block: 'center', inline: 'nearest' });
    rolou = true;
  }

  // 3) nada por cima do preço
  const r = melhor.getBoundingClientRect();
  if (!naJanela(r)) return { avisos, precoVisivel: false, rolou };
  const topo = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
  const precoVisivel = !!topo && (melhor === topo || melhor.contains(topo) || topo.contains(melhor));
  return { avisos, precoVisivel, rolou };
}
