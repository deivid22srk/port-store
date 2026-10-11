# Instalação Virtual — Port Store

Modo opcional em que os jogos são instalados **dentro do próprio Port Store**
(ambiente virtual), sem aparecer na gaveta de apps do Android — experiência de
console: baixar, instalar, abrir, atualizar e desinstalar tudo pelo app.

---

## 1. Arquitetura

```
:app  ──usa──▶  :virtual  ──usa──▶  :Bcore (+ :black-reflection, :compiler)
                 (isolamento)         (motor vendored)
```

- **`:virtual`** — módulo Kotlin que isola o motor. O app só conhece:
  - `VirtualEngine` — interface (`install`, `launch`, `uninstall`, `clearData`,
    `stop`, `isInstalled`, `listApps`, `packageInfo`); trocar de motor no futuro
    = reimplementar só esta interface.
  - `VirtualManager` — fachada usada via `AppGraph.virtual`: `StateFlow` de
    jogos instalados, vínculo `gameId` (catálogo) ↔ pacote virtual, cálculo de
    espaço, instalação com verificações.
  - `VirtualBootstrap` — inicialização preguiçosa no `Application` (abaixo).
- **`:Bcore`** — núcleo do motor de virtualização (Java + nativo ndkBuild),
  que executa os apps virtualizados no processo do hospedeiro com hooks de
  Binder, sistema de arquivos redirecionado e componentes proxy declarados no
  manifesto (mesclados automaticamente pelo Manifest Merger).

### Inicialização preguiçosa

Para não custar memória/tempo a quem não usa o modo:

1. O usuário liga o modo → `SettingsRepository.setVirtualInstallEnabled` grava
   no **DataStore** (fonte da UI) **e** numa **flag espelho em
   SharedPreferences** (leitura síncrona e barata no boot) e, em seguida,
   `YouViewModel` tenta o **bootstrap tardio** (`VirtualBootstrap.ensureStarted`).
2. No boot do processo, `VirtualBootstrap.attachBaseContext` lê a flag:
   se desligada, **não inicializa nada** (fluxo 100% idêntico ao atual).
3. **Bootstrap tardio** — quem ligou o modo nesta sessão não precisa reiniciar:
   `VirtualManager.ensureEngine()` roda a mesma sequência do boot
   (attach + create) sob demanda, na primeira instalação/execução (ou na hora
   em que o switch é ligado). Protegido por `synchronized` e idempotente.
4. Se o bootstrap (normal ou tardio) falhar (ex.: Android incompatível), a
   exceção é engolida com log — o app continua normal e a UI informa que o
   motor não está pronto ("Reinicie o Port Store e tente de novo").

Desligar o modo **não apaga nada**: apenas interrompe o roteamento de novos
jogos para o ambiente virtual.

### Roteamento de instalação (onde o APK baixado é instalado)

Regra única: **com o modo ativo, o instalador do Android nunca é aberto
implicitamente** — o diálogo do sistema só aparece no botão explícito
"Instalar no Android".

| Ponto de entrada | Modo desligado | Modo ativo |
|---|---|---|
| Detalhe (normal e Launcher) — CTA principal | "Instalar APK" → Android | "Instalar (Virtual)" → `installDownloadedApk` → motor (com `ensureEngine`); se o motor não subir, aviso pede reinício |
| Detalhe (normal e Launcher) — alternativa | — | "Instalar no Android" → instalador do sistema |
| Downloads (normal e Launcher) — "Instalar APK" | instalador do sistema | motor (com `ensureEngine`) + Toast de resultado; sem motor, Toast pedindo reinício |
| Atualização do próprio Port Store | instalador do sistema (sempre — é o host) | instalador do sistema (sempre — é o host) |

`DetailViewModel.installApk` é Android puro **de propósito**: só é chamado
pelo botão explícito de alternativa (ou com o modo desligado).

## 2. Biblioteca escolhida

| Item | Valor |
|---|---|
| Motor | **Bcore** do [NewBlackbox](https://github.com/ALEX5402/NewBlackbox) |
| Licença | **Apache-2.0** (uso, modificação e distribuição com atribuição) |
| Commit fixado | `89b59836c66f173756a4ae258cf379a957649820` (2026-07-19, último na data da integração) |
| Módulos vendored | `Bcore/`, `black-reflection/`, `compiler/` (processor de annotations) |
| Requisitos declarados | Android 5.0–14+, arm64-v8a/armeabi-v7a, sem root |
| Página de 16 KB | `Application.mk` do motor já define `APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true` |

**Por que este e não outro:** Apache-2.0 permissiva (VirtualXposed e a linha
VirtualApp têm restrições de uso comercial/GPL — código **não foi copiado**),
repositório ativo (commit recente), suporte declarado a Android 14+ (o aparelho
de referência do projeto roda Android 14) e integração como módulo AAR/módulo
Gradle — exatamente o formato adotado aqui.

**Modificações locais** (registre em futuros upgrades):
- `Bcore/build.gradle`: compileSdk literal (35), minSdk 26, Java 21→17,
  NDK 29→27 (`27.0.12077973`), `lintOptions`→`lint` (DSL do AGP 8), comentários
  de proveniência.
- `Bcore/consumer-rules.pro`: adicionadas as regras do black-reflection
  (runtime `top.niunaijun.blackreflection.**`, keeps por anotação
  `@BClass`/`@BClassName`/`@BClassNameNotProcess` e
  `-keepattributes *Annotation*`) — no upstream essas regras vivem no
  **app de demo**; sem elas, o R8 do app final renomeia o runtime e as
  interfaces/proxies anotados e `BlackBoxCore.<clinit>` falha com
  "Not found @BlackClass or @BlackStrClass" (motor nunca fica pronto).
  `app/proguard-rules.pro` também espelha o conjunto.
- Nenhum arquivo Java/C++ do motor foi alterado.

**Atribuição** exigida pela Apache-2.0: ver `docs/THIRD_PARTY_NOTICES.md`
(copiado também para o `NOTICE` da raiz).

## 3. Segurança e recursos desativados

- **Instalação recursiva bloqueada** — nunca se instala o próprio Port Store
  dentro de si (`BlackboxVirtualEngine.install` confere o pacote do arquivo).
- **Integridade**: o download valida o SHA-256 no motor nativo (fluxo atual);
  a instalação virtual re-verifica o hash do catálogo quando disponível
  (`VirtualVerification.sha256`) e recusa arquivos corrompidos/trocados.
- **Arquitetura**: o APK é inspecionado (`lib/<abi>/` no zip) contra as ABIs do
  aparelho; incompatível → falha clara **antes** de instalar.
- **Somente catálogo**: não há entrada para instalar APK arbitrário escolhido
  pelo usuário — só o APK baixado pelo fluxo oficial do Port Store.
- **Desativado / não exposto** (conforme requisito):
  - falsificação de localização (o serviço de location do motor apenas
    repassa a localização real; nenhuma UI de fake location existe no app);
  - injeção de módulos Xposed (nenhum carregador exposto);
  - rede por VPN do motor (`isUseVpnNetwork() = false`);
  - daemon persistente (`isEnableDaemonService() = false`);
  - esconder root (`isHideRoot() = false`); anti-FlagSecure desligado.
- **Permissões mediadas**: as `uses-permission` extras que o motor traz no
  manifesto (câmera, microfone, localização etc.) existem para que o hospedeiro
  consiga **representar** as permissões que o jogo virtual pedir — o diálogo do
  sistema aparece para o usuário e nada é concedido silenciosamente.
- **Isolamento de falhas**: toda chamada ao motor é `runCatching`; exceção do
  motor nunca derruba o app. Falhas viram mensagens claras com alternativa de
  instalar normalmente no Android.

## 4. Fluxos do usuário

- **Ativar/desativar**: Você → "Instalação Virtual". Primeira ativação mostra
  aviso (o que muda, limitações, dados apagados se o Port Store for
  desinstalado). Ao desativar, escolhe **manter** ou **remover** os jogos
  virtuais (remoção só com confirmação explícita).
- **Instalar**: na tela do jogo, com o modo ativo, o APK baixado mostra
  **"Instalar (Virtual)"** (ou "Atualizar (Virtual)") com alternativa
  **"Instalar no Android"** sempre disponível. Tela de Downloads também
  respeita o modo. Split/XAPK/OBB: o catálogo hoje distribui APK único — a
  interface do motor já cobre APK único; múltiplos assets aparecem no diálogo
  de variantes (um por vez, igual ao fluxo atual).
- **Abrir**: botão "Jogar" abre dentro do ambiente virtual (tela cheia,
  orientação e áudio do próprio jogo; gamepad e toque funcionam pois a
  atividade roda no processo do hospedeiro com entrada do sistema intacta).
- **Atualizar**: baixa a nova versão e instala por cima — os dados/saves do
  ambiente virtual são preservados pelo motor na reinstalação.
- **Gerenciar**: Você → **"Jogos virtuais"**: espaço total, lista com capa,
  versão, tamanho e data, ações Abrir / Detalhes / **Limpar dados** (com
  confirmação) / **Desinstalar** (com confirmação).
- **Game Launcher**: jogos virtuais entram na linha da Home com selo
  **"Virtual"**, abrem via gamepad/toque como os demais, e a desinstalação do
  menu Y remove do ambiente virtual.

## 5. Matriz de compatibilidade (a validar no aparelho)

| Cenário | Estado esperado | Como testar |
|---|---|---|
| Android 14 (API 34), arm64 — Motorola edge 30 fusion | alvo principal | instalar APK da CI, ligar o modo, instalar/abrir 1 jogo |
| Android 10–13, arm64/armv7 | esperado funcionar (motor declara suporte) | repetir o teste acima em outro aparelho/emulador |
| Android 8–9 | provável, sem garantia | mesmo roteiro |
| Android 15/16+ | **pode quebrar** (APIs internas mudam) | motor deve falhar sem derrubar o app; reportar issue upstream |
| APK 32 bits em aparelho 64-only | bloqueado cedo (mensagem de arquitetura) | tentar instalar variante armeabi-v7a em aparelho sem suporte |
| Jogos com Play Integrity/licença | **esperado não funcionar** — não contornar | se o jogo fecha na abertura, instalar no Android |
| Page size 16 KB (Android 15+) | motor compilado com suporte flexível | instalar em emulador 16 KB e abrir jogo |

> ⚠️ Virtualização usa APIs internas do Android que mudam entre versões —
> quebra em versões novas é **esperada** e exige manutenção. O design isola o
> motor para que o resto do app nunca seja afetado.

## 6. Desempenho (como medir)

O CI não executa em aparelho; a medição abaixo deve ser feita no dispositivo
(mesmo jogo, instalação normal × virtual):

1. **Tempo de abertura**: `adb shell am start -W <pacote>` (normal) vs. abrir
   pelo Port Store com `adb logcat | grep -i display` (virtual).
2. **FPS**: Ativar "Perfil de GPU" nas opções de desenvolvedor ou usar o
   HUD de FPS do aparelho, jogando 3 min por modo.
3. **RAM**: `adb shell dumpsys meminfo <pacote>` (normal) vs.
   `adb shell dumpsys meminfo com.deivid22srk.portstore` (virtual — processo
   do hospedeiro inclui o jogo).

Relate os 3 jogos (leve/médio/pesado do catálogo) em issues para calibrar as
limitações da documentação.

## 7. Limitações conhecidas (também exibidas ao usuário)

- Jogos com verificação de integridade/licença (Play Integrity etc.) ou
  anti-adulteração podem não funcionar virtualizados — **não contornamos**;
  sugerimos instalação normal.
- Nem todo jogo é compatível; falhas são tratadas com mensagem e fallback.
- Os dados dos jogos virtuais vivem dentro do Port Store: **desinstalar o app
  apaga tudo** (aviso na ativação; backup de saves manual: os arquivos ficam em
  `Android/data/com.deivid22srk.portstore/files/blackbox` — exportável pelo
  gerenciador de arquivos).
- Distribuição em lojas oficiais (Google Play) pode restringir apps com
  virtualização — risco registrado; hoje o Port Store é distribuído por APK.
- Startup do app com o modo ativo inicializa o motor (custo moderado de
  memória) — quem não usa o modo não paga nada.
