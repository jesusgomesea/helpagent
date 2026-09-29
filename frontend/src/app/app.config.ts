import { registerLocaleData } from '@angular/common';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import localePt from '@angular/common/locales/pt';
import {
  ApplicationConfig,
  LOCALE_ID,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { carregarConfiguracao } from './core/configuracao';
import { interceptadorApi } from './core/interceptador-api';

registerLocaleData(localePt);

/**
 * Configuração da aplicação: sem zone.js (detecção de mudanças por signals), locale pt-BR
 * para datas/moeda e HttpClient com fetch. As chamadas usam caminho relativo (/api) — o proxy do
 * ng serve (proxy.conf.json) encaminha para o backend. Antes da primeira tela lê o config.json (endereço da API,
 * cookies) e toda chamada passa pelo interceptadorApi (apiBase, X-Request-Id, token do portal) — docs/INTEGRACAO.md §6.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideRouter(routes, withComponentInputBinding()),
    provideAppInitializer(carregarConfiguracao),
    provideHttpClient(withFetch(), withInterceptors([interceptadorApi])),
    { provide: LOCALE_ID, useValue: 'pt-BR' },
  ],
};
