@echo off
setlocal
chcp 65001 >nul
rem ===========================================================================
rem  Encerra o HELP-AGENT aberto por iniciar-helpagent.bat.
rem  Encerra pelos processos que ocupam as portas do sistema (8080 = backend, PORTA_WEB = frontend),
rem  e nao pelo titulo da janela: no Windows Terminal o titulo pertence ao terminal, nao ao cmd,
rem  e o filtro por titulo nao encontra nada. So encerra se o processo for java.exe ou node.exe,
rem  para nunca derrubar outro servico que esteja usando a mesma porta.
rem ===========================================================================

rem Mesma porta configurada em iniciar-helpagent.bat.
set "PORTA_WEB=80"

call :encerrar 8080 java.exe
call :encerrar %PORTA_WEB% node.exe

echo HELP-AGENT parado.
timeout /t 3 /nobreak >nul
endlocal
exit /b 0

rem --- :encerrar <porta> <executavel esperado> ---------------------------------
:encerrar
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /R /C:":%1 .*LISTENING"') do (
  tasklist /FI "PID eq %%p" /FO CSV /NH | findstr /I /C:"%2" >nul && (
    taskkill /PID %%p /T /F >nul 2>&1
    echo Encerrado %2 ^(PID %%p^) da porta %1.
  )
)
exit /b 0
