package br.com.rdamasio.helpagent.historico;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import br.com.rdamasio.helpagent.orcamento.ModoAquisicao;

/** Backup versão 2 foi feito quando "OPEX" significava o fluxo normal; hoje isso é REQUISICAO. */
class BackupModoTest {

    @Test
    void opexDeBackupAntigoViraRequisicao() {
        assertThat(BackupService.modoDoBackup(ModoAquisicao.OPEX, 2)).isEqualTo(ModoAquisicao.REQUISICAO);
        assertThat(BackupService.modoDoBackup(ModoAquisicao.CAPEX, 2)).isEqualTo(ModoAquisicao.CAPEX);
        assertThat(BackupService.modoDoBackup(null, 2)).isEqualTo(ModoAquisicao.REQUISICAO);
    }

    @Test
    void backupNovoPreservaOTipo() {
        assertThat(BackupService.modoDoBackup(ModoAquisicao.OPEX, 3)).isEqualTo(ModoAquisicao.OPEX);
        assertThat(BackupService.modoDoBackup(ModoAquisicao.REQUISICAO, 3)).isEqualTo(ModoAquisicao.REQUISICAO);
    }
}
