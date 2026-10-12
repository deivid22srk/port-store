# Setup Inicial Adaptativo — decisões e extensão

Este documento resume as decisões de arquitetura do novo assistente de
configuração em 5 etapas (branch `feature/setup-adaptativo`) e mostra como
estender o fluxo no futuro.

## Visão geral

O setup passou de 2 para **5 etapas adaptativas**:

| # | Etapa | Conteúdo |
|---|-------|----------|
| 1 | Modo de uso | `UiMode.PHONE` ou `UiMode.GAMER_LAUNCHER` (nenhuma pré-selecionada) |
| 2 | Destino de instalação | `InstallTarget.VIRTUAL_ENVIRONMENT` ou `InstallTarget.DEVICE` |
| 3 | Permissões | Condicionais ao destino (ver abaixo) |
| 4 | Repositórios | Jogos (fontes do catálogo) + Dados Complementares (export/import local) |
| 5 | Conclusão | Resumo das escolhas + "Começar" (Loja ou Launcher) |

## Decisões principais

### 1. Um só `SetupViewModel` com estado único (`SetupUiState`)

MVVM + `StateFlow`: `uiMode`, `installTarget`, permissões, repositórios,
estado do backup de dados e `currentStep` vivem em um único data class
(`setup/SetupViewModel.kt`). A lógica **condicional** (o que muda conforme
modo/destino) foi extraída para `setup/SetupModels.kt` (`SetupFlowLogic`),
pura e testável em JVM — o ViewModel só orquestra I/O e persistência.

### 2. Persistência com espelhamento de flags (DataStore)

`UiMode` e `InstallTarget` são persistidos como strings no DataStore
(`ui_mode`, `install_target`). Como o restante do app já roteia com as flags
booleanas `game_launcher_enabled` e `virtual_install_enabled`, os setters
(`SettingsRepository.setUiMode/setInstallTarget`) **espelham** as duas
representações de forma atômica (e o `setVirtualInstallEnabled` continua
escrevendo o boot flag do motor virtual). Assim o `MainActivity` não mudou:
ao concluir, ele abre `LauncherRoot` ou `AppRoot` conforme a flag espelhada.

**Estado de retorno:** o DataStore é a fonte da verdade da etapa
(`setup_step`, agora 0..4) e das escolhas — cobre o papel do
`SavedStateHandle` com alcance maior (restaura também depois de fechar o
app). Voltar entre etapas nunca perde escolhas porque o estado lê das
preferências ao entrar e as escolhas são salvas no ato.

### 3. `SetupScaffold` — duas peles, um conteúdo

`setup/SetupScaffold.kt` define o contrato do layout com **duas
implementações**:

- `PhoneSetupScaffold` — retrato, cabeçalho segmentado, rodapé
  Voltar/Próximo (visual do assistente original);
- `GamerSetupScaffold` — paisagem imersiva, `LauncherTopBar` (hora/Wi-Fi/
  bateria), cards focáveis com borda/brilho amarelo-limão
  (`launcherFocusable`), navegação por controle (`launcherGamepadNav`:
  D-pad/analógico movem foco, **A** confirma, **B** volta, **Start**
  avança) e barra de dicas contextual.

O **conteúdo das etapas é reutilizável**: cada etapa é um composable puro
(`ModeStepContent`, `InstallTargetStepContent`, `PermissionsStepContent`,
`RepositoriesStepContent`, `SummaryStepContent`) que recebe `gamer: Boolean`
e adapta apenas o tratamento de foco/tamanho. O indicador "Etapa X de 5"
permanece nos dois layouts.

A orientação/imersão é controlada em `SetupFlow` por um `DisposableEffect`
chaveado no layout gamer (`SENSOR_LANDSCAPE` + system bars escondidas,
restaurando ao sair) — a etapa 1 é sempre retrato, pois o modo ainda não
foi escolhido.

### 4. Permissões condicionais

`SetupFlowLogic.visiblePermissions(installTarget)` decide as linhas:

| Permissão | Quando | Status |
|---|---|---|
| Notificações | sempre | Obrigatória (com "Continuar sem notificações") |
| Instalar apps desconhecidos | só `DEVICE` | Obrigatória |
| Ignorar otimização de bateria | sempre | Recomendada |
| Armazenamento | nunca (SAF) | — |

A etapa 4 usa **Storage Access Framework** (`CreateDocument`/`OpenDocument`),
que não exige permissão de armazenamento em runtime — por isso o card não
existe; no destino `VIRTUAL_ENVIRONMENT` a etapa explica por que a permissão
de instalar apps não aparece. O estado é reavaliado em `ON_RESUME` (voltar
das telas de sistema) e no callback dos launchers (Android 12+ não pausa a
Activity no diálogo de permissão).

### 5. Repositório de Dados Complementares (export/import local)

`setup/ComplementaryDataBackup.kt`:

- **Formato** JSON versionado: `schema = "portstore-complementary-data"`,
  `version = 1`, contagem declarada e itens (fontes do catálogo + cache dos
  seus `games.json`, que carregam capas/descrições/categorias).
- **Codec puro** (`ComplementaryDataCodec`) com validação tipada:
  `INVALID_FILE` (não é o schema/JSON quebrado), `INCOMPATIBLE_VERSION`
  (arquivo mais novo que o app), `CORRUPTED_FILE` (contagem divergente,
  itens sem id/nome), `INSUFFICIENT_SPACE` (verificação de espaço livre
  antes de gravar no Room) e `IO_ERROR`.
- **Fluxo**: exportar → `ACTION_CREATE_DOCUMENT` (nome sugerido); importar →
  `ACTION_OPEN_DOCUMENT` → resumo validado (nº de itens, versão, fontes) →
  confirmação **Mesclar** (upsert por id) ou **Substituir** (limpa e
  insere). Tudo local, sem permissões amplas.

### 6. Testabilidade

Gateways mínimos (`SetupSettingsGateway`, `SetupCatalogGateway`,
`ComplementaryDataBackupOps`) implementados pelas classes de produção
(`SettingsRepository`, `CatalogRepository`, `ComplementaryDataBackupManager`).
Os testes de JVM usam fakes dessas portas — sem Robolectric, sem Android:

- `SetupFlowLogicTest` — fluxo condicional por modo e destino;
- `SetupViewModelTest` — ViewModel com fakes (persistência, navegação,
  condicionais, conclusão);
- `ComplementaryDataCodecTest` — codec e validações;
- `SetupFlowUiTest` (androidTest, Compose) — etapas 1–3 no layout celular.

### 7. Re-execução

O setup reabre por `Você → Refazer configuração inicial` (já existia; agora
anuncia o escopo novo) e por **Configurações do Game Launcher → Refazer
configuração inicial** (novo): ambos persistem `setupStep = 0` e abrem a
sessão de setup — o `MainActivity` recompõe o assistente e o layout volta ao
retrato automaticamente.

## Como estender

1. **Nova etapa**: adicione a constante em `SetupFlowLogic` (ex.
   `STEP_NOVO = 5`), aumente `TOTAL_STEPS`, crie `NovoStepContent(state,
   gamer, ...)` e registre no `when(step)` do `SetupFlow` + regra do
   `canProceed`. O scaffold e o indicador se ajustam sozinhos.
2. **Etapa condicional** (aparece só em alguns caminhos): derive o número de
   etapas do estado (ex. `fun stepsFor(installTarget): Int`) e baseie a
   navegação em uma lista de etapas em vez de índices fixos — os callbacks
   `goToNextStep/goToPreviousStep` são os únicos pontos a mudar.
3. **Nova permissão**: adicione o id em `SetupPermissionId`, a linha em
   `visiblePermissions` e o card em `PermissionsStepContent`; o gating do
   "Próximo" entra em `canLeavePermissions`.
4. **Mudar o formato do export**: aumente `ComplementaryDataFormat.CURRENT_VERSION`,
   aceite até a versão nova no `decode` e mapeie campos antigos→novos.
5. **Nova persistência de escolha**: inclua o campo em `AppSettings` +
   chave no `SettingsRepository` e exponha via `SetupSettingsGateway` — os
   fakes dos testes já seguem a interface.

## Limitações conhecidas

- No layout gamer, diálogos (adicionar/editar repositório, confirmação de
  importação) usam a navegação padrão de diálogo do Android — D-pad funciona
  via busca de foco nativa, mas não há realce customizado dentro do diálogo.
- A verificação de espaço usa `StatFs` do armazenamento interno (onde o Room
  grava); provedores SAF remotos não expõem espaço livre, então falhas de
  escrita no destino caem em `IO_ERROR`.
