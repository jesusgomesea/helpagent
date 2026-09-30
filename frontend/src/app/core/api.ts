import { HttpClient, HttpErrorResponse, HttpEvent, HttpEventType, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, filter, map, shareReplay, tap } from 'rxjs';
import {
  Anexo,
  GerarOrcamentoRequest,
  FiltrosHistorico,
  Fornecedor,
  FornecedorForm,
  ItemHistorico,
  Loja,
  LojaForm,
  ModoAquisicao,
  Pagina,
  Parametros,
  Problema,
  RespostaExtracao,
  ResultadoImportacao,
} from './modelos';

export interface ArquivoBaixado {
  nomeArquivo: string;
  blob: Blob;
}

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  /** Só as ativas — para o autocomplete da revisão. */
  lojas(): Observable<Loja[]> {
    return this.http.get<Loja[]>('/api/lojas');
  }

  /** Todas, inclusive desativadas — para a tela de cadastro. */
  lojasTodas(): Observable<Loja[]> {
    return this.http.get<Loja[]>('/api/lojas', { params: { incluirInativas: true } });
  }

  criarLoja(f: LojaForm): Observable<Loja> {
    return this.http.post<Loja>('/api/lojas', f);
  }

  atualizarLoja(numero: number, f: LojaForm): Observable<Loja> {
    return this.http.put<Loja>(`/api/lojas/${numero}`, f);
  }

  definirLojaAtiva(numero: number, ativa: boolean): Observable<Loja> {
    return this.http.patch<Loja>(`/api/lojas/${numero}/ativa`, { ativa });
  }

  /** Só os ativos — sugestão na revisão e filtro do histórico. */
  fornecedores(): Observable<Fornecedor[]> {
    return this.http.get<Fornecedor[]>('/api/fornecedores');
  }

  /** Todos, com o uso de cada um — tela de cadastro. */
  fornecedoresTodos(): Observable<Fornecedor[]> {
    return this.http.get<Fornecedor[]>('/api/fornecedores', { params: { incluirInativos: true } });
  }

  criarFornecedor(f: FornecedorForm): Observable<Fornecedor> {
    return this.http.post<Fornecedor>('/api/fornecedores', f);
  }

  atualizarFornecedor(id: number, f: FornecedorForm): Observable<Fornecedor> {
    return this.http.put<Fornecedor>(`/api/fornecedores/${id}`, f);
  }

  definirFornecedorAtivo(id: number, ativo: boolean): Observable<Fornecedor> {
    return this.http.patch<Fornecedor>(`/api/fornecedores/${id}/ativo`, { ativo });
  }

  /** Orçamentos já gerados para algum dos chamados do texto ("1021069, 1021070"). */
  porChamado(numeros: string): Observable<ItemHistorico[]> {
    return this.http.get<ItemHistorico[]>('/api/historico/por-chamado', { params: { numeros } });
  }

  /** Uma chamada só por carga da página: cabeçalho, telas e guardas de rota leem o mesmo resultado. */
  private readonly parametros$ = this.http.get<Parametros>('/api/parametros').pipe(shareReplay(1));

  parametros(): Observable<Parametros> {
    return this.parametros$;
  }

  /**
   * @param aoProgredir recebe a fase atual: 'enviando' (com o % do upload) e depois 'lendo' (arquivos
   *                    já no servidor, esperando a IA) — é o que o overlay mostra ao usuário
   */
  extrair(
    modo: ModoAquisicao,
    chamados: Anexo[],
    orcamentos: Anexo[],
    aoProgredir: (fase: 'enviando' | 'lendo', pct?: number) => void = () => {},
  ): Observable<RespostaExtracao> {
    const form = new FormData();
    form.append('modo', modo);
    chamados.forEach((a) => form.append('chamados', a.arquivo, a.nome));
    orcamentos.forEach((a) => form.append('orcamentos', a.arquivo, a.nome));
    return this.http.post<RespostaExtracao>('/api/extracoes', form, { observe: 'events', reportProgress: true }).pipe(
      tap((e: HttpEvent<RespostaExtracao>) => {
        if (e.type === HttpEventType.UploadProgress) {
          const pct = e.total ? Math.round((100 * e.loaded) / e.total) : undefined;
          aoProgredir(pct !== undefined && pct >= 100 ? 'lendo' : 'enviando', pct);
        }
      }),
      filter((e): e is HttpResponse<RespostaExtracao> => e.type === HttpEventType.Response),
      map((e) => e.body!),
    );
  }

  gerar(dados: GerarOrcamentoRequest, chamados: Anexo[], orcamentos: Anexo[]): Observable<ArquivoBaixado> {
    const form = new FormData();
    form.append('dados', new Blob([JSON.stringify(dados)], { type: 'application/json' }));
    chamados.forEach((a) => form.append('chamados', a.arquivo, a.nome));
    orcamentos.forEach((a) => form.append('orcamentos', a.arquivo, a.nome));
    return this.http
      .post('/api/orcamentos', form, { observe: 'response', responseType: 'blob' })
      .pipe(map((r) => paraArquivo(r, 'orcamento.pdf')));
  }

  /**
   * @param modo    aba do histórico; null = todos os tipos
   * @param lixeira true = aba "Lixeira" (o que foi apagado nos últimos 30 dias)
   */
  historico(busca: string, pagina = 0, modo: ModoAquisicao | null = null, tamanho = 20, lixeira = false,
    filtros: FiltrosHistorico = {}): Observable<Pagina<ItemHistorico>> {
    let params = comFiltros(new HttpParams().set('pagina', pagina).set('tamanho', tamanho), filtros);
    if (busca.trim()) params = params.set('busca', busca.trim());
    if (modo) params = params.set('modo', modo);
    if (lixeira) params = params.set('lixeira', true);
    return this.http.get<Pagina<ItemHistorico>>('/api/historico', { params });
  }

  /** Quantos orçamentos de cada aba batem com a busca: { TODOS, REQUISICAO, OPEX, CAPEX, LIXEIRA }. */
  contagemHistorico(busca: string, filtros: FiltrosHistorico = {}): Observable<Record<ModoAquisicao | 'TODOS' | 'LIXEIRA', number>> {
    let params = comFiltros(new HttpParams(), filtros);
    if (busca.trim()) params = params.set('busca', busca.trim());
    return this.http.get<Record<ModoAquisicao | 'TODOS' | 'LIXEIRA', number>>('/api/historico/contagem', { params });
  }

  baixar(id: number): Observable<ArquivoBaixado> {
    return this.http
      .get(`/api/historico/${id}/pdf`, { observe: 'response', responseType: 'blob' })
      .pipe(map((r) => paraArquivo(r, `orcamento_${id}.pdf`)));
  }

  /** Backup completo do histórico em JSON (inclui os PDFs). */
  exportarHistorico(): Observable<ArquivoBaixado> {
    return this.http
      .get('/api/historico/exportar', { observe: 'response', responseType: 'blob' })
      .pipe(map((r) => paraArquivo(r, 'historico-orcamentos.json')));
  }

  /** Aceita o backup deste sistema e o JSON exportado pelo HTML v3.5. */
  importarHistorico(arquivo: File): Observable<ResultadoImportacao> {
    const form = new FormData();
    form.append('arquivo', arquivo, arquivo.name);
    return this.http.post<ResultadoImportacao>('/api/historico/importar', form);
  }

  /** "Apagar": manda para a lixeira (volta por 30 dias). */
  remover(id: number): Observable<void> {
    return this.http.delete<void>(`/api/historico/${id}`);
  }

  restaurar(id: number): Observable<void> {
    return this.http.post<void>(`/api/historico/${id}/restaurar`, {});
  }

  /** Só para o que já está na lixeira: apaga o registro e o PDF de vez. */
  excluirDeVez(id: number): Observable<void> {
    return this.http.delete<void>(`/api/historico/${id}/definitivo`);
  }
}

function paraArquivo(r: HttpResponse<Blob>, padrao: string): ArquivoBaixado {
  return { nomeArquivo: nomeDoCabecalho(r.headers.get('Content-Disposition')) ?? padrao, blob: r.body! };
}

/** Lê filename* (RFC 5987, UTF-8 — o nome tem "ç") antes do filename simples. */
export function nomeDoCabecalho(cd: string | null): string | null {
  if (!cd) return null;
  const estendido = /filename\*=UTF-8''([^;]+)/i.exec(cd);
  if (estendido) return decodeURIComponent(estendido[1]);
  const simples = /filename="?([^";]+)"?/i.exec(cd);
  return simples ? simples[1] : null;
}

export function salvarArquivo({ nomeArquivo, blob }: ArquivoBaixado): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = nomeArquivo;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/**
 * Transforma o erro HTTP em linhas legíveis. Respostas de erro de chamadas com
 * responseType 'blob' chegam como Blob, por isso a leitura é assíncrona.
 */
/** Só os filtros preenchidos viram parâmetro (o servidor trata ausente como "não filtra"). */
function comFiltros(params: HttpParams, f: FiltrosHistorico): HttpParams {
  for (const [chave, valor] of Object.entries(f)) {
    if (valor !== undefined && valor !== null && valor !== '') params = params.set(chave, String(valor));
  }
  return params;
}

export async function mensagensDeErro(err: unknown): Promise<string[]> {
  if (!(err instanceof HttpErrorResponse)) return [String(err)];
  if (err.status === 0) return ['Servidor fora do ar ou sem conexão.'];
  let corpo: Problema | null = err.error ?? null;
  if (err.error instanceof Blob) {
    try {
      corpo = JSON.parse(await err.error.text()) as Problema;
    } catch {
      corpo = null;
    }
  }
  const linhas: string[] = [];
  const n = corpo?.tentativas ?? 1;
  if (corpo?.detail) linhas.push(n > 1 ? `Erro após ${n} tentativa(s): ${corpo.detail}` : corpo.detail);
  if (corpo?.problemas?.length) linhas.push(...corpo.problemas);
  if (!linhas.length) linhas.push(`Erro ${err.status}`);
  // o suporte acha a requisição no log por este código (IdRequisicaoFiltro no backend)
  if (corpo?.idRequisicao && err.status >= 500) linhas.push(`Código para o suporte: ${corpo.idRequisicao}`);
  return linhas;
}
