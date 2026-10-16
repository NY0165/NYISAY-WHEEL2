# NYISAY Virtual Racing Wheel V0.3

Telemóvel Android como **comando de jogo Bluetooth HID** — sem instalar nada no PC.

```
Telemóvel (app Kotlin)  --- Bluetooth HID --->  Windows  --->  F1 2014
  sensor + pedais + botões
```

## O que há de novo vs V0.2

| | V0.2 | V0.3 |
|---|---|---|
| Servidor PC | Sim (C# + ViGEm) | **Não** |
| Protocolo | Pacotes próprios | Relatório HID padrão |
| Telemetria do jogo | Sim (UDP) | **Não** (exige servidor) |
| Telemetria local editável | — | **Sim** (km/h, marcha, RPM) |
| Instalação | APK + servidor + driver | **Só APK** + emparelhar |

## Conteúdo

```
android-app/          App Kotlin (HID)
  .../MainActivity.kt
  .../HidDeviceService.kt
  .../ReportBuilder.kt
  .../ControlState.kt
  .../SteeringSensor.kt
  .../Sender.kt
docs/                 Documento V0.3
.github/workflows/    Build CI
```

## Requisitos

- Android **9+** (API 28) — `BluetoothHidDevice`
- Acelerómetro / sensor de gravidade
- Bluetooth clássico
- PC Windows com Bluetooth (sem software extra)

> **Risco T1:** alguns fabricantes limitam o perfil HID Device. Se o registo falhar, a app mostra "Falha HID" e o plano B é voltar à V0.2.

## Instalação

1. Abre `android-app` no **Android Studio** → Sync → Run  
   (ou `gradle wrapper && ./gradlew assembleDebug`)
2. No telemóvel: permite Bluetooth quando pedido.
3. No **Windows**: Definições → Bluetooth → Adicionar dispositivo → escolhe o telemóvel.
4. Confirma em `joy.cpl` (Configurar controladores de jogo USB) que os eixos mexem.
5. No F1 2014: menu de controlos → atribui direcção, acelerador, travão e botões.

## Controlos

| Controlo | Como |
|----------|------|
| Direcção | Inclinar o telemóvel (paisagem) |
| Calibrar | Botão na barra superior (posição neutra) |
| Acelerador / Travão | Manter o dedo nas zonas grandes |
| Mudança + / − | Patilhas laterais |
| DRS / ERS / Pausa | Botões centrais |

## Painel de telemetria editável

Os campos **km/h**, **Marcha** e **RPM** são locais e editáveis — servem para diagnóstico visual e para treinar o olhar.  
Não vêm do jogo (isso exigiria um programa no PC, fora do âmbito V0.3).

## Diagnóstico / parâmetros editáveis

Botão **Diagnóstico** ou **Definições**:

- Ângulo máximo (°)
- Filtro passa-baixo
- Zona morta
- Curva (mais fina ao centro)
- Relatórios por segundo (10–100 Hz)
- Inverter direcção

Os valores ficam guardados no telemóvel.

## Relatório HID

| Campo | Bits | Intervalo | Repouso |
|-------|------|-----------|---------|
| Direcção (X) | 16 | 0–65535 | 32768 |
| Acelerador (Y) | 16 | 0–65535 | 0 |
| Travão (Z) | 16 | 0–65535 | 0 |
| Botões | 16 | 1 bit cada | 0 |

Botões: 1 = mudança +, 2 = mudança −, 3 = DRS, 4 = ERS, 5 = pausa.

Se alterares o descritor, **remove o dispositivo no Windows** e emparelha de novo (o Windows guarda o descritor em cache).

## Segurança

- Ao sair da app ou ir para segundo plano → relatório **neutro** (centro + pedais a 0).
- Reconexão começa sempre em neutro.
- Ecrã mantém-se ligado enquanto a app está aberta.

## Testes importantes

| ID | O quê |
|----|-------|
| **T1** | Registo HID aceite; telemóvel aparece no emparelhamento Windows |
| T2 | Eixos mexem em `joy.cpl` durante 5 min |
| T3 | F1 2014 atribui controlos |
| T4 | Sensor: sinais correctos, zero estável |
| T5 | Uma volta; atraso aceitável |
| T6 | Sair da app → comando neutro |

## Licença

Uso privado (autor + primo). Código como está, sem garantias.
