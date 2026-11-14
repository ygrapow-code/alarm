# CoopAlarm

Alarme para a fila de atendimento. Consulta a URL JSON, pega o primeiro `senha` numerico
e toca alarme quando atual >= (carro - antecedencia) ou quando aparece o codigo geral.

## Gerar o APK
**GitHub (sem instalar nada):** crie um repositorio, envie esta pasta, va em Actions >
Build APK > Run workflow, e baixe `CoopAlarm-apk` (contem app-debug.apk).

**Android Studio:** File > Open nesta pasta, aguarde o sync, Build > Build APK(s).
Saida: app/build/outputs/apk/debug/app-debug.apk

## Uso
Abra o app, cole a URL (com a sua chave), ajuste os campos, Salvar, Iniciar.
Toque em "Isentar da otimizacao de bateria" e deixe o volume de alarme alto.
