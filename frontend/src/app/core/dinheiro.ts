/** "R$ 1.234,56" → 1234.56. Vazio ou ilegível vira 0, como no HTML v3.5. */
export function parseBRL(texto: string | number | null | undefined): number {
  if (texto === null || texto === undefined || texto === '') return 0;
  if (typeof texto === 'number') return texto;
  const n = parseFloat(texto.replace(/[R$\s ]/g, '').replace(/\./g, '').replace(',', '.'));
  return Number.isFinite(n) ? n : 0;
}

const FORMATO = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** 1234.56 → "R$ 1.234,56". */
export function fmtBRL(valor: number): string {
  return 'R$ ' + FORMATO.format(valor);
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
