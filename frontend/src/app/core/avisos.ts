import { Injectable, signal } from '@angular/core';

export interface Toast {
  id: number;
  mensagem: string;
  icone: string;
}

/** Toasts no canto e o overlay "Construindo…" durante chamadas longas (IA, PDF). */
@Injectable({ providedIn: 'root' })
export class Avisos {
  readonly toasts = signal<Toast[]>([]);
  readonly processando = signal<string | null>(null);
  private seq = 0;

  toast(mensagem: string, icone = '✓'): void {
    const t = { id: ++this.seq, mensagem, icone };
    this.toasts.update((l) => [...l, t]);
    setTimeout(() => this.toasts.update((l) => l.filter((x) => x.id !== t.id)), 2200);
  }

  iniciar(status: string): void {
    this.processando.set(status);
  }

  terminar(): void {
    this.processando.set(null);
  }
}
