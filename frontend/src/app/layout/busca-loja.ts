import { ChangeDetectionStrategy, Component, computed, effect, input, model, signal, untracked } from '@angular/core';
import { Loja } from '../core/modelos';

/** Quantas sugestões aparecem na lista (o resto se acha refinando o que se digita). */
const MAX_SUGESTOES = 8;

/**
 * Busca rápida de loja (30/09/2026): um campo só, que aceita **número** ("7", "023"), **nome** ("tdpi sul"),
 * cidade ou **CNPJ** (5+ dígitos), com lista de sugestões navegável pelo teclado — ↑/↓ escolhe, Enter confirma,
 * Esc fecha. Substitui o par "Número da loja" + "Busca por nome" das duas telas de revisão, que repetiam a mesma
 * lógica e exigiam acertar o campo certo. "023" e "23" continuam sendo a mesma loja.
 *
 * Uso: `<ha-busca-loja [lojas]="lojas" [(loja)]="lojaEscolhida" [sugestao]="textoLidoPelaIa" />`.
 */
@Component({
  selector: 'ha-busca-loja',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <label class="busca-loja">Loja
      <input role="combobox" aria-autocomplete="list" [attr.aria-expanded]="aberta()" aria-controls="lista-lojas"
        [attr.aria-activedescendant]="aberta() && opcoes().length ? 'loja-opcao-' + ativa() : null"
        placeholder="número, nome, cidade ou CNPJ — ex: 7, tdpi sul, 06.845.796" autocomplete="off"
        [value]="texto()" (input)="digitar($any($event.target).value)" (focus)="abrir()" (blur)="fecharDepois()"
        (keydown)="tecla($event)">
      <span class="ajuda">↑ ↓ para escolher · Enter confirma · Esc fecha</span>
      @if (aberta() && opcoes().length) {
        <ul class="busca-loja-lista" id="lista-lojas" role="listbox">
          @for (l of opcoes(); track l.numero; let i = $index) {
            <li role="option" [id]="'loja-opcao-' + i" [attr.aria-selected]="i === ativa()" [class.ativa]="i === ativa()"
              (mousedown)="escolher(l); $event.preventDefault()" (mouseenter)="ativa.set(i)">
              <span class="loja-num">{{ l.numero }}</span>
              <span class="busca-loja-nome">{{ l.nome }}</span>
              <span class="loja-cnpj">{{ l.cidade ? l.cidade + '/' + l.uf + ' · ' : '' }}{{ l.cnpj }}</span>
            </li>
          }
        </ul>
      } @else if (aberta() && texto().trim().length > 0) {
        <div class="busca-loja-lista busca-loja-vazia">Nenhuma loja com "{{ texto().trim() }}"</div>
      }
    </label>
  `,
})
export class BuscaLoja {
  readonly lojas = input.required<Loja[]>();
  /** A loja escolhida (duas vias: a tela pode definir, por exemplo com o que a IA leu). */
  readonly loja = model<Loja | null>(null);
  /** Texto para mostrar quando ainda não há loja escolhida (ex.: o que a IA leu e não bateu com o cadastro). */
  readonly sugestao = input<string>('');

  protected readonly texto = signal('');
  protected readonly aberta = signal(false);
  protected readonly ativa = signal(0);
  private fechando?: ReturnType<typeof setTimeout>;

  protected readonly opcoes = computed(() => filtrarLojas(this.lojas(), this.texto()).slice(0, MAX_SUGESTOES));

  constructor() {
    // a loja escolhida por fora (IA, edição) aparece no campo como "23 · DAMASIO PE"
    effect(() => {
      const l = this.loja();
      if (l) untracked(() => this.texto.set(rotuloLoja(l)));
    });
    // sugestão nova (outra leitura da IA) só entra se não há loja escolhida — nunca por cima do que se digita
    effect(() => {
      const s = this.sugestao();
      untracked(() => {
        if (!this.loja()) this.texto.set(s);
      });
    });
  }

  protected digitar(v: string): void {
    this.texto.set(v);
    this.ativa.set(0);
    this.aberta.set(true);
    // digitou algo diferente do rótulo da loja escolhida: desfaz a escolha até confirmar outra
    const atual = this.loja();
    if (atual && v !== rotuloLoja(atual)) this.loja.set(null);
  }

  protected abrir(): void {
    clearTimeout(this.fechando);
    this.aberta.set(true);
  }

  /** O clique numa opção (mousedown) acontece antes do blur; o atraso só evita piscar. */
  protected fecharDepois(): void {
    this.fechando = setTimeout(() => this.aberta.set(false), 120);
  }

  protected escolher(l: Loja): void {
    this.loja.set(l);
    this.texto.set(rotuloLoja(l));
    this.aberta.set(false);
  }

  protected tecla(ev: KeyboardEvent): void {
    const n = this.opcoes().length;
    if (ev.key === 'ArrowDown') {
      this.aberta.set(true);
      if (n) this.ativa.set((this.ativa() + 1) % n);
      ev.preventDefault();
    } else if (ev.key === 'ArrowUp') {
      if (n) this.ativa.set((this.ativa() - 1 + n) % n);
      ev.preventDefault();
    } else if (ev.key === 'Enter' && !ev.ctrlKey && !ev.metaKey) {
      // Enter aqui escolhe a loja, não envia o formulário (Ctrl+Enter continua gerando)
      ev.preventDefault();
      if (this.aberta() && n) this.escolher(this.opcoes()[this.ativa()]);
    } else if (ev.key === 'Escape') {
      this.aberta.set(false);
    }
  }
}

export function rotuloLoja(l: Loja): string {
  return `${l.numero} · ${l.nome}`;
}

/**
 * Lojas que batem com o que foi digitado, das mais prováveis para as menos: número exato, número que começa com,
 * nome que começa com, nome/empresa/cidade/razão social que contém, CNPJ (5+ dígitos). Função pura, testável.
 */
export function filtrarLojas(lojas: Loja[], termo: string): Loja[] {
  const t = sem(termo.trim());
  if (!t) return lojas;
  // "23 · DAMASIO PE" (o rótulo) também acha a própria loja
  const numeroNoRotulo = /^(\d+)\s*·/.exec(termo.trim());
  const digitos = t.replace(/\D/g, '');
  const soNumero = /^\d+$/.test(t);
  const pontos = (l: Loja): number => {
    if (numeroNoRotulo && l.numero === Number(numeroNoRotulo[1])) return 100;
    if (soNumero && t.length <= 4) {
      if (l.numero === Number(t)) return 90;
      if (String(l.numero).startsWith(String(Number(t)))) return 60;
      // número curto é número de loja: não procurar "7" dentro de "TDMA-107" (virava ruído na lista)
      return 0;
    }
    const nome = sem(l.nome);
    if (nome.startsWith(t)) return 80;
    if (nome.includes(t)) return 70;
    if ([l.empresa, l.cidade, l.razaoSocial].some((x) => x && sem(x).includes(t))) return 50;
    if (digitos.length >= 5 && l.cnpj.replace(/\D/g, '').includes(digitos)) return 40;
    return 0;
  };
  return lojas
    .map((l) => ({ l, p: pontos(l) }))
    .filter((x) => x.p > 0)
    .sort((a, b) => b.p - a.p || a.l.numero - b.l.numero)
    .map((x) => x.l);
}

/** Minúsculas e sem acento ("São Luís" = "sao luis"). */
function sem(s: string): string {
  return s.toLowerCase().normalize('NFD').replace(/\p{M}/gu, '');
}
