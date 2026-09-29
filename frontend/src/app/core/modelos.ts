/**
 * Tipo de requisição (espelha ModoAquisicao.java). O valor vai para o banco e não muda; o nome que a tela
 * mostra fica em ROTULO_MODO — "Requisição / Chamado" é provisório, trocar só ali.
 */
export type ModoAquisicao = 'REQUISICAO' | 'OPEX' | 'CAPEX';

/** Ordem dos botões e das abas. Requisição/Chamado primeiro: é o fluxo normal e o padrão da tela. */
export const MODOS: ModoAquisicao[] = ['REQUISICAO', 'OPEX', 'CAPEX'];

export const ROTULO_MODO: Record<ModoAquisicao, string> = {
  REQUISICAO: 'Requisição / Chamado',
  OPEX: 'OPEX',
  CAPEX: 'CAPEX',
};

export const DICA_MODO: Record<ModoAquisicao, string> = {
  REQUISICAO: 'Requisição normal com chamado — observação "# 1021069. …"',
  OPEX: 'OPEX — despesa operacional com chamado — observação "OPEX. # 1021069. …"',
  CAPEX: 'CAPEX — investimento sem chamado (aquisição livre) — observação "CAPEX. …"',
};

/** OPEX e Requisição exigem o chamado; no CAPEX ele é opcional e não vai para a IA. */
export function exigeChamado(m: ModoAquisicao): boolean {
  return m !== 'CAPEX';
}
export type TemplateCodigo = 'TD' | 'DAM' | 'RDAM' | 'CPL';

export interface Loja {
  numero: number;
  nome: string;
  cnpj: string;
  empresa: string;
  template: TemplateCodigo;
  templateRotulo: string;
  ativa: boolean;
  /** Cadastro (não sai no impresso); null quando não informado. */
  razaoSocial: string | null;
  inscricaoEstadual: string | null;
  cidade: string | null;
  uf: string | null;
}

/** Cadastro/edição de loja (tela Lojas). O número não muda na edição. */
export interface LojaForm {
  numero: number;
  nome: string;
  cnpj: string;
  empresa: string;
  template: TemplateCodigo;
  razaoSocial: string;
  inscricaoEstadual: string;
  cidade: string;
  uf: string;
}

export interface Parametros {
  maxItens: number;
  requerentePadrao: string;
  gestorPadrao: string;
  /** "" na produção; "homologacao" no ambiente de testes (a tela mostra uma faixa) */
  ambiente: string;
}

/** JSON devolvido pela IA — valores como texto no formato brasileiro. */
export interface DadosExtraidos {
  chamado_num: string | null;
  loja_num: string | null;
  loja_nome: string | null;
  titulo: string | null;
  itens: ItemExtraido[];
  total: string | null;
  observacao: string | null;
  validade_ate: string | null;
  validade_dias: string | null;
}

export interface ItemExtraido {
  produto: string | null;
  descricao: string | null;
  qtd: string | null;
  valor_unit: string | null;
  valor_total: string | null;
  /** Onde a IA leu o valor — só vai para o log do servidor, a tela não mostra. */
  fonte?: string | null;
}

export interface RespostaExtracao {
  dados: DadosExtraidos;
  loja: Loja | null;
  avisos: string[];
  modelo: string;
  tentativas: number;
  /** Tempo da leitura no servidor. */
  duracaoMs: number;
  /** Os mesmos arquivos já tinham sido lidos há pouco: resposta reaproveitada, sem chamar a IA. */
  doCache: boolean;
  /** "Válido até" calculado pelo servidor (yyyy-MM-dd) a partir do documento; null se ele não informa. */
  validadeSugerida: string | null;
  /** Orçamentos já gerados para os mesmos chamados — aviso de duplicidade. */
  chamadosJaOrcados: ItemHistorico[];
}

export interface GerarOrcamentoRequest {
  modo: ModoAquisicao;
  lojaNumero: string;
  titulo: string;
  dataEmissao: string; // yyyy-MM-dd
  validade: string | null; // yyyy-MM-dd
  chamadoNum: string | null;
  /** prints: só no orçamento por cotação — ids dos prints das opções do item, a escolhida primeiro. */
  itens: { produto: string; descricao: string; quantidade: number; valorUnitario: number; prints?: string[] }[];
  subtotal: number | null;
  frete: number | null;
  acrescimos: number | null;
  observacoes: string;
  requerente: string;
  gestor: string;
}

export interface ItemHistorico {
  id: number;
  criadoEm: string;
  modo: ModoAquisicao;
  titulo: string;
  lojaNumero: number;
  lojaNome: string;
  empresa: string;
  chamadoNum: string | null;
  total: number;
  nomeArquivo: string;
  criadoPor: string;
  /** COTACAO = montado pela cotação em lojas online (com os prints); DOCUMENTOS = fluxo de sempre. */
  origem: OrigemOrcamento;
}

export type OrigemOrcamento = 'DOCUMENTOS' | 'COTACAO';

export interface Pagina<T> {
  itens: T[];
  pagina: number;
  tamanho: number;
  total: number;
}

export interface ResultadoImportacao {
  importados: number;
  ignorados: number;
  problemas: string[];
}

/** Documento anexado no composer (chamado ou orçamento). */
export interface Anexo {
  id: string;
  arquivo: Blob;
  nome: string;
  /** Endereço local (blob:) do arquivo, para pré-visualizar — imagem ou PDF. */
  previewUrl: string;
  tipo: 'imagem' | 'PDF';
}

/** Corpo de erro RFC 9457 que o backend devolve. */
export interface Problema {
  detail?: string;
  problemas?: string[];
  tentativas?: number;
}
