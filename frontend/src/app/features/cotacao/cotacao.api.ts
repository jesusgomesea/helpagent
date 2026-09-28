import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ArquivoBaixado, nomeDoCabecalho } from '../../core/api';

// Tipos e chamadas da cotação em lojas online — funcionalidade à parte do orçamento, por isso num arquivo
// próprio e não em core/api.ts. Os tipos espelham os records de backend/.../cotacao (mesmos nomes de campo).

/** Anúncio coletado. null = a página não informou (≠ zero): a tela mostra "—". */
export interface Anuncio {
  titulo: string;
  preco: number;
  precoDe: number | null;
  nota: number | null;
  vendidos: number | null;
  full: boolean;
  lojaOficial: boolean;
  freteGratis: boolean;
  recondicionado: boolean;
  internacional: boolean;
  pais: string;
  vendedor: string;
  url: string;
  patrocinado: boolean;
  /** Nome da loja ("Kabum", "Mercado Livre"...) */
  fonte: string;
  /** Quem vende é a própria loja ou o fabricante (não um lojista de marketplace) */
  vendedorProprio: boolean;
}

export type Origem = 'qualquer' | 'nacional' | 'internacional';

/** Critérios da cotação (CriteriosCotacao.java). O padrão vem do servidor em /api/cotacao/estado. */
export interface CriteriosCotacao {
  pesoPreco: number;
  pesoEntrega: number;
  pesoFornecedor: number;
  pesoMarca: number;
  notaMinima: number;
  vendidosMinimo: number;
  aceitaRecondicionado: boolean;
  exigirFull: boolean;
  origem: Origem;
  precoMax: number | null;
  deveConter: string;
  naoPodeConter: string;
  pontosFull: number;
  pontosFreteGratis: number;
  pontosSemFrete: number;
  marcasPreferidas: string;
  segmentos: string;
  /** Loja própria/fabricante sem nota pública: fica, com reputação presumida (em vez de ser descartada) */
  aceitarLojaPropriaSemNota: boolean;
}

export interface Parciais {
  preco: number;
  entrega: number;
  fornecedor: number;
  marca: number;
}

export interface Avaliado {
  anuncio: Anuncio;
  segmento: string | null;
  tier: string;
  parciais: Parciais;
  score: number;
}

export interface Descartado {
  anuncio: Anuncio;
  motivo: string;
}

export interface Grupo {
  rotulo: string;
  semSegmento: boolean;
  qtd: number;
  menorPreco: number;
  precoMedio: number;
  top3: Avaliado[];
  demais: Avaliado[];
}

export interface Resumo {
  analisados: number;
  elegiveis: number;
  descartados: number;
  menorPreco: number;
  maiorPreco: number;
  mediana: number;
  precoMedio: number;
  pesosEfetivos: Parciais;
  semVolume: number;
  internacionais: number;
}

export interface ResultadoCotacao {
  elegiveis: Avaliado[];
  descartados: Descartado[];
  grupos: Grupo[];
  /** null quando nenhum anúncio passou nos eliminatórios */
  resumo: Resumo | null;
  criterios: CriteriosCotacao;
}

export interface RespostaCotacao {
  id: string;
  termo: string;
  coletadoEm: string;
  segundos: number;
  esperouFila: boolean;
  avisos: string[];
  /** Anúncios aproveitados de cada loja consultada, na ordem pedida (0 = não devolveu nada) */
  porFonte: Record<string, number>;
  resultado: ResultadoCotacao;
}

export type GrupoLoja = 'VAREJO_TI' | 'MARKETPLACE' | 'FABRICANTE';

export interface LojaCotacao {
  id: string;
  nome: string;
  grupo: GrupoLoja;
}

/** Atalho de busca: cada nível soma um grupo de lojas ao anterior (definidos em CotacaoService.NIVEIS). */
export interface NivelBusca {
  numero: number;
  nome: string;
  descricao: string;
  fontes: string[];
}

export interface EstadoCotacao {
  ocupado: boolean;
  padrao: CriteriosCotacao;
  maxPaginas: number;
  lojas: LojaCotacao[];
  niveis: NivelBusca[];
}

@Injectable({ providedIn: 'root' })
export class CotacaoApi {
  private readonly http = inject(HttpClient);

  estado(): Observable<EstadoCotacao> {
    return this.http.get<EstadoCotacao>('/api/cotacao/estado');
  }

  /**
   * Abre o Chrome no servidor e coleta nas lojas pedidas, em paralelo (medido em 28/09/2026: nível 1 ~20 s,
   * as 7 lojas juntas 33–42 s).
   * Uma cotação por vez no servidor inteiro.
   */
  cotar(termo: string, paginas: number, fontes: string[], criterios: CriteriosCotacao): Observable<RespostaCotacao> {
    return this.http.post<RespostaCotacao>('/api/cotacao', { termo, paginas, fontes, criterios });
  }

  /** Mesmos anúncios, critérios novos: só o motor roda, resposta na hora. */
  reavaliar(id: string, criterios: CriteriosCotacao): Observable<RespostaCotacao> {
    return this.http.post<RespostaCotacao>(`/api/cotacao/${id}/reavaliar`, { criterios });
  }

  planilha(id: string): Observable<ArquivoBaixado> {
    return this.http.get(`/api/cotacao/${id}/planilha`, { observe: 'response', responseType: 'blob' }).pipe(
      map((r) => ({
        nomeArquivo: nomeDoCabecalho(r.headers.get('Content-Disposition')) ?? 'cotacao.xlsx',
        blob: r.body!,
      })),
    );
  }
}
