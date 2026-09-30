import { Routes } from '@angular/router';
import { recursoLigado } from './core/recursos';

/**
 * Rotas. As páginas são carregadas sob demanda (lazy) para a primeira tela abrir rápido. Cotação e o painel de uso
 * da IA só existem se o servidor tiver o recurso ligado (core/recursos.ts).
 *
 * O painel de uso da IA é área técnica, fora do menu do helpdesk (30/09/2026): só pelo endereço /swagger/uso-ia,
 * ao lado da documentação da API (/swagger-ui.html).
 */
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
    // debaixo de /lojas: o link "Lojas" do menu fica ativo nas duas telas de cadastro
    path: 'lojas/fornecedores',
    title: 'HELP-AGENT — Fornecedores',
    loadComponent: () => import('./features/fornecedores/fornecedores.page').then((m) => m.FornecedoresPage),
  },
  {
    path: 'cotacao',
    title: 'HELP-AGENT — Cotação',
    canMatch: [recursoLigado('cotacao')],
    loadComponent: () => import('./features/cotacao/cotacao.page').then((m) => m.CotacaoPage),
  },
  {
    path: 'orcamento-cotacao',
    title: 'HELP-AGENT — Orçamento por cotação',
    canMatch: [recursoLigado('cotacao')],
    loadComponent: () =>
      import('./features/orcamento-cotacao/orcamento-cotacao.page').then((m) => m.OrcamentoCotacaoPage),
  },
  {
    path: 'swagger/uso-ia',
    title: 'HELP-AGENT — Uso da IA',
    canMatch: [recursoLigado('ia')],
    loadComponent: () => import('./features/uso-ia/uso-ia.page').then((m) => m.UsoIaPage),
  },
  {
    // área técnica, como o uso da IA: onde a IA mais erra, pelo que os atendentes corrigem
    path: 'swagger/qualidade-ia',
    title: 'HELP-AGENT — Qualidade da IA',
    canMatch: [recursoLigado('ia')],
    loadComponent: () => import('./features/qualidade-ia/qualidade-ia.page').then((m) => m.QualidadeIaPage),
  },
  // endereço antigo (29/09/2026), para favorito salvo
  { path: 'uso-ia', redirectTo: 'swagger/uso-ia' },
  { path: '**', redirectTo: '' },
];
