import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Api, mensagensDeErro } from '../../core/api';
import { Avisos } from '../../core/avisos';
import { Loja, LojaForm, TemplateCodigo } from '../../core/modelos';
import { Faixa } from '../../layout/faixa';
import { Icone } from '../../layout/icone';

/**
 * Cadastro de lojas: quando abre uma loja nova, o helpdesk cadastra aqui, sem precisar de alguém mexer no
 * código ou no banco. Sem login: toda alteração fica no log do servidor, com o IP de quem fez.
 *
 * Regras (validadas no servidor): número único e fixo (trocou de número → nova loja e desativar a antiga),
 * CNPJ conferido pelos dígitos verificadores, loja nunca é apagada, só desativada, porque os orçamentos
 * antigos continuam apontando para ela.
 */
@Component({
  selector: 'ha-lojas',
  imports: [ReactiveFormsModule, Faixa, Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ha-faixa sobretitulo="Helpdesk · Cadastro" titulo="Lojas" subtitulo="Cadastro usado para empresa, CNPJ e impresso de cada orçamento" />
    <main class="container">
      <section class="card">
        <header class="card-header">
          <h2>{{ editando() ? 'Editar loja ' + editando() : 'Nova loja' }}</h2>
          @if (editando()) { <button type="button" class="btn-link cabecalho-link" (click)="limpar()">cancelar edição</button> }
        </header>
        <form class="card-body" [formGroup]="form" (ngSubmit)="salvar()">
          <div class="linha cols-3">
            <label>Número
              <input type="number" min="1" formControlName="numero" placeholder="ex: 812" [readonly]="!!editando()">
              @if (editando()) { <span class="ajuda">O número não muda. Trocou? Cadastre outra e desative esta.</span> }
            </label>
            <label>Nome <input formControlName="nome" placeholder="ex: DAMASIO PE"></label>
            <label>CNPJ <input formControlName="cnpj" placeholder="00.000.000/0000-00"></label>
          </div>
          <div class="linha cols-3">
            <label>Empresa (sai no impresso) <input formControlName="empresa" placeholder="ex: DAMASIO-PE"></label>
            <label>Impresso
              <select formControlName="template">
                @for (t of templates; track t.codigo) { <option [value]="t.codigo">{{ t.rotulo }}</option> }
              </select>
            </label>
            <div class="acao-form">
              <button class="btn-primario" type="submit" [disabled]="form.invalid || salvando()">
                {{ editando() ? 'Salvar alterações' : 'Cadastrar loja' }}
              </button>
            </div>
          </div>
          @if (erros().length) {
            <div class="status erro"><ul>@for (e of erros(); track $index) { <li>{{ e }}</li> }</ul></div>
          }
        </form>
      </section>

      <section class="card">
        <header class="card-header">
          <h2>Lojas cadastradas</h2>
          <span class="card-sub">{{ ativas() }} ativa(s) · {{ lojas().length - ativas() }} desativada(s)</span>
        </header>
        <div class="card-body">
          <input class="busca" #b placeholder="Buscar por número, nome, empresa ou CNPJ..." (input)="termo.set(b.value)">
          <div class="tabela-lojas">
            <div class="linha-loja cabecalho"><span>Nº</span><span>Nome</span><span>Empresa</span><span>CNPJ</span><span>Impresso</span><span></span></div>
            @for (l of filtradas(); track l.numero) {
              <div class="linha-loja" [class.inativa]="!l.ativa">
                <span class="loja-num">{{ l.numero }}</span>
                <span>{{ l.nome }} @if (!l.ativa) { <em>(desativada)</em> }</span>
                <span>{{ l.empresa }}</span>
                <span class="mono">{{ l.cnpj }}</span>
                <span><span class="tmpl tmpl-{{ l.template }}">{{ l.templateRotulo }}</span></span>
                <span class="acoes-loja">
                  <button type="button" (click)="editar(l)" title="Editar">Editar</button>
                  <button type="button" [class.perigo]="l.ativa" (click)="alternarAtiva(l)">
                    {{ l.ativa ? 'Desativar' : 'Reativar' }}
                  </button>
                </span>
              </div>
            } @empty {
              <div class="hist-vazio"><ha-icone nome="recibo" /> Nenhuma loja encontrada.</div>
            }
          </div>
        </div>
      </section>
    </main>
  `,
})
export class LojasPage {
  private readonly api = inject(Api);
  private readonly avisos = inject(Avisos);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly templates: { codigo: TemplateCodigo; rotulo: string }[] = [
    { codigo: 'DAM', rotulo: 'DAMÁSIO' },
    { codigo: 'TD', rotulo: 'TD MOTOPEÇAS' },
    { codigo: 'RDAM', rotulo: 'R DAMÁSIO' },
    { codigo: 'CPL', rotulo: 'CPL MOTOPARTS' },
  ];

  protected readonly lojas = signal<Loja[]>([]);
  protected readonly termo = signal('');
  protected readonly editando = signal<number | null>(null);
  protected readonly salvando = signal(false);
  protected readonly erros = signal<string[]>([]);

  protected readonly ativas = computed(() => this.lojas().filter((l) => l.ativa).length);
  protected readonly filtradas = computed(() => {
    const t = this.termo().trim().toLowerCase();
    if (!t) return this.lojas();
    const soDigitos = t.replace(/\D/g, '');
    return this.lojas().filter(
      (l) =>
        String(l.numero) === t ||
        l.nome.toLowerCase().includes(t) ||
        l.empresa.toLowerCase().includes(t) ||
        (soDigitos.length >= 4 && l.cnpj.replace(/\D/g, '').includes(soDigitos)),
    );
  });

  protected readonly form = this.fb.group({
    numero: this.fb.control<number | null>(null, [Validators.required, Validators.min(1)]),
    nome: ['', Validators.required],
    cnpj: ['', Validators.required],
    empresa: ['', Validators.required],
    template: this.fb.control<TemplateCodigo>('DAM'),
  });

  constructor() {
    this.carregar();
  }

  private async carregar(): Promise<void> {
    this.lojas.set(await firstValueFrom(this.api.lojasTodas()));
  }

  protected editar(l: Loja): void {
    this.editando.set(l.numero);
    this.erros.set([]);
    this.form.setValue({ numero: l.numero, nome: l.nome, cnpj: l.cnpj, empresa: l.empresa, template: l.template });
    scrollTo({ top: 0, behavior: 'smooth' });
  }

  protected limpar(): void {
    this.editando.set(null);
    this.erros.set([]);
    this.form.reset({ numero: null, nome: '', cnpj: '', empresa: '', template: 'DAM' });
  }

  protected async salvar(): Promise<void> {
    const v = this.form.getRawValue();
    const dados: LojaForm = { numero: v.numero!, nome: v.nome, cnpj: v.cnpj, empresa: v.empresa, template: v.template };
    this.salvando.set(true);
    this.erros.set([]);
    try {
      const n = this.editando();
      const salva = await firstValueFrom(n ? this.api.atualizarLoja(n, dados) : this.api.criarLoja(dados));
      this.avisos.toast(n ? `Loja ${salva.numero} atualizada` : `Loja ${salva.numero} cadastrada`);
      this.limpar();
      await this.carregar();
    } catch (e) {
      this.erros.set(await mensagensDeErro(e));
    } finally {
      this.salvando.set(false);
    }
  }

  protected async alternarAtiva(l: Loja): Promise<void> {
    const acao = l.ativa ? 'Desativar' : 'Reativar';
    if (l.ativa && !confirm(`${acao} a loja ${l.numero} (${l.nome})? Ela some da busca da revisão; os orçamentos antigos continuam.`)) return;
    try {
      await firstValueFrom(this.api.definirLojaAtiva(l.numero, !l.ativa));
      this.avisos.toast(`Loja ${l.numero} ${l.ativa ? 'desativada' : 'reativada'}`);
      await this.carregar();
    } catch (e) {
      this.avisos.toast((await mensagensDeErro(e)).join(' '), '⚠');
    }
  }
}
