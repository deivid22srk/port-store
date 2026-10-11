# Experiência de jogo no Game Launcher

Abertura, pausa e saída de um jogo com a sensação de console: tela de
carregamento animada, botão Home do gamepad com confirmação e menu rápido
lateral aberto por gesto. Trabalha em cima do modo **Game Launcher** (ver
`docs/GAME_LAUNCHER.md`, se existente) e do motor de **Instalação Virtual**
(`docs/VIRTUAL_INSTALL.md`).

## Visão geral dos recursos

| Recurso | O que faz | Onde vive |
|---|---|---|
| Tela de carregamento | Overlay estilo console ao abrir qualquer jogo pelo Launcher (virtual ou normal), com capa desfocada, logo animado e texto de estado | `app/.../launcher/GameExperienceController.kt` + `GameLoadingOverlay.kt` |
| Botão Home do gamepad | Dentro de um jogo **virtual**, abre o diálogo "Voltar ao início?" — o jogo fica suspenso em segundo plano com selo "Em execução" | `virtual/.../gex/GameOverlayHook.kt` + `GameOverlayUi.kt` |
| Menu rápido lateral | Duas deslizadas rápidas da esquerda para a direita (ou Home segurada) abre um drawer no visual do Launcher | `virtual/.../gex/DoubleSwipeDetector.kt` + `GameOverlayUi.kt` |

## Arquitetura

### 1. Ponte de entrada no motor (Bcore)

O ponto central é a ponte `top.niunaijun.blackbox.app.GameExperienceBridge`
(Java, no Bcore): o app hospedeiro registra um `Hook` nos processos de jogo
(`:p0..:p49`) e o delegate de Instrumentation (`BaseInstrumentationDelegate`)
passa a envolver o `Window.Callback` de cada activity do jogo com
`WindowCallbackDispatcher`.

- **Interceptação antes do jogo**: teclas, toques e eventos genéricos chegam
  ao hook ANTES da activity do jogo; tudo que o hook não consome é delegado
  pelo caminho original (mesma thread, mesma ordem — uma chamada de método no
  caminho, sem fila, sem thread extra).
- **Ciclo de vida**: `onGameResumed`/`onGamePaused` são notificados pelo
  delegate — base da reinicialização da UI do overlay e do gesto.
- O dispatcher só é instalado quando o hook existe (modo virtual ativo e
  processos de jogo), então o resto do app **não muda de comportamento**.

### 2. Overlays dentro do processo do jogo (Views clássicas)

O diálogo "Voltar ao início?" e o drawer são Views clássicas (sem Compose):
o processo do jogo não tem Compose montado e o custo de abrir/fechar é
desprezível. Visual: réplica do tema do Launcher em `GexStyle` (fundo
`#0F0F0F`/`#141414`, destaque `#DFFF00`, cantos 22/14 dp, texto `#EDEDED`).

- **Diálogo**: janela `Dialog` do próprio app (sem `SYSTEM_ALERT_WINDOW`).
  Enquanto está aberto, a janela do jogo perde o foco — nenhuma tecla/toque
  chega ao jogo.
- **Drawer**: view adicionada via `addContentView` (topo da hierarquia do
  jogo, sem janela nova). O hook consome TODAS as teclas enquanto o menu está
  aberto e as roteia para o menu (`D-pad` move, `A` aciona, `B` fecha); os
  toques vão para o scrim/itens (view de topo). O analógico é consumido para
  não vazar ao jogo. Ao fechar, o jogo volta a receber entrada normalmente.
- **Pausa de áudio**: o motor não expõe pausa de áudio confiável por processo
  (mutar `STREAM_MUSIC` é global ao aparelho e pode "grudar" se o processo
  morrer), então o áudio do jogo **continua** com o diálogo/menu aberto —
  como no menu Home de consoles. Documentado como limitação.

### 3. Tela de carregamento (app, Compose)

`GameExperienceController` é uma máquina de estados única
(`Idle → Loading → Idle/Error`) compartilhada por Home e detalhe do Launcher:

1. **Entrada**: `launch()` é chamado no toque/A no card ou botão "Abrir" —
   o overlay aparece imediatamente (fade 200 ms) ANTES de qualquer trabalho.
2. **Trabalho em segundo plano**: motor virtual (ensure + launchApk) ou
   `startActivity` do jogo normal.
3. **Prontidão real** (não é timer fixo):
   - **Virtuais**: poll de 200 ms via `ActivityManager.getRunningTasks` —
     retorno limitado às tarefas do PRÓPRIO app (sem permissão): activity do
     jogo no topo = jogo na frente. Extra: `onStop` do host (LauncherRoot)
     também marca "coberto".
   - **Normais**: o sinal principal é o `onStop` do MainActivity (o Android
     cobre o Launcher com o jogo).
4. **Tempos**: mínimo de **1,3 s** (evita flash; fase "Quase lá…" enquanto
   espera) e máximo de **15 s** (virtuais; primeiro boot do motor é lento) /
   **10 s** (normais). Estourou → tela de erro.
5. **Cancelamento**: B/Voltar cancela; se o jogo virtual já foi iniciado mas
   ainda não apareceu, o processo virtual é parado (`stopPackage`).
6. **Erro**: opções **Tentar novamente**, **Voltar ao Launcher** e, para
   virtuais, **Instalar normalmente no Android** — usa o APK base que o
   ambiente virtual já tem em disco (`BEnvironment.getBaseApkDir`), abrindo o
   instalador do Android via `ApkInstaller`.

Detalhes visuais: capa com blur (RenderEffect, API 31+; sem blur abaixo com
fallback de alpha), logo do Port Store (quadrado amarelo-limão + ícone de
download) com entrada fade/escala e pulso discreto, status rotativo
("Preparando ambiente…" → "Carregando jogo…" → "Quase lá…"), dica
`B Cancelar`. Tudo em alpha/escala (60 fps, sem realocação por quadro).

**Respeita "reduzir animações"**: com `ANIMATOR_DURATION_SCALE` ou
`TRANSITION_ANIMATION_SCALE` em 0, a tela fica estática (sem pulso, sem
crossfade) — `rememberReducedMotion()`.

**Orientação**: a orientação preferida da activity principal do jogo é lida
antes (`VirtualManager.launchScreenOrientation` — virtuais; `PackageManager`
— normais) e aplicada ao MainActivity ANTES do overlay, evitando rotação
visível no meio da animação; restaurada ao voltar.

### 4. "Voltar ao início" e suspensão do jogo

Ao confirmar, o hook traz a MainActivity para a frente com
`FLAG_ACTIVITY_REORDER_TO_FRONT` (mesma tarefa). O jogo:

- **não é fechado**: a activity vai a onStop e o processo `:pN` continua vivo
  (suspenso — o SDL/loop do jogo pausa com o ciclo de vida);
- o card na Home mostra o selo **"Em execução"** (destaque lime) enquanto o
  processo existir (`VirtualManager.refreshRunning` consulta o servidor do
  motor por IPC);
- abrir o jogo de novo no Launcher **retoma** a activity existente (mesma
  tarefa/componente), como "retomar de um console".

A opção **Encerrar jogo** (menu/diálogo, com confirmação) chama
`stopPackage` e volta ao Launcher — encerramento limpo.

### 4. Modo console — overlay dentro do jogo (in-window)

O console é uma **camada de overlay desenhada dentro da própria janela do
jogo** (`GameOverlayUi`): drawer lateral rápido, diálogo "Voltar ao
início?" e confirmação de encerrar. O jogo abre em tela cheia pelo fluxo
classico (`GameExperienceController`, com tela de carregamento) e o overlay
vive no processo `:pN`, instalado no `Application.onCreate`
(`GameOverlayInstaller` → `GameExperienceBridge.hook`), com o
`WindowCallbackDispatcher` (Bcore) entregando teclas/toques ao hook ANTES do
jogo.

Gatilhos do menu/diálogo:

- **Guide/Mode** (`KEYCODE_BUTTON_MODE`): toque curto = diálogo; segurado
  ~600 ms = menu;
- **Select + Start** segurados ~1 s = diálogo (cobre controles/aparelhos que
  capturam o Guide antes do app);
- **Gesto**: duas deslizadas rápidas da esquerda para a direita
  (`DoubleSwipeDetector`) — observadas sem atrasar o jogo; no disparo,
  `ACTION_CANCEL` para nada ficar "preso".

#### Por que não há embedding por VirtualDisplay

A tentativa de renderizar o jogo numa `VirtualDisplay` com a Surface de um
`SurfaceView` do Launcher foi **removida**: o Android 16 bloqueia o
lançamento de activities em displays virtuais criados por apps comuns. O log
de campo (`11_10-16-35-43_867.log`) mostra exatamente isso:

```
SecurityException: Permission Denial: starting Intent { cmp=...ProxyActivity$P1 }
  with launchDisplayId=10
  at com.android.server.wm.SafeActivityOptions.checkPermissions
```

O check (`ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay`, Android
16 r1) exige, para displays **não-trusted** (toda VirtualDisplay de app é):

1. `FLAG_ALLOW_EMBEDDED` na activity lançada — corrigível no manifest, mas
2. a permissão `ACTIVITY_EMBEDDING` (`signature|privileged`) **ou** o uid do
   chamador já presente na display (ActivityRecord) — impossível no primeiro
   lançamento (efeito "ovo e galinha");
3. ou ser o dono da display — insuficiente sozinho, os itens 1–2 vêm antes.

Rotas alternativas também fechadas (verificado no fonte AOSP Android 16):
display **trusted** exige `ADD_TRUSTED_DISPLAY` (`signature|role`);
redirecionar a janela com `Window.setWindowManager` não funciona
(`WindowManagerService.getDisplayContentOrCreate` dá prioridade ao **token**
da ActivityRecord, então a janela sempre nasce na display dela);
`moveRootTaskToDisplay` e `WindowContainerTransaction` exigem permissões
de sistema. O modo in-window não depende de nenhuma dessas portas e é o
comportamento oficial do console.

Ciclo de vida: "Voltar ao início" traz a MainActivity à frente (jogo fica
abaixo, suspenso) e o card mostra o selo **"Em execução"**; reabrir o jogo
retoma. "Encerrar jogo" para o processo (`stopPackage`) e volta ao Launcher.

## Mapeamento de botões (jogo virtual)

O hook (`GameOverlayHook`) intercepta ANTES do jogo: consome Home/Guide e as
teclas de menu/diálogo; Select+Start continuam indo ao jogo (a combinação
segurada abre o diálogo). A tabela abaixo vale para o jogo virtual em tela
cheia com o overlay in-window.

| Entrada | Ação |
|---|---|
| `KEYCODE_BUTTON_MODE` (Home/Guide do controle) — **toque curto** | Abre o diálogo "Voltar ao início?" |
| `KEYCODE_BUTTON_MODE` — **segurado ~600 ms** | Abre o menu rápido lateral |
| **Select + Start segurados ~1 s** | Abre o diálogo (alternativa quando o sistema captura o Guide) |
| Diálogo/menu: D-pad | Move o foco |
| Diálogo/menu: `A` (ou Enter / D-pad central) | Aciona o item focado |
| Diálogo/menu: `B` (ou Back) | Fecha (diálogo: continuar jogando) |
| Menu: deslizar para a esquerda / tocar fora | Fecha |
| Qualquer outra tecla/toque | Vai ao jogo normalmente |

Notas de compatibilidade:

- O botão **Home do sistema Android** não é entregue a apps — por isso o
  recurso usa `KEYCODE_BUTTON_MODE` (Guide/Mode), que é o que o gamepad
  realmente envia.
- Alguns aparelhos/controles capturam o Guide antes do app (ex.: alguns
  layouts de handhelds e certos controles Bluetooth em modo "teclado"); a
  combinação **Select+Start** cobre esses casos. Em controles Xbox o Guide
  costuma abrir a navegação do sistema quando conectado via Bluetooth no
  modo "sem mapeamento" — usar o modo "input device/gamepad". Controles
  8BitDo em modo Switch enviam `BUTTON_MODE`; em modo "keyboard" nenhum dos
  dois chega — trocar o modo do controle. Gamepads integrados de handhelds
  (Retroid, Odin etc.) normalmente entregam o Guide ao app.
- **Jogos instalados normalmente no Android** são apps separados: o Port
  Store NÃO intercepta teclas neles — o recurso vale apenas para jogos
  virtuais (limitação de arquitetura do Android, não do motor).

## Gesto do menu rápido (parâmetros)

Todos os parâmetros ficam em `DoubleSwipeDetector` (um único lugar):

| Parâmetro | Valor |
|---|---|
| Início da deslizada | Faixa esquerda da tela (primeiros **25%** da largura) |
| Distância mínima | **26%** da largura da tela ou **120 dp** (o maior) |
| Direção | Esquerda → direita, ângulo máximo de **25°** do eixo horizontal |
| Velocidade mínima | **900 dp/s** |
| Duração máxima da deslizada | **500 ms** |
| Intervalo entre as duas | **700 ms** (fim da 1ª → fim da 2ª) |

Comportamento:

- O detector **observa** os toques sem atrasá-los (o jogo recebe tudo em
  tempo real); só no disparo da 2ª deslizada os toques em andamento são
  cancelados com `ACTION_CANCEL` — nenhum toque "fantasma" no jogo.
- Jogos que usam gestos horizontais próprios podem conflitar: o switch
  **"Gesto do menu rápido"** (Configurações do Launcher e tela "Você",
  ligado por padrão) desliga o gesto por completo; o menu continua acessível
  segurando o botão Home/Guide.
- O espelho da preferência vive em SharedPreferences
  (`GameExperiencePrefs`) porque o processo do jogo não usa DataStore
  (multiprocesso).

## Configurações

| Opção | Padrão | Efeito |
|---|---|---|
| Gesto do menu rápido | Ligado | Duas deslizadas abrem o menu lateral nos jogos virtuais |
| Som e vibração de abertura | Desligado | Beep curto + vibração de 18 ms ao abrir um jogo pelo Launcher |

## Limitações documentadas

1. **Botão Home do sistema** não é interceptável (Android); usa-se o
   Guide/Mode do controle + combinação alternativa.
2. **Diálogo, menu e gesto funcionam apenas em jogos virtuais** (o hook vive
   nos processos `:pN` do motor).
3. **Gesto × jogos com gestos horizontais**: configurável (ver acima).
4. **Suspensão**: o jogo fica "suspenso" como activity em onStop — o
   processo continua vivo; se o Android matar o processo por memória, o jogo
   volta do zero ao reabrir (o selo "Em execução" desaparece, pois consulta
   processos vivos).
5. **Áudio**: não é pausado com diálogo/menu aberto (mutar o stream global
   seria pior); documentado em vez disso.
6. **Blur** da capa de carregamento: API 31+ (abaixo, só véu escuro).
7. **Analog do menu**: o analógico não navega no menu (D-pad sim) — o stick
   é consumido para não vazar ao jogo.

## Como testar

1. **Tela de carregamento**: com o modo Game Launcher ativo, abra um jogo
   virtual — a tela com o logo deve aparecer imediatamente e sair em fade
   quando o jogo estiver na frente. Abra também um jogo instalado
   normalmente (deve funcionar igual, saindo quando o Android cobre o
   Launcher). Toque/B durante o carregamento cancela (o jogo virtual que
   ainda não abriu é parado).
2. **Erro**: desinstale o jogo virtual pela tela de detalhe mantendo o APK
   (ou use um jogo com motor falho simulando reinício sem modo ativado) — o
   overlay deve mostrar o erro com Tentar novamente / Voltar ao Launcher /
   Instalar normalmente no Android.
3. **Botão Home**: dentro do jogo virtual, toque curto no Guide → diálogo
   (foco em "Continuar jogando"); confirmar volta ao Launcher e o card mostra
   "Em execução"; reabrir o jogo retoma; B no diálogo devolve ao jogo sem
   perder estado. Segurar o Guide ~600 ms abre o menu; Select+Start (1 s)
   abre o diálogo.
4. **Menu rápido**: duas deslizadas rápidas da borda esquerda → drawer;
   D-pad/A/B navegam; tocar fora fecha; "Encerrar jogo" pede confirmação e
   fecha o processo.
5. **Gesto desligado**: Configurações → Gesto do menu rápido OFF → o jogo
   recebe as deslizadas normalmente (nenhum menu pelo gesto; Home segurada
   ainda abre).
6. **Reduzir animações**: ative "Remover animações" nas opções do
   desenvolvedor → tela de carregamento estática (sem pulso).
7. **Regressões**: modo normal (sem Launcher) e Launcher com instalação
   normal — nenhuma mudança de comportamento; teclas do jogo chegam 1:1.
8. **Latência/FPS**: o dispatcher adiciona uma chamada de método por evento
   (ver `WindowCallbackDispatcher`) — comparar a jogabilidade antes/depois
   com o mesmo jogo (ex.: `dumpsys gfxinfo <pkg>` do jogo virtual e logs de
   tempo do SDL) não deve mostrar diferença acima do ruído.

## Arquivos

- `Bcore/src/main/java/top/niunaijun/blackbox/app/GameExperienceBridge.java`
- `Bcore/src/main/java/top/niunaijun/blackbox/app/WindowCallbackDispatcher.java`
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/delegate/BaseInstrumentationDelegate.java` (instalação do dispatcher + callbacks)
- `virtual/src/main/java/com/deivid22srk/portstore/virtual/gex/` (hook, UI, gesto, prefs, installer)
- `virtual/src/main/res/values/strings.xml` (textos dos overlays)
- `Bcore/src/main/java/top/niunaijun/blackbox/core/system/am/ActivityStack.java` (options preservadas na nova task)
- `app/src/main/java/com/deivid22srk/portstore/launcher/GameExperienceController.kt`
- `app/src/main/java/com/deivid22srk/portstore/launcher/GameLoadingOverlay.kt`
- `app/src/main/java/com/deivid22srk/portstore/launcher/` (Root, Home, Detail, Components, Settings — integração)
- `app/src/main/java/com/deivid22srk/portstore/PortStoreApp.kt` (registro nos processos :pN)
- `app/src/main/java/com/deivid22srk/portstore/settings/SettingsRepository.kt` (+2 preferências)
