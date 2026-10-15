/**
 * Moeda do impresso (15/10/2026). Orçamento de fornecedor em dólar ou euro sai na moeda original, sem conversão;
 * o padrão é o real. Moeda nova: incluir aqui e no {@code common/Moeda.java} do backend.
 */
export type Moeda = 'BRL' | 'USD' | 'EUR';
export const MOEDAS: Moeda[] = ['BRL', 'USD', 'EUR'];
export const SIMBOLO_MOEDA: Record<Moeda, string> = { BRL: 'R$', USD: 'US$', EUR: '€' };
export const NOME_MOEDA: Record<Moeda, string> = { BRL: 'real', USD: 'dólar', EUR: 'euro' };

/**
 * "R$ 1.234,56", "US$ 1.234,56", "€ 1.234,56" → 1234.56. Vazio ou ilegível vira 0, como no HTML v3.5.
 * O símbolo é descartado: a moeda é a escolhida no orçamento.
 */
export function parseBRL(texto: string | number | null | undefined): number {
  if (texto === null || texto === undefined || texto === '') return 0;
  if (typeof texto === 'number') return texto;
  const n = parseFloat(texto.replace(/[^0-9,.\-]/g, '').replace(/\./g, '').replace(',', '.'));
  return Number.isFinite(n) ? n : 0;
}

const FORMATO = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** 1234.56 → "R$ 1.234,56". */
export function fmtBRL(valor: number): string {
  return fmtMoeda(valor, 'BRL');
}

/** 1234.56 → "US$ 1.234,56": símbolo da moeda, número no formato brasileiro (como o resto do impresso). */
export function fmtMoeda(valor: number, moeda: Moeda | null | undefined): string {
  return SIMBOLO_MOEDA[moeda ?? 'BRL'] + ' ' + FORMATO.format(valor);
}

/**
 * Moeda escrita num texto lido do documento ("US$ 1.200", "USD", "€ 300", "EUR"); null = nada além do real.
 * Serve para sugerir a moeda na revisão: a IA copia o valor com o símbolo de origem no "fonte".
 */
export function moedaNoTexto(texto: string | null | undefined): Moeda | null {
  if (!texto) return null;
  if (/US\$|USD|U\$S|d[óo]lar/i.test(texto)) return 'USD';
  if (/€|EUR\b|euro/i.test(texto)) return 'EUR';
  return null;
}

/** Para o backend: número ou null quando o campo ficou em branco. */
export function numeroOuNulo(texto: string | null | undefined): number | null {
  return texto && texto.trim() ? parseBRL(texto) : null;
}

/** Data local em yyyy-MM-dd. toISOString() é UTC: no Brasil, depois das 21h, já seria amanhã. */
export function dataLocalISO(d = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
