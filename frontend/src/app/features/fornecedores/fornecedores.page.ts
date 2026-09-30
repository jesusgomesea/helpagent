import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Api, mensagensDeErro } from '../../core/api';
import { Avisos } from '../../core/avisos';
import { Fornecedor, FornecedorForm } from '../../core/modelos';
import { Faixa } from '../../layout/faixa';
import { Icone } from '../../layout/icone';

/**
 * Cadastro de fornecedores (30/09/2026). Cresce sozinho: ao gerar um orçamento com fornecedor novo, o servidor
 * cadastra, e cada nome com que a IA leu o fornecedor vira apelido. Aqui o helpdesk padroniza o nome, acrescenta
 * CNPJ/apelidos e desativa o que não usa mais. Fica junto das Lojas (/lojas/fornecedores), sem link novo no menu.
 *
 * Regras (no servidor): CNPJ conferido e único; fornecedor não se apaga (os orçamentos apontam para ele), só desativa.
 */
@Component({
  selector: 'ha-fornecedores',
  imports: [ReactiveFormsModule, RouterLink, DatePipe, Faixa, Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ha-faixa sobretitulo="Helpdesk · Cadastro" titulo="Fornecedores" subtitulo="Quem emite os orçamentos: reconhecidos na leitura pela IA" />
    <main class="container">
      <div class="seg cadastro-seg" role="tablist" aria-label="Cadastro">
        <a role="tab" routerLink="/lojas">Lojas</a>
        <a role="tab" class="ativo" aria-selected="true">Fornecedores</a>
      </div>

      <section class="card">
        <header class="card-header">
          <h2>{{ editando() ? 'Editar fornecedor' : 'Novo fornecedor' }}</h2>
          @if (editando()) { <button type="button" class="btn-link cabecalho-link" (click)="limpar()">cancelar edição</button> }
        </header>
        <form class="card-body" [formGroup]="form" (ngSubmit)="salvar()">
          <div class="linha cols-2">
            <label>Nome (como aparece no histórico) <input formControlName="nome" placeholder="ex: Infotec Soluções"></label>
            <label>CNPJ <input formControlName="cnpj" placeholder="00.000.000/0000-00 (opcional)"></label>
          </div>
          <label>Outros nomes com que aparece nos documentos (um por linha)
            <textarea formControlName="apelidos" rows="3" placeholder="ex: INFOTEC SOLUCOES EM INFORMATICA LTDA"></textarea>
            <span class="ajuda">A leitura por IA reconhece o fornecedor por estes nomes quando o documento não traz o CNPJ.</span>
          </label>
          <div class="acao-form">
            <button class="btn-primario" type="submit" [disabled]="form.invalid || salvando()">
              {{ editando() ? 'Salvar alterações' : 'Cadastrar fornecedor' }}
            </button>
          </div>
          @if (erros().length) {
            <div class="status erro"><ul>@for (e of erros(); track $index) { <li>{{ e }}</li> }</ul></div>
          }
        </form>
      </section>

      <section class="card">
        <header class="card-header">
          <h2>Fornecedores cadastrados</h2>
          <span class="card-sub">{{ ativos() }} ativo(s) · {{ lista().length - ativos() }} desativado(s)</span>
        </header>
        <div class="card-body">
          <input class="busca" #b placeholder="Buscar por nome, apelido ou CNPJ..." (input)="termo.set(b.value)">
          <div class="tabela-lojas">
            <div class="linha-fornecedor cabecalho"><span>Nome</span><span>CNPJ</span><span>Orçamentos</span><span>Último uso</span><span></span></div>
            @for (f of filtrados(); track f.id) {
              <div class="linha-fornecedor" [class.inativa]="!f.ativo">
                <span>{{ f.nome }} @if (!f.ativo) { <em>(desativado)</em> }
                  @if (f.apelidos.length) { <span class="loja-sub">também: {{ f.apelidos.join(' · ') }}</span> }
                </span>
                <span class="mono">{{ f.cnpj ?? '—' }}</span>
                <span class="mono">{{ f.orcamentos }}</span>
                <span class="mono">{{ f.ultimoUso ? (f.ultimoUso | date: 'dd/MM/yyyy') : '—' }}</span>
                <span class="acoes-loja">
                  <a [routerLink]="['/historico']" [queryParams]="{ fornecedor: f.id }" title="Ver os orçamentos deste fornecedor">Histórico</a>
                  <button type="button" (click)="editar(f)">Editar</button>
                  <button type="button" [class.perigo]="f.ativo" (click)="alternarAtivo(f)">{{ f.ativo ? 'Desativar' : 'Reativar' }}</button>
                </span>
              </div>
            } @empty {
              <div class="hist-vazio"><ha-icone nome="recibo" /> Nenhum fornecedor ainda. Eles entram sozinhos ao gerar orçamentos.</div>
            }
          </div>
        </div>
      </section>
    </main>
  `,
})
export class FornecedoresPage {
  private readonly api = inject(Api);
  private readonly avisos = inject(Avisos);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly lista = signal<Fornecedor[]>([]);
  protected readonly termo = signal('');
  protected readonly editando = signal<number | null>(null);
  protected readonly salvando = signal(false);
  protected readonly erros = signal<string[]>([]);

  protected readonly ativos = computed(() => this.lista().filter((f) => f.ativo).length);
  protected readonly filtrados = computed(() => {
    const t = this.termo().trim().toLowerCase();
    if (!t) return this.lista();
    const digitos = t.replace(/\D/g, '');
    return this.lista().filter(
      (f) =>
        f.nome.toLowerCase().includes(t) ||
        f.apelidos.some((a) => a.toLowerCase().includes(t)) ||
        (digitos.length >= 4 && (f.cnpj ?? '').replace(/\D/g, '').includes(digitos)),
    );
  });

  protected readonly form = this.fb.group({
    nome: ['', Validators.required],
    cnpj: [''],
    apelidos: [''],
  });

  constructor() {
    this.carregar();
  }

  private async carregar(): Promise<void> {
    this.lista.set(await firstValueFrom(this.api.fornecedoresTodos()));
  }

  protected editar(f: Fornecedor): void {
    this.editando.set(f.id);
    this.erros.set([]);
    this.form.setValue({ nome: f.nome, cnpj: f.cnpj ?? '', apelidos: f.apelidos.join('\n') });
    scrollTo({ top: 0, behavior: 'smooth' });
  }

  protected limpar(): void {
    this.editando.set(null);
    this.erros.set([]);
    this.form.reset({ nome: '', cnpj: '', apelidos: '' });
  }

  protected async salvar(): Promise<void> {
    const v = this.form.getRawValue();
    const dados: FornecedorForm = {
      nome: v.nome,
      cnpj: v.cnpj,
      apelidos: v.apelidos.split('\n').map((a) => a.trim()).filter(Boolean),
    };
    this.salvando.set(true);
    this.erros.set([]);
    try {
      const id = this.editando();
      await firstValueFrom(id ? this.api.atualizarFornecedor(id, dados) : this.api.criarFornecedor(dados));
      this.avisos.toast(id ? 'Fornecedor atualizado' : 'Fornecedor cadastrado');
      this.limpar();
      await this.carregar();
    } catch (e) {
      this.erros.set(await mensagensDeErro(e));
    } finally {
      this.salvando.set(false);
    }
  }

  protected async alternarAtivo(f: Fornecedor): Promise<void> {
    if (f.ativo && !confirm(`Desativar ${f.nome}? Ele deixa de ser sugerido na revisão; os orçamentos antigos continuam.`)) return;
    try {
      await firstValueFrom(this.api.definirFornecedorAtivo(f.id, !f.ativo));
      this.avisos.toast(`${f.nome} ${f.ativo ? 'desativado' : 'reativado'}`);
      await this.carregar();
    } catch (e) {
      this.avisos.toast((await mensagensDeErro(e)).join(' '), '⚠');
    }
  }
}
