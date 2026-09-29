import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// Tipos e chamada do painel "Uso da IA" (GET /api/uso-ia). Espelham os records de backend/.../usoia
// (ControleCotaIa.Situacao e UsoIaService.Painel), com os mesmos nomes de campo.

/** Situação de um modelo: os LIMITE_LOCAL_* são do contador do servidor; os SEM_COTA_*, respostas do Google. */
export type EstadoModelo =
  | 'DISPONIVEL'
  | 'ESFRIANDO'
  | 'LIMITE_LOCAL_MINUTO'
  | 'LIMITE_LOCAL_DIA'
  | 'SEM_COTA_MINUTO'
  | 'SEM_COTA_DIA'
  | 'INDISPONIVEL';

export interface SituacaoModelo {
  modelo: string;
  /** 0 = o melhor da cadeia */
  degrau: number;
  rpm: number;
  tpm: number;
  /** Limite diário em uso (o menor entre o configurado e o que o Google informou) */
  rpd: number;
  /** Preenchido quando o Google informou um limite diário menor que o configurado */
  rpdAprendido: number | null;
  requisicoesMinuto: number;
  tokensMinuto: number;
  requisicoesDia: number;
  tokensEntradaDia: number;
  tokensSaidaDia: number;
  errosDia: number;
  /** Vezes que o servidor desceu de degrau sem chamar este modelo (sem vez) */
  pulosDia: number;
  estado: EstadoModelo;
  /** Fim da pausa/resfriamento (ISO) */
  ate: string | null;
  motivo: string | null;
}

export interface TotaisUso {
  leituras: number;
  requisicoes: number;
  tokensEntrada: number;
  tokensSaida: number;
  erros: number;
  foraDoPrincipal: number;
  picoPorMinuto: number;
  momentoDoPico: string | null;
}

export interface HoraUso {
  inicio: string;
  requisicoes: number;
  erros: number;
  tokensEntrada: number;
}

export interface ChamadaIa {
  momento: string;
  modelo: string;
  degrau: number;
  papel: 'PRINCIPAL' | 'RESERVA' | 'DEGRAU' | 'REPETICAO';
  ocorrencia: 'OK' | 'SOBRECARGA' | 'COTA_MINUTO' | 'COTA_DIA' | 'INDISPONIVEL' | 'ERRO';
  status: number;
  tokensEntrada: number | null;
  tokensSaida: number | null;
  duracaoMs: number;
  modo: string | null;
  arquivos: number;
  origem: string | null;
}

export interface PainelUsoIa {
  agora: string;
  /** Quando a cota diária do Google zera (meia-noite do Pacífico) */
  viradaDoDia: string;
  modelos: SituacaoModelo[];
  hoje: TotaisUso;
  ultimas24h: HoraUso[];
  ultimas: ChamadaIa[];
}

@Injectable({ providedIn: 'root' })
export class UsoIaApi {
  private readonly http = inject(HttpClient);

  painel(): Observable<PainelUsoIa> {
    return this.http.get<PainelUsoIa>('/api/uso-ia');
  }
}
