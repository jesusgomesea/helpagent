import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { Avaliado, CotacaoApi, PrintCotacao, ResultadoCotacao, SituacaoPrint } from './cotacao.api';

/**
 * Cesta do orçamento por cotação: os itens escolhidos na tela Cotação, cada um com as opções fotografadas
 * (a escolhida + 2 alternativas). Vive enquanto o app vive (providedIn root) e fica salva no navegador, porque
 * a cesta costuma ser montada item a item, com buscas no meio — recarregar a página não pode perdê-la.
 *
 * Só guarda os ids e os dados de exibição: os prints ficam no servidor (PrintsCotacao), que é quem os anexa ao PDF.
 */
export interface OpcaoCesta {
  printId: string;
  fonte: string;
  titulo: string;
  preco: number | null;
  url: string;
  situacao: SituacaoPrint;
  motivo: string | null;
  capturadoEm: string | null;
  /** Conferência automática não achou o preço visível no print (null = conferido). Cesta antiga: undefined. */
  alerta?: string | null;
}

export interface ItemCesta {
  id: string;
  /** Termo da busca de onde o item veio (ajuda a lembrar o que era). */
  termo: string;
  produto: string;
  descricao: string;
  quantidade: number;
  /** Começa no preço à vista da escolhida; o atendente pode corrigir (o resumo do PDF mostra o coletado). */
  valorUnitario: number;
  /** A escolhida é sempre a primeira. */
  opcoes: OpcaoCesta[];
}

const CHAVE = 'helpagent.cesta-cotacao.v1';
/** Limite físico do impresso (helpagent.orcamento.max-itens). */
export const MAX_ITENS_CESTA = 10;
/** Enquanto algum print captura, pergunta ao servidor neste intervalo. Um item leva ~15 s. */
const INTERVALO_MS = 2500;

@Injectable({ providedIn: 'root' })
export class CestaCotacao {
  private readonly api = inject(CotacaoApi);

  readonly itens = signal<ItemCesta[]>(carregar());
  readonly total = computed(() => this.itens().reduce((s, i) => s + i.quantidade * i.valorUnitario, 0));
  readonly capturando = computed(() => this.itens().flatMap((i) => i.opcoes).filter((o) => o.situacao === 'CAPTURANDO').length);
  readonly falhas = computed(() => this.itens().flatMap((i) => i.opcoes).filter((o) => o.situacao === 'FALHOU').length);
  /** Prints que saíram mas não passaram na conferência do preço — não impedem gerar, mas pedem um olhar. */
  readonly alertas = computed(() => this.itens().flatMap((i) => i.opcoes).filter((o) => o.situacao === 'PRONTO' && o.alerta).length);
  /** Pronto para gerar: tem item e todo print tem imagem. */
  readonly pronta = computed(() => this.itens().length > 0 && this.capturando() === 0 && this.falhas() === 0);
  /** URLs já escolhidas — a tela da cotação marca "na cesta". */
  readonly urlsEscolhidas = computed(() => new Set(this.itens().map((i) => i.opcoes[0]?.url)));

  private timer?: ReturnType<typeof setTimeout>;

  constructor() {
    this.acompanhar();
  }

  /**
   * Põe um item na cesta e pede os prints ao servidor.
   * @param alternativas até 2 anúncios para comparar com a escolhida (as melhores do mesmo grupo)
   */
  async adicionar(cotacaoId: string, termo: string, escolhida: Avaliado, alternativas: Avaliado[]): Promise<void> {
    if (this.itens().length >= MAX_ITENS_CESTA) {
      throw new Error(`O impresso tem ${MAX_ITENS_CESTA} linhas e a cesta já está cheia.`);
    }
    const anuncios = [escolhida, ...alternativas].map((a) => a.anuncio);
    const prints = await firstValueFrom(this.api.solicitarPrints(cotacaoId, anuncios.map((a) => a.url)));
    const a = escolhida.anuncio;
    const item: ItemCesta = {
      id: prints[0].id,
      termo,
      produto: produtoDoTermo(termo),
      descricao: `${a.fonte} · ${a.titulo}`.slice(0, 500),
      quantidade: 1,
      valorUnitario: a.preco,
      opcoes: prints.map(opcaoDe),
    };
    this.salvar([...this.itens(), item]);
    this.acompanhar();
  }

  remover(id: string): void {
    this.salvar(this.itens().filter((i) => i.id !== id));
  }

  atualizar(id: string, campos: Partial<Pick<ItemCesta, 'produto' | 'descricao' | 'quantidade' | 'valorUnitario'>>): void {
    this.salvar(this.itens().map((i) => (i.id === id ? { ...i, ...campos } : i)));
  }

  limpar(): void {
    this.salvar([]);
  }

  async recapturar(printId: string): Promise<void> {
    this.trocarOpcao(opcaoDe(await firstValueFrom(this.api.recapturar(printId))));
    this.acompanhar();
  }

  async anexarManual(printId: string, arquivo: Blob, nome: string): Promise<void> {
    this.trocarOpcao(opcaoDe(await firstValueFrom(this.api.anexarPrint(printId, arquivo, nome))));
  }

  /** Enquanto houver print capturando, consulta o servidor; para sozinho quando não houver mais. */
  private acompanhar(): void {
    clearTimeout(this.timer);
    const pendentes = this.itens().flatMap((i) => i.opcoes).filter((o) => o.situacao === 'CAPTURANDO').map((o) => o.printId);
    if (!pendentes.length) return;
    this.timer = setTimeout(async () => {
      try {
        const atuais = await firstValueFrom(this.api.situacaoPrints(pendentes));
        atuais.forEach((p) => this.trocarOpcao(opcaoDe(p)));
      } catch (e) {
        // 404: o servidor não conhece mais o print (apagado da pasta) — vira falha para o atendente refazer
        if (e instanceof HttpErrorResponse && e.status === 404) {
          pendentes.forEach((id) => this.marcarFalha(id, 'o servidor não tem mais este print — escolha o item de novo'));
        }
      }
      this.acompanhar();
    }, INTERVALO_MS);
  }

  private trocarOpcao(nova: OpcaoCesta): void {
    this.salvar(this.itens().map((i) => ({ ...i, opcoes: i.opcoes.map((o) => (o.printId === nova.printId ? nova : o)) })));
  }

  private marcarFalha(printId: string, motivo: string): void {
    this.salvar(this.itens().map((i) => ({
      ...i,
      opcoes: i.opcoes.map((o) => (o.printId === printId ? { ...o, situacao: 'FALHOU' as const, motivo } : o)),
    })));
  }

  private salvar(itens: ItemCesta[]): void {
    this.itens.set(itens);
    try {
      localStorage.setItem(CHAVE, JSON.stringify(itens));
    } catch {
      // navegador sem armazenamento (janela anônima restrita): a cesta vale só enquanto a aba estiver aberta
    }
  }
}

/**
 * As 2 opções que vão junto com a escolhida: as de maior score do mesmo grupo (segmento), sem ela. Se o grupo
 * não tiver 2, completa com as melhores dos outros grupos. Escolher uma das 3 mais indicadas traz as outras duas;
 * escolher uma de fora traz as 2 melhores — a validação sempre vê a escolhida contra o melhor do mercado.
 */
export function alternativasPara(resultado: ResultadoCotacao, escolhida: Avaliado): Avaliado[] {
  const mesmo = (a: Avaliado) => a.anuncio.url === escolhida.anuncio.url && a.anuncio.fonte === escolhida.anuncio.fonte;
  const grupo = resultado.grupos.find((g) => [...g.top3, ...g.demais].some(mesmo));
  const porScore = (l: Avaliado[]) => l.filter((a) => !mesmo(a) && a.anuncio.url).sort((a, b) => b.score - a.score);
  const doGrupo = porScore(grupo ? [...grupo.top3, ...grupo.demais] : []);
  const resto = porScore(resultado.elegiveis).filter((a) => !doGrupo.includes(a));
  return [...doGrupo, ...resto].slice(0, 2);
}

function carregar(): ItemCesta[] {
  try {
    const bruto = localStorage.getItem(CHAVE);
    const lista = bruto ? (JSON.parse(bruto) as ItemCesta[]) : [];
    return Array.isArray(lista) ? lista : [];
  } catch {
    return [];
  }
}

function opcaoDe(p: PrintCotacao): OpcaoCesta {
  return { printId: p.id, fonte: p.fonte, titulo: p.titulo, preco: p.preco, url: p.url, situacao: p.situacao,
    motivo: p.motivo, capturadoEm: p.capturadoEm, alerta: p.alerta };
}

/** "ssd 480gb" → "SSD 480GB": o termo da busca costuma ser o nome curto do item no impresso. */
function produtoDoTermo(termo: string): string {
  return termo.trim().toUpperCase().slice(0, 200);
}
