# NYISAY Virtual Racing Wheel — Documento de Projecto V0.3

| Campo | Valor |
|---|---|
| Versão do documento | 0.3 (substitui a 0.2; sem servidor no PC) |
| Data | 16 de Outubro de 2026 |
| Utilizadores | 2 (autor e primo), uso privado |
| Jogo alvo | F1 2014 (Windows) |
| Ligação | Bluetooth Clássico, perfil HID, directa telemóvel para PC |
| Plataforma | Android 9 ou superior (Kotlin). Nada é instalado no PC. |

---

## 1. Resumo

O telemóvel apresenta-se ao Windows como um comando de jogo Bluetooth (perfil HID). O PC emparelha-o como qualquer comando e o F1 2014 vê um dispositivo de jogo. Não existe servidor, nem controlador virtual, nem biblioteca Bluetooth no PC.

```
Telemóvel (app Kotlin) --- Bluetooth HID ---> Windows (comando de jogo) ---> F1 2014
 sensor + botões
```

A direcção é feita por inclinação, lida no acelerómetro (o giroscópio não é exigido).

## 2. Consequências de não haver servidor

| Item da V0.2 | Estado na V0.3 |
|---|---|
| Programa servidor no PC (C#) | Removido |
| ViGEmBus / vJoy / teclado | Removidos; o Windows já reconhece o dispositivo HID |
| Protocolo próprio (pacotes, soma, sequência) | Substituído pelo relatório HID (secção 6) |
| Biblioteca Bluetooth no PC | Removida |
| Telemetria do jogo no telemóvel | **Fora do âmbito**: exige um programa no PC a ler o UDP do jogo. Ver secção 3. |
| Watchdog no servidor | Passa para o telemóvel e para a deteção de desligação do Windows (secção 9) |

Ganho: instalação para o primo = um APK e emparelhar no Windows.
Perda: sem telemetria do jogo, e o comportamento depende do telemóvel suportar o perfil HID (risco R1).

## 3. Âmbito

Dentro:
- Direcção analógica por inclinação.
- Acelerador e travão por toque (manter premido), com multitoque.
- Mudança +, mudança -, DRS, ERS, pausa.
- Painel de diagnóstico no telemóvel (ângulo, valor enviado, estado da ligação, relatórios por segundo).

Fora:
- Telemetria do jogo no telemóvel. Só seria possível com um pequeno programa no PC; fica como extensão opcional (V0.5), contrária à regra "sem servidor".
- Wi-Fi, USB, vários telefones, iOS, force feedback.

## 4. Requisitos

| ID | Requisito |
|---|---|
| RF1 | A direcção varia de forma contínua com o ângulo de inclinação. |
| RF2 | O utilizador calibra o zero e o ângulo máximo. |
| RF3 | Acelerador e travão funcionam enquanto o dedo estiver no ecrã, em simultâneo com a direcção. |
| RF4 | Botões chegam ao jogo como premir e soltar. |
| RF5 | O telemóvel mostra o estado da ligação e liga ao PC emparelhado sem passos manuais. |
| RF6 | Ao sair da app ou perder a ligação, o comando fica em posição neutra. |
| RNF1 | Nenhuma operação Bluetooth na thread do ecrã. |
| RNF2 | Atraso aceitável definido pelo teste T5. |

## 5. Arquitectura (só no telemóvel)

| Módulo | Responsabilidade |
|---|---|
| MainActivity | Ecrã em paisagem, ciclo de vida, ecrã sempre ligado, estado. |
| HidDeviceService | Regista a app como dispositivo HID (gamepad/joystick), gere emparelhamento, ligação e reconexão ao PC. |
| ReportBuilder | Converte o estado actual no relatório HID (secção 6). |
| ControlState | Estado mais recente: direcção, pedais, botões. Escrita pelo ecrã e sensor, leitura pelo emissor. |
| SteeringSensor | Lê o sensor, calcula o ângulo, filtra, calibra, devolve a direcção. |
| Sender | Uma só thread; envia o relatório de forma periódica e quando há alteração. |

Estrutura de pastas:

```
NYISAY-Virtual-Racing-Wheel/
  docs/
  android-app/app/src/main/java/mz/nyisay/virtualracingwheel/
    MainActivity.kt
    HidDeviceService.kt
    ReportBuilder.kt
    ControlState.kt
    SteeringSensor.kt
    Sender.kt
  .github/workflows/android-build.yml
```

## 6. Relatório HID

O descritor define o aspecto do dispositivo para o Windows. Proposta inicial (valores de 16 bits em little-endian):

| Campo | Bits | Intervalo | Repouso |
|---|---|---|---|
| Direcção (eixo X) | 16 | 0 a 65535 | 32768 (centro) |
| Acelerador (eixo Y) | 16 | 0 a 65535 | 0 |
| Travão (eixo Z) | 16 | 0 a 65535 | 0 |
| Botões | 16 | 1 bit cada | 0 |

Botões: 1 mudança +, 2 mudança -, 3 DRS, 4 ERS, 5 pausa. Os restantes ficam reservados.

Notas:
- Acelerador e travão em eixos separados permitem usar os dois ao mesmo tempo.
- O mapeamento exacto de eixos e botões faz-se no menu de controlos do F1 2014 (teste T3). Se o jogo esperar outro eixo para os pedais, altera-se o descritor.
- O Windows costuma guardar em cache o descritor na primeira ligação. Depois de alterar o descritor, remove o dispositivo no Windows e emparelha de novo.

## 7. Direcção por inclinação (acelerómetro)

1. **Eixo certo.** Em paisagem, rodar como um volante desloca a gravidade entre os dois eixos do plano do ecrã. O ângulo calcula-se a partir dos dois componentes, não de um só eixo.
2. **Orientação.** Paisagem nos dois sentidos (`sensorLandscape`); rodar o telemóvel 180° inverte o sinal e a app compensa.
3. **Filtro.** Passa-baixo para retirar tremor e acelerações lineares das mãos; valor configurável.
4. **Calibração.** Botão "Calibrar" guarda a posição actual como zero.
5. **Curva.** Ângulo máximo configurável (início a testar: 45°), uma única zona morta pequena, curva mais fina ao centro opcional.
6. **Opcional (V0.4).** Se o aparelho tiver giroscópio, usar o vector de rotação para suavizar; nunca é exigido.

## 8. Emparelhamento e ligação

Primeira vez:
1. Abrir a app; ela regista-se como dispositivo HID e fica visível para emparelhamento.
2. No Windows: Definições, Bluetooth e dispositivos, Adicionar dispositivo, Bluetooth; escolher o telemóvel.
3. Confirmar o código nos dois lados.
4. Confirmar no Windows (Configurar controladores de jogo USB, `joy.cpl`) que o dispositivo aparece e que os eixos mexem.

Seguintes:
- A app liga ao PC emparelhado ao abrir e tenta de novo, com intervalo crescente, se cair.
- O estado ("a procurar", "ligado", "desligado") fica sempre visível no ecrã.

## 9. Segurança de jogo

| Situação | Comportamento |
|---|---|
| App sai do primeiro plano ou o ecrã apaga | A app envia um relatório neutro (direcção ao centro, pedais a 0) antes de parar. |
| Ligação cai de repente | A app não consegue avisar. O comportamento do jogo é verificado no teste T6 e documentado. |
| Reconexão | Nova ligação começa sempre com relatório neutro até o primeiro estado válido. |
| Ecrã | Ecrã sempre ligado enquanto a app está aberta. |

## 10. Ecrã do telemóvel (paisagem)

- Barra superior: estado da ligação, Calibrar, Diagnóstico.
- Canto inferior esquerdo: travão (zona grande).
- Canto inferior direito: acelerador (zona grande).
- Laterais superiores: mudança - e mudança + como patilhas.
- Junto ao centro: DRS, ERS, pausa.
- Todos os botões respondem a premir e soltar; multitoque activo.
- Diagnóstico: ângulo, direcção enviada, relatórios por segundo.

## 11. Permissões e compatibilidade

| Android | Requisitos |
|---|---|
| 9 a 11 | Permissões `BLUETOOTH` e `BLUETOOTH_ADMIN` no manifesto. |
| 12 ou superior | `BLUETOOTH_CONNECT` com pedido em execução; tratar o resultado. |

`BluetoothHidDevice` existe desde o Android 9. A documentação do Android lista as constantes de subclasse gamepad e joystick usadas aqui. Se o telemóvel suporta o perfil na prática depende do fabricante, e é testado em T1.

## 12. Plano de testes

| ID | Teste | Critério |
|---|---|---|
| T0 | (Opcional) Monect no F1 2014, como referência de atraso | Anotar o atraso sentido. |
| T1 | App mínima regista o perfil HID | O registo é aceite e o telemóvel aparece no emparelhamento do Windows. É o teste mais importante: se falhar, ver plano B. |
| T2 | Ligação estável com eixos em movimento automático | Em `joy.cpl` os eixos e botões mexem; 5 minutos sem queda. |
| T3 | F1 2014 reconhece o dispositivo | Menu de controlos atribui direcção, acelerador, travão e botões. |
| T4 | Sensor | Esquerda e direita com sinais certos, zero estável após calibrar, sem saturação. |
| T5 | Jogo real | Uma volta completa; atraso aceitável em comparação com T0. |
| T6 | Falhas | Desligar o Bluetooth do telemóvel a meio da recta: registar o que o jogo faz; sair da app: comando fica neutro. |

## 13. Riscos

| ID | Risco | Mitigação |
|---|---|---|
| R1 | O fabricante do telemóvel não suporta o perfil HID Device, ou o registo falha | T1 decide cedo. Plano B abaixo. |
| R2 | Windows vê o dispositivo, mas o F1 2014 não o aceita (um dispositivo HID genérico aparece como DirectInput, sem XInput) | T3. Ajustar descritor; se o jogo recusar, plano B. |
| R3 | Atraso maior do que o aceitável | Medir no T5; ajustar frequência de envio e definições de QoS do registo. |
| R4 | Ligação cai e o jogo mantém o último valor | T6. Se acontecer, não há solução só no telemóvel; entra o plano B. |
| R5 | O Windows guarda o descritor antigo | Remover o dispositivo e emparelhar de novo. |
| R6 | O PC não tem Bluetooth | Adaptador USB; o desempenho varia com o modelo. |

**Plano B (reintroduz o servidor):** ligação Bluetooth RFCOMM com um programa no PC e comando virtual, como descrito na V0.2. Só se T1, T3 ou T6 falharem de forma que não se corrija.

## 14. Roteiro

| Versão | Conteúdo |
|---|---|
| V0.3 | Perfil HID, direcção por inclinação, pedais por toque, botões, estado da ligação, relatório neutro ao sair. |
| V0.4 | Curva de direcção ajustável no ecrã, giroscópio opcional, ecrã de definições. |
| V0.5 | Opcional: programa mínimo no PC só para telemetria (exige abandonar a regra "sem servidor"). |

## 15. Fontes consultadas

- Documentação Android, classe `BluetoothHidDevice` (registerApp, subclasses gamepad e joystick): developer.android.com/reference/android/bluetooth/BluetoothHidDevice.
- Exemplo de joystick HID em Android com `BluetoothHidDevice`: jung-max.github.io (2019), "Android로 Bluetooth HID 장치(Joystick) 만들기".
- Monect PC Remote, para referência de funcionalidades: xda-developers.com e nerdschalk.com.
- O suporte do F1 2014 a dispositivos DirectInput genéricos e o suporte do perfil HID Device no teu modelo de telemóvel não foram confirmados por fonte: são os testes T1 e T3.
