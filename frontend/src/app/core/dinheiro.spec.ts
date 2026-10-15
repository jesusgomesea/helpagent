import { dataLocalISO, fmtBRL, fmtMoeda, moedaNoTexto, numeroOuNulo, parseBRL } from './dinheiro';

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

  it('lê e formata dólar e euro, sem converter', () => {
    expect(parseBRL('US$ 1.200,00')).toBe(1200);
    expect(parseBRL('€ 99,90')).toBe(99.9);
    expect(fmtMoeda(1200, 'USD')).toBe('US$ 1.200,00');
    expect(fmtMoeda(99.9, 'EUR')).toBe('€ 99,90');
  });

  it('reconhece a moeda escrita no documento', () => {
    expect(moedaNoTexto('pág. 1, Total: US$ 1.200,00')).toBe('USD');
    expect(moedaNoTexto('Total EUR 300')).toBe('EUR');
    expect(moedaNoTexto('R$ 1.500,00')).toBeNull();
  });

  it('distingue campo em branco de zero', () => {
    expect(numeroOuNulo('  ')).toBeNull();
    expect(numeroOuNulo('0,00')).toBe(0);
  });

  it('usa a data local, não UTC', () => {
    expect(dataLocalISO(new Date(2026, 8, 25, 23, 30))).toBe('2026-09-25');
  });
});
