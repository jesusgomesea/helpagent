import { Routes } from '@angular/router';

/** Rotas. As páginas são carregadas sob demanda (lazy) para a primeira tela abrir rápido. */
export const routes: Routes = [
  {
    path: '',
    title: 'HELP-AGENT — Novo orçamento',
    loadComponent: () => import('./features/novo-orcamento/novo-orcamento.page').then((m) => m.NovoOrcamentoPage),
  },
  {
    path: 'historico',
    title: 'HELP-AGENT — Histórico',
    loadComponent: () => import('./features/historico/historico.page').then((m) => m.HistoricoPage),
  },
  {
    path: 'lojas',
    title: 'HELP-AGENT — Lojas',
    loadComponent: () => import('./features/lojas/lojas.page').then((m) => m.LojasPage),
  },
  {
    path: 'cotacao',
    title: 'HELP-AGENT — Cotação',
    loadComponent: () => import('./features/cotacao/cotacao.page').then((m) => m.CotacaoPage),
  },
  {
    path: 'orcamento-cotacao',
    title: 'HELP-AGENT — Orçamento por cotação',
    loadComponent: () =>
      import('./features/orcamento-cotacao/orcamento-cotacao.page').then((m) => m.OrcamentoCotacaoPage),
  },
  { path: '**', redirectTo: '' },
];
