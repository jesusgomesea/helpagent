@echo off
REM Cotacao Assistida - sobe o servidor e abre o navegador.
REM A janela precisa ficar aberta: fechar encerra a aplicacao.

cd /d "%~dp0"
title Cotacao Assistida - nao feche esta janela

python -X utf8 app.py %*

if errorlevel 1 (
    echo.
    echo  A aplicacao encerrou com erro.
    echo  Se a mensagem acima fala em porta em uso, ja ha um servidor rodando.
    echo  Para diagnostico, veja HANDOFF.md secao 8.
    echo.
    pause
)
