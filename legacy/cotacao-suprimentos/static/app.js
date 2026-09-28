/* Cotação R Damásio — front-end */
'use strict';

const $ = (s) => document.querySelector(s);
const el = (t, cls, txt) => {
  const n = document.createElement(t);
  if (cls) n.className = cls;
  if (txt !== undefined) n.textContent = txt;
  return n;
};

// cores dos quatro critérios, na ordem em que aparecem na barra de score
const CORES = {
  preco:      'var(--rd-blue-700)',
  entrega:    'var(--rd-blue-400)',
  fornecedor: 'var(--rd-sand)',
  marca:      'var(--rd-red-600)'
};
const NOMES = { preco: 'Preço', entrega: 'Entrega', fornecedor: 'Fornecedor', marca: 'Marca' };
const PESOS = ['peso_preco', 'peso_entrega', 'peso_fornecedor', 'peso_marca'];
const PADRAO = { peso_preco: 40, peso_entrega: 20, peso_fornecedor: 25, peso_marca: 15 };

const brl = (v) => (v == null ? '—' :
  v.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' }));
// null = o anuncio nao informou; nao e zero, entao nao mostramos zero
const inteiro = (v) => (v == null ? '—' : v.toLocaleString('pt-BR'));

/* ------------------------------------------------------------ critérios */
function pesosAtuais() {
  const p = {};
  PESOS.forEach((k) => { p[k] = Number($('#' + k).value); });
  return p;
}

function pintaPesos() {
  const p = pesosAtuais();
  const soma = PESOS.reduce((a, k) => a + p[k], 0) || 1;
  const barra = $('#barra-pesos');
  barra.innerHTML = '';
  const partes = [];
  PESOS.forEach((k) => {
    const pct = Math.round((p[k] / soma) * 100);
    $('#out_' + k).textContent = pct + '%';
    const chave = k.replace('peso_', '');
    const i = el('i');
    i.style.width = pct + '%';
    i.style.background = CORES[chave];
    i.title = NOMES[chave] + ' ' + pct + '%';
    barra.appendChild(i);
    partes.push(NOMES[chave].toLowerCase() + ' ' + pct);
  });
  $('#criterios-resumo').textContent = partes.join(' · ');
}

function coletaCriterios() {
  const c = pesosAtuais();
  c.nota_minima = Number($('#nota_minima').value);
  c.vendidos_minimo = Number($('#vendidos_minimo').value);
  c.preco_max = $('#preco_max').value ? Number($('#preco_max').value) : null;
  c.deve_conter = $('#deve_conter').value;
  c.nao_pode_conter = $('#nao_pode_conter').value;
  c.origem = $('#origem').value;
  c.exigir_full = $('#exigir_full').checked;
  c.aceita_recondicionado = $('#aceita_recondicionado').checked;
  c.marcas_preferidas = $('#marcas_preferidas').value;
  c.segmentos = $('#segmentos').value;
  return c;
}

function restauraPadrao() {
  PESOS.forEach((k) => { $('#' + k).value = PADRAO[k]; });
  $('#nota_minima').value = '4.5';
  $('#vendidos_minimo').value = '100';
  $('#origem').value = 'qualquer';
  ['preco_max', 'deve_conter', 'nao_pode_conter', 'marcas_preferidas', 'segmentos']
    .forEach((id) => { $('#' + id).value = ''; });
  $('#exigir_full').checked = false;
  $('#aceita_recondicionado').checked = false;
  $('#paginas').value = '1';
  pintaPesos();
}

/* ----------------------------------------------------------- renderizar */
function badges(a) {
  const box = el('div', 'card__badges');
  if (a.full) box.appendChild(el('span', 'rd-badge rd-badge--solid', 'Full'));
  else if (a.frete_gratis) box.appendChild(el('span', 'rd-badge', 'Frete grátis'));
  if (a.internacional) {
    box.appendChild(el('span', 'rd-badge rd-badge--accent',
      'Internacional' + (a.pais ? ' · ' + a.pais : '')));
  }
  if (a.loja_oficial) box.appendChild(el('span', 'rd-badge', 'Loja oficial'));
  if (a.nota) box.appendChild(el('span', 'rd-badge', a.nota.toFixed(1).replace('.', ',') + ' ★'));
  if (a.vendidos) box.appendChild(el('span', 'rd-badge', '+' + inteiro(a.vendidos) + ' vendidos'));
  if (a.preco_de && a.preco_de > a.preco) {
    const off = Math.round((1 - a.preco / a.preco_de) * 100);
    if (off > 0) box.appendChild(el('span', 'rd-badge rd-badge--off', off + '% off'));
  }
  return box;
}

function barraScore(a, pesos) {
  const box = el('div', 'score');
  const top = el('div', 'score__top');
  top.appendChild(el('span', 'score__label', 'Score'));
  top.appendChild(el('span', 'score__valor', a.score.toFixed(1).replace('.', ',')));
  box.appendChild(top);

  const barra = el('div', 'score__barra');
  const leg = el('div', 'score__leg');
  Object.keys(CORES).forEach((k) => {
    // contribuição de cada critério no score final, em pontos absolutos
    const contrib = (a.parciais[k] / 100) * (pesos['peso_' + k] / 100) * 100;
    const i = el('i');
    i.style.width = contrib + '%';
    i.style.background = CORES[k];
    i.title = NOMES[k] + ': ' + contrib.toFixed(1) + ' pts de ' + pesos['peso_' + k];
    barra.appendChild(i);

    const s = el('span');
    const b = el('b');
    b.style.background = CORES[k];
    s.appendChild(b);
    s.appendChild(document.createTextNode(NOMES[k] + ' ' + Math.round(contrib)));
    leg.appendChild(s);
  });
  box.appendChild(barra);
  box.appendChild(leg);
  return box;
}

function cartao(a, pos, pesos) {
  const c = el('article', 'card' + (pos === 1 ? ' card--top' : ''));
  c.appendChild(el('span', 'card__pos', pos === 1 ? '① melhor escolha' : (pos === 2 ? '② vice' : '③ terceiro')));
  c.appendChild(el('h4', 'card__titulo', a.titulo));

  const pb = el('div', 'card__preco');
  pb.appendChild(el('span', 'preco', brl(a.preco)));
  if (a.preco_de && a.preco_de > a.preco) pb.appendChild(el('span', 'preco__de', brl(a.preco_de)));
  c.appendChild(pb);

  c.appendChild(badges(a));
  c.appendChild(barraScore(a, pesos));

  const rod = el('div', 'card__rodape');
  rod.appendChild(el('span', 'card__vend', a.vendedor || (a.loja_oficial ? 'loja oficial' : 'vendedor não identificado')));
  if (a.url) {
    const link = el('a', 'card__link', a.patrocinado ? 'Ver anúncio (ad)' : 'Ver anúncio');
    link.href = a.url;
    link.target = '_blank';
    link.rel = 'noopener';
    if (a.patrocinado) link.title = 'anúncio patrocinado no Mercado Livre';
    rod.appendChild(link);
  } else {
    rod.appendChild(el('span', 'card__link card__link--off', 'sem link'));
  }
  c.appendChild(rod);
  return c;
}

// Patrocinado também abre: o coletor troca o link de rastreamento pelo destino
// real, e quando não consegue mantém o rastreamento, que leva ao mesmo anúncio.
function linkCel(a) {
  const td = el('td');
  if (a.url) {
    const l = el('a', '', 'abrir');
    l.href = a.url;
    l.target = '_blank';
    l.rel = 'noopener';
    if (a.patrocinado) l.title = 'anúncio patrocinado';
    td.appendChild(l);
    if (a.patrocinado) td.appendChild(el('span', 'tag-ad', 'ad'));
  } else {
    td.appendChild(el('span', 'sem-link', 'sem link'));
  }
  return td;
}

function tabelaDemais(itens) {
  const box = el('div', 'tabela-box');
  const t = el('table');
  t.innerHTML = '<thead><tr>' +
    '<th>#</th><th>Produto</th><th class="num">Preço</th><th class="num">Score</th>' +
    '<th class="num">Nota</th><th class="num">Vendidos</th><th>Entrega</th>' +
    '<th>Origem</th><th>Anúncio</th></tr></thead>';
  const tb = el('tbody');
  itens.forEach((a, i) => {
    const tr = el('tr');
    const entrega = a.full ? 'FULL' : (a.frete_gratis ? 'Frete grátis' : 'Envio comum');
    tr.appendChild(el('td', 'num mono', String(i + 4)));
    tr.appendChild(el('td', 'prod', a.titulo));
    tr.appendChild(el('td', 'num mono', brl(a.preco)));
    tr.appendChild(el('td', 'num mono', a.score.toFixed(1).replace('.', ',')));
    tr.appendChild(el('td', 'num mono', a.nota ? a.nota.toFixed(1).replace('.', ',') : '—'));
    tr.appendChild(el('td', 'num mono', inteiro(a.vendidos)));
    tr.appendChild(el('td', '', entrega));
    tr.appendChild(el('td', a.internacional ? 'origem-int' : '',
      a.internacional ? (a.pais || 'Internacional') : 'Nacional'));
    tr.appendChild(linkCel(a));
    tb.appendChild(tr);
  });
  t.appendChild(tb);
  box.appendChild(t);
  return box;
}

function render(r) {
  const pesos = r.resumo.pesos_efetivos;

  $('#res-termo').textContent = r.termo;
  $('#res-meta').textContent =
    'Coletado em ' + r.coletado_em + ' · Mercado Livre · ' + r.segundos + 's';

  const kpis = [
    [inteiro(r.resumo.analisados), 'anúncios analisados'],
    [inteiro(r.resumo.elegiveis), 'passaram nos eliminatórios'],
    ['<em>' + inteiro(r.resumo.descartados) + '</em>', 'descartados'],
    [brl(r.resumo.menor_preco), 'menor preço elegível'],
    [brl(r.resumo.preco_medio), 'preço médio'],
    [brl(r.resumo.mediana), 'mediana']
  ];
  const box = $('#kpis');
  box.innerHTML = '';
  kpis.forEach(([v, l]) => {
    const k = el('div', 'kpi');
    const val = el('div', 'kpi__valor');
    val.innerHTML = v;
    k.appendChild(val);
    k.appendChild(el('div', 'kpi__label', l));
    box.appendChild(k);
  });

  const avisos = $('#avisos');
  avisos.innerHTML = '';
  const semSeg = r.grupos.filter((g) => g.sem_segmento);
  if (semSeg.length && semSeg[0].qtd) {
    const a = el('div', 'aviso aviso--nota');
    a.appendChild(el('strong', '', semSeg[0].qtd + ' anúncios ficaram fora dos segmentos'));
    a.appendChild(el('span', '', 'O título deles não contém nenhum dos segmentos que você definiu. ' +
      'Eles têm ranking próprio abaixo — confira o item antes de comparar.'));
    avisos.appendChild(a);
  }
  if (r.resumo.internacionais && r.criterios.origem === 'qualquer') {
    const a = el('div', 'aviso aviso--nota');
    a.appendChild(el('strong', '', r.resumo.internacionais + ' dos ' + r.resumo.elegiveis +
      ' elegíveis vêm do exterior'));
    a.appendChild(el('span', '', 'Anúncio internacional costuma ser mais barato, mas o prazo é ' +
      'de semanas e pode haver tributação na entrada. Se o prazo importa, use "Origem do envio: ' +
      'só nacional" nos critérios.'));
    avisos.appendChild(a);
  }
  if (r.resumo.sem_volume) {
    const a = el('div', 'aviso aviso--nota');
    a.appendChild(el('strong', '', 'Quantidade vendida indisponível em ' +
      r.resumo.sem_volume + ' de ' + r.resumo.elegiveis + ' anúncios'));
    a.appendChild(el('span', '', 'O Mercado Livre serve variantes de layout e nesta coleta ' +
      'não veio o número de unidades vendidas. Para esses anúncios a reputação foi calculada ' +
      'só pela nota do vendedor, e o filtro de volume mínimo não foi aplicado — dado ausente ' +
      'não é o mesmo que venda baixa.'));
    avisos.appendChild(a);
  }
  (r.avisos || []).forEach((m) => {
    const a = el('div', 'aviso aviso--nota');
    a.appendChild(el('strong', '', 'Aviso da coleta'));
    a.appendChild(el('span', '', m));
    avisos.appendChild(a);
  });

  const gbox = $('#grupos');
  gbox.innerHTML = '';
  r.grupos.forEach((g) => {
    const sec = el('section', 'grupo');
    const cab = el('div', 'grupo__cab');
    cab.appendChild(el('h3', 'grupo__nome', g.rotulo));
    cab.appendChild(el('span', 'grupo__meta',
      g.qtd + ' elegíveis · menor ' + brl(g.menor_preco) + ' · médio ' + brl(g.preco_medio)));
    sec.appendChild(cab);

    const podio = el('div', 'podio');
    g.top3.forEach((a, i) => podio.appendChild(cartao(a, i + 1, pesos)));
    sec.appendChild(podio);

    if (g.demais.length) sec.appendChild(tabelaDemais(g.demais));
    gbox.appendChild(sec);
  });

  // descartados
  $('#desc-titulo').textContent = r.descartados.length + ' anúncios descartados e por quê';
  const dc = $('#desc-corpo');
  dc.innerHTML = '';
  if (r.descartados.length) {
    const t = el('table');
    t.innerHTML = '<thead><tr><th>Produto</th><th class="num">Preço</th>' +
      '<th>Origem</th><th>Motivo do descarte</th><th>Anúncio</th></tr></thead>';
    const tb = el('tbody');
    r.descartados.slice().sort((a, b) => a.motivo.localeCompare(b.motivo)).forEach((a) => {
      const tr = el('tr');
      tr.appendChild(el('td', 'prod', a.titulo));
      tr.appendChild(el('td', 'num mono', brl(a.preco)));
      tr.appendChild(el('td', a.internacional ? 'origem-int' : '',
        a.internacional ? (a.pais || 'Internacional') : 'Nacional'));
      tr.appendChild(el('td', 'motivo', a.motivo));
      tr.appendChild(linkCel(a));
      tb.appendChild(tr);
    });
    t.appendChild(tb);
    dc.appendChild(t);
  }
  $('#box-descartados').hidden = !r.descartados.length;
}

/* ------------------------------------------------------------- fluxo */
const ETAPAS = [
  'Abrindo o navegador…',
  'Buscando no Mercado Livre…',
  'Lendo os anúncios…',
  'Aplicando os eliminatórios…',
  'Pontuando e ordenando…'
];
let timerEtapa = null;

function mostra(qual) {
  $('#estado').hidden = qual !== 'carregando';
  $('#erro').hidden = qual !== 'erro';
  $('#resultado').hidden = qual !== 'resultado';
}

function iniciaEtapas() {
  let i = 0;
  $('#carregando-txt').textContent = ETAPAS[0];
  timerEtapa = setInterval(() => {
    i = Math.min(i + 1, ETAPAS.length - 1);
    $('#carregando-txt').textContent = ETAPAS[i];
  }, 2200);
}

let termoAtual = '';

async function cotar(ev) {
  if (ev) ev.preventDefault();
  const termo = $('#termo').value.trim();
  if (!termo) return;

  const btn = $('#btn-cotar');
  btn.disabled = true;
  btn.querySelector('.btn-txt').textContent = 'Cotando…';
  mostra('carregando');
  iniciaEtapas();
  $('#estado').scrollIntoView({ behavior: 'smooth', block: 'center' });

  try {
    const resp = await fetch('/api/cotar', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        termo: termo,
        paginas: Number($('#paginas').value),
        criterios: coletaCriterios()
      })
    });
    const r = await resp.json();
    if (!resp.ok) throw new Error(r.detalhe ? r.erro + ' — ' + r.detalhe : r.erro);

    if (!r.elegiveis.length) {
      $('#erro-titulo').textContent = 'Nenhum anúncio passou nos eliminatórios.';
      $('#erro-detalhe').textContent =
        r.descartados.length + ' anúncios foram coletados mas todos foram cortados. ' +
        'Afrouxe a nota mínima, o volume de vendas ou o filtro de título.';
      mostra('erro');
      return;
    }
    termoAtual = termo;
    render(r);
    mostra('resultado');
    $('#resultado').scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (e) {
    $('#erro-titulo').textContent = 'Não foi possível cotar.';
    $('#erro-detalhe').textContent = e.message || String(e);
    mostra('erro');
  } finally {
    clearInterval(timerEtapa);
    btn.disabled = false;
    btn.querySelector('.btn-txt').textContent = 'Cotar agora';
  }
}

async function baixarPlanilha() {
  const btn = $('#btn-xlsx');
  const antes = btn.textContent;
  btn.disabled = true;
  btn.textContent = 'Gerando…';
  try {
    const resp = await fetch('/api/planilha', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ termo: termoAtual })
    });
    if (!resp.ok) throw new Error((await resp.json()).erro);
    const blob = await resp.blob();
    const nome = (resp.headers.get('Content-Disposition') || '').match(/filename="([^"]+)"/);
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = nome ? nome[1] : 'cotacao.xlsx';
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(a.href);
  } catch (e) {
    alert('Não foi possível gerar a planilha: ' + e.message);
  } finally {
    btn.disabled = false;
    btn.textContent = antes;
  }
}

/* --------------------------------------------------------------- init */
PESOS.forEach((k) => $('#' + k).addEventListener('input', pintaPesos));
$('#btn-padrao').addEventListener('click', restauraPadrao);
$('#form-busca').addEventListener('submit', cotar);
$('#btn-xlsx').addEventListener('click', baixarPlanilha);
document.querySelectorAll('.chip').forEach((c) => {
  c.addEventListener('click', () => {
    $('#termo').value = c.dataset.termo;
    if (c.dataset.conter !== undefined) $('#deve_conter').value = c.dataset.conter;
    $('#segmentos').value = c.dataset.seg || '';
    cotar();
  });
});
pintaPesos();
$('#termo').focus();
