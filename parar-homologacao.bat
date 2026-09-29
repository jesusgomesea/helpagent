@echo off
setlocal
chcp 65001 >nul
rem ===========================================================================
rem  Encerra o HELP-AGENT de homologacao aberto por iniciar-homologacao.bat.
rem  Mesma tecnica do parar-helpagent.bat: pelos processos que ocupam as portas, e so se forem java.exe
rem  ou node.exe. As portas sao as da homologacao - a producao (80/8080) continua no ar.
rem ===========================================================================

rem Mesmas portas configuradas em iniciar-homologacao.bat.
set "PORTA_WEB=4201"
set "PORTA_API=8091"

call :encerrar %PORTA_API% java.exe
call :encerrar %PORTA_WEB% node.exe

echo HELP-AGENT de homologacao parado.
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
