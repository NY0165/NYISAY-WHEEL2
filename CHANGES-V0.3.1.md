# NYISAY Virtual Racing Wheel V0.3.1 — alterações sobre a V0.3 "corrigida"

## Erro que impedia a compilação
- `HidDeviceService.kt`: `BluetoothHidDevice.SUBCLASS1_GAMEPAD` não existe na API do Android.
  Trocado por `SUBCLASS2_GAMEPAD` (a lista oficial só tem `SUBCLASS2_GAMEPAD`).

## Bug de direcção corrigido (`SteeringSensor.kt`)
- Em paisagem o ângulo neutro dava cerca de ±180° e o filtro criava picos de volante a fundo ao cruzar o centro.
- Agora o vector de gravidade é passado para o referencial do ecrã (neutro = 0°), as duas componentes são
  filtradas antes do `atan2`, e rodar o telemóvel para a direita (sentido horário) dá direcção positiva.
- Se na prática ficar invertido, usa a opção "Inverter direcção".

## Ligação ao PC (antes nunca ligava)
- Novos botões: **Emparelhar** (torna o telemóvel visível 120 s) e **Escolher PC** (lista dos emparelhados).
- O PC escolhido fica guardado; a app liga sozinha (`connect`) e tenta de novo a cada 5 s se cair.
- Novo estado "A ligar ao PC…" (antes aparecia como "A registar").

## Não alterado (ainda por fazer)
- Acelerador e travão continuam 0% ou 100% (digitais).
- O painel de telemetria continua com campos editáveis à mão (não lê o jogo).
- CI (GitHub Actions) mantido: as versões checkout@v7, setup-java@v6, setup-android@v4 e upload-artifact@v7 existem.

## Verificação
- Feita só por análise estática (XML válido, recursos e IDs em falta = nenhum, delimitadores equilibrados).
- NÃO foi compilado neste ambiente (sem Android SDK nem rede). Se o CI mostrar um erro, envia o log.

## Como compilar
1. Envia a pasta para o GitHub e corre Actions > "Android Build V0.3" > Run workflow;
   o APK sai como artefacto. Ou, localmente: `cd android-app && ./gradlew assembleDebug`.

## Como ligar (primeira vez)
1. Abre a app (aceita as permissões Bluetooth).
2. Toca em **Emparelhar**. No Windows: Definições > Bluetooth e dispositivos > Adicionar dispositivo > Bluetooth > escolhe o telemóvel.
3. Na app toca em **Escolher PC** e escolhe o teu PC. O estado deve passar a "Ligado".
4. No Windows abre `joy.cpl` (Configurar controladores de jogo USB) e confirma que os eixos mexem.
