import { dataLocalISO, fmtBRL, numeroOuNulo, parseBRL } from './dinheiro';

describe('dinheiro', () => {
  it('lê valores no formato brasileiro', () => {
    expect(parseBRL('R$ 1.234,56')).toBe(1234.56);
    expect(parseBRL('1500,00')).toBe(1500);
    expect(parseBRL('')).toBe(0);
    expect(parseBRL('abc')).toBe(0);
  });

  it('formata em reais', () => {
    expect(fmtBRL(1234.5)).toBe('R$ 1.234,50');
  });

  it('distingue campo em branco de zero', () => {
    expect(numeroOuNulo('  ')).toBeNull();
    expect(numeroOuNulo('0,00')).toBe(0);
  });

  it('usa a data local, não UTC', () => {
    expect(dataLocalISO(new Date(2026, 8, 25, 23, 30))).toBe('2026-09-25');
  });
});
