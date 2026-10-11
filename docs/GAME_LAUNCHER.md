# Modo Game Launcher

O **Game Launcher** é um modo opcional do Port Store que transforma o app em uma
interface de console, inspirada na home do Nintendo Switch, com **controle total
por gamepad** e **suporte completo a touch** como alternativa de primeira classe.
Ele funciona como "um app dentro do app": pode ser ativado e desativado a
qualquer momento, sem perder downloads, jogos instalados ou preferências.

---

## 1. Ativar e desativar

- **Ativar**: aba **Você → "Modo Game Launcher"** (switch). Na próxima abertura
  (e imediatamente após ativar) o app abre direto no Launcher.
- **Desativar**: dentro do Launcher, em **Configurações** (ícone de engrenagem na
  barra inferior) ou no item **"Voltar ao Port Store"** da barra inferior. Também
  é possível sair com o botão **B** na Home (diálogo de confirmação).
- A preferência é persistida em **DataStore** (`game_launcher_enabled`) e
  restaurada na próxima abertura.
- Trocar de modo **não** perde dados: as duas interfaces compartilham as mesmas
  camadas de dados (catálogo, downloads, monitor de instalação, configurações).

## 2. Arquitetura

Todo o código do Launcher fica isolado no pacote
`com.deivid22srk.portstore.launcher` (nenhum arquivo da interface normal foi
movido; o comportamento do modo normal permanece idêntico):

```
launcher/
├── LauncherRoot.kt             # Raiz do modo: NavHost, tema escuro fixo, tela
│                               # cheia imersiva, monitor de gamepad, diálogo de saída
├── LauncherViewModel.kt        # ViewModel compartilhado (escopo da Activity):
│                               # jogos instalados, seções da loja, "jogados
│                               # recentemente", ações de download
├── LauncherHomeScreen.kt       # Home estilo Switch (linha de jogos instalados)
├── LauncherStoreScreen.kt      # Loja estilo eShop (banner + linhas por seção)
├── LauncherGameDetailScreen.kt # Detalhes do jogo (reaproveita DetailViewModel)
├── LauncherSearchScreen.kt     # Pesquisa com teclado virtual navegável
├── LauncherDownloadsScreen.kt  # Downloads com progresso e ações
├── LauncherSettingsScreen.kt   # Configurações do Launcher (sair, vibração, som)
├── LauncherComponents.kt       # Top bar (hora/bateria/conexão/perfil), barra
│                               # inferior circular, cards, banner, botões, linhas
├── LauncherDialogs.kt          # Menu de opções e confirmações (janela própria)
└── input/
    ├── LauncherInputRoot.kt    # launcherGamepadNav: D-pad/analógico com
    │                           # repetição e zona morta, B/X/Y/Start/L1/R1;
    │                           # LauncherInitialFocus
    ├── LauncherFocus.kt        # launcherFocusable: foco com destaque estilo
    │                           # Switch (escala + borda/brilho limão), A confirma,
    │                           # Y opções, toque e toque longo
    ├── GamepadMonitor.kt       # Conexão/desconexão de controles + último tipo
    │                           # de entrada (gamepad × toque) para as dicas
    └── LauncherFeedback.kt     # Vibração leve e beep de navegação (opcional)
```

### Reaproveitamento (sem duplicação)

| Camada | Fonte reutilizada |
|---|---|
| Catálogo/loja | `AppGraph.catalog` (`CatalogRepository`) |
| Downloads/instalação | `AppGraph.downloads` (`DownloadRepository`, motor Rust) |
| Estado de instalação | `AppGraph.installedApps` (`InstalledAppsMonitor`) |
| Versões/atualizações | `AppGraph.resolver`/`versions` via `DetailViewModel` |
| Instalação de APK | `ApkInstaller` |
| Imagens/cache | Coil (mesmo `ImageLoader` do app) |
| Tema/identidade | `LauncherTheme` (esquema escuro do `Theme.kt`, limão como destaque) |
| Preferências | `SettingsRepository` (DataStore `portstore_settings`) |

### Rotas do NavHost do Launcher

`launcher_home` → `launcher_store` → `launcher_game/{gameId}` → `launcher_search`
→ `launcher_downloads` → `launcher_settings`.

## 3. Mapeamento de botões

| Ação | Controle | Observações |
|---|---|---|
| Navegar | **D-pad** e **analógico esquerdo** | Repetição ao segurar (340 ms inicial, 140 ms intervalo); zona morta de 0.45 no analógico |
| Confirmar / Abrir | **A** (botão inferior) | Também Enter e D-pad central |
| Voltar / Cancelar | **B** (botão direito) | Na Home abre o diálogo "Sair do modo Launcher"; fecha menus |
| Opções do item | **Y** | Home: menu do jogo (Abrir / Informações / Desinstalar) |
| Pesquisar | **X** | Abre a pesquisa a partir de qualquer tela |
| Trocar de seção | **L1 / R1** | Cicla Home → Loja → Downloads → Configurações |
| Menu rápido | **Start** | Abre as Configurações do Launcher |

### Sistema de foco

- Sempre há **exatamente um elemento focado** por tela: cada tela pede foco
  inicial ao entrar (`LauncherInitialFocus` / lógica da Home).
- O item focado ganha **escala + borda/brilho em amarelo-limão** (estilo Switch)
  e a rolagem o mantém visível (`bringIntoView`).
- Ao voltar de uma tela, a Home **restaura o foco** no card de onde o usuário
  saiu (`homeFocusedGameId` no ViewModel).
- Diálogos e menus rodam em janela própria com navegação própria: o foco nunca
  escapa para o conteúdo atrás nem se perde ao abrir/fechar.
- Conexão/desconexão do controle em tempo real: aviso discreto
  ("Controle conectado/desconectado") e as dicas do rodapé alternam entre
  **botões do gamepad** e **dicas de toque** conforme a última entrada usada.
- Touch não quebra o foco: tocar um item o torna o elemento focado.

## 4. Funcionalidades

- **Home estilo Switch**: linha horizontal de jogos instalados (capas com o
  jogo focado maior), nome do jogo focado acima da linha, barra superior com
  hora/bateria/conexão/perfil, barra inferior circular (Loja, Downloads,
  Configurações, Voltar ao Port Store), dicas de botões no rodapé, estado vazio
  com atalho para a loja. Ordenação: **jogados recentemente** primeiro
  (registrado ao abrir um jogo; persistido no DataStore), depois alfabética.
- **Loja estilo eShop**: banner em destaque, seções **Em alta**, **Novos
  ports**, **Mais baixados** e por categoria; selo **Instalado/Atualização**
  nos cards; **detalhes do jogo** com capa, descrição, original, tamanho,
  botão principal (Baixar / Instalar / Abrir / Atualizar) conforme o estado,
  barra de progresso e escolha de variante do APK.
- **Pesquisa**: teclado virtual navegável por gamepad (A digita), buscas
  recentes, teclado físico também funciona.
- **Downloads**: progresso, velocidade/ETA, pausar/retomar, cancelar,
  instalar APK concluído e apagar arquivo.
- **Estados de erro**: loja offline (com "Tentar novamente"), jogo não
  encontrado/removido, falha de download (com "Tentar novamente").

## 5. Orientação e imersão

- Enquanto o modo está ativo, o aparelho fica **travado em paisagem**
  (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE` — aceita os dois lados, seguindo o
  sensor, como um portátil). Ao sair do modo, a rotação livre do Port Store é
  restaurada (`SCREEN_ORIENTATION_UNSPECIFIED`; o manifest não fixa
  orientation). A trava não recria a Activity (`configChanges` cobre
  orientation|screenSize).
- Layouts funcionam em **paisagem** (prioridade, estilo handheld) e retrato;
  a linha da Home encolhe os cards conforme a altura disponível.
- Tela cheia imersiva no Launcher (barras do sistema ocultas, swipe exibe
  temporariamente); as barras voltam ao sair do modo. Áreas seguras
  (`WindowInsets.safeDrawing`) respeitadas em todas as telas.

## 6. Como testar

1. **Modo normal intacto**: com o switch desligado, o app se comporta
   exatamente como antes (navegação, catálogo, downloads).
2. **Ativar o modo**: Você → "Modo Game Launcher". O app abre no Launcher;
   feche e reabra o app — continua no Launcher (persistência).
3. **Somente gamepad** (Xbox/PS/8BitDo/handheld Android, Bluetooth ou USB):
   - Navegar na Home com D-pad e analógico; segurar a direção para repetir.
   - **A** abre um jogo; **Y** abre o menu (Abrir/Informações/Desinstalar);
     toque longo equivale a Y.
   - **Loja**: navegar entre linhas e cards; abrir detalhes; baixar um port e
     acompanhar a barra de progresso; pausar/retomar/cancelar; instalar o APK.
   - **Pesquisa** com **X**: digitar com o teclado virtual (A) e filtrar.
   - **L1/R1** troca de seção; **Start** abre Configurações.
   - Desconectar o controle durante o uso: aviso discreto, sem crash; as
     dicas passam para modo toque.
4. **Somente touch**: tocar para abrir, deslizar para rolar, toque longo para
   opções, alvos ≥48dp, tocar fora fecha menus.
5. **Alternar modos**: sair pelo ícone "Voltar ao Port Store" (ou B na Home) —
   a interface normal abre intacta; reativar o switch volta ao Launcher. Os
   downloads em andamento continuam na fila nos dois modos.
6. **Foco**: voltar de uma tela de detalhes devolve o foco ao card de origem;
   abrir/fechar menus não perde o foco; rolagem acompanha o foco.

## 7. Limitações conhecidas

- **Vibração**: usamos o feedback do aparelho (`performHapticFeedback`); em
  controles Bluetooth dedicados a vibração do próprio gamepad dependeria de
  APIs específicas por dispositivo.
- **D-pads por eixo HAT**: alguns controles raros expõem o D-pad apenas como
  eixo `AXIS_HAT_X/Y` em MotionEvents; nesses casos use o analógico (ambos os
  caminhos funcionam nos controles mais comuns, que enviam KEYCODE_DPAD_*).
- "Jogados recentemente" é registrado pelo Port Store (ao abrir o jogo pelo
  Launcher); jogos abertos fora do app não atualizam a ordem.
- Sons de navegação usam beep curto do sistema (`ToneGenerator`), não um
  conjunto de samples customizados.

## 8. Próximos passos sugeridos

- Sons de navegação com SoundPool e samples próprios (navegar/confirmar/voltar).
- Perfis/avatars selecionáveis na barra superior.
- Linha "Jogados recentemente" separada na Home quando houver histórico.
- Suporte a D-pad via eixo HAT com detecção de dispositivo.
- Testes de UI (Compose Test) para a navegação por foco e máquina de estados
  do botão de instalação.
