# Port Store

**Loja/baixador de ports de jogos para Android — Hail Games**

App Android nativo (Kotlin + Jetpack Compose) com motor de download em **Rust**,
interface inspirada na **Google Play Store** (Material 3, tema escuro com destaque
**amarelo-limão `#DFFF00`**) e **assistentes de configuração em etapas** com
**repositórios de dados** plugáveis (o padrão é o
[port-db](https://github.com/deivid22srk/port-db), do canal
[Hail Games](https://www.youtube.com/@Hail-Games1)).

- **Package:** `com.deivid22srk.portstore`
- **Repositório de dados padrão:** `https://github.com/deivid22srk/port-db`
  (catálogo: `data/games.json` · imagens: `assets/img/games/<id>/` resolvidas contra a raiz raw)
- **Motor de download:** Rust (`tokio` + `reqwest`/rustls-ring) compilado como `cdylib` e ponteado via **JNI**

---

## ⚖️ Aviso legal

O Port Store é **apenas uma vitrine e um gerenciador de downloads**. Nenhum jogo,
ROM, ISO ou APK é hospedado por este app — todos os links de download apontam para
os **repositórios oficiais dos autores** de cada port. Sem afiliação com SEGA, EA,
Microsoft, Valve, Nintendo ou qualquer outra empresa. Créditos e licenças de cada
port são exibidos na página do jogo. Ports que exigem arquivos originais do jogo
(dump) deixam isso indicado.

O aviso é exibido na primeira entrada no app ("Entendi") e fica sempre disponível em
**Você → Aviso legal**.

---

## 🚀 Setup inicial em etapas

Na **primeira abertura** o app entra em um assistente de duas etapas (navegação
só pelos botões, com indicador "Etapa X de 2"):

1. **Permissões** — cartões individuais com estado em tempo real:
   - **Notificações** (Android 13+): progresso dos downloads na barra de status;
     há a opção explícita **"Continuar sem notificações"**, e se a permissão for
     negada duas vezes aparece **"Abrir configurações do app"**
   - **Instalar apps desconhecidos** (opcional): necessário para instalar os APKs
   - **Ignorar otimização de bateria** (opcional): mantém downloads longos vivos
2. **Repositórios** — fontes de dados do catálogo, com o **Port DB (Hail Games)**
   pré-cadastrado e ativado. É possível **adicionar** (link do GitHub `dono/repo` ou
   do `games.json` direto, com botão **Verificar** que baixa e valida o JSON), **editar**,
   **reordenar** (prioridade no merge), **ativar/desativar** e **remover** (com confirmação).

Ao tocar em **Concluir**, abre a **tela de carregamento** (indicador circular
ondulado): *Buscando repositórios… → Baixando dados… → Processando jogos… →
Preparando imagens… → Tudo pronto!*, com progresso por repositório, tratamento de
falha parcial (segue com os demais) e total (**Tentar novamente / Voltar ao setup /
Continuar offline** com cache).

Nas **próximas aberturas** o app abre direto com o catálogo do cache e atualiza os
repositórios em segundo plano (ETag; pull-to-refresh também disponível).
Em **Você → Configurações** há **"Gerenciar repositórios"** e **"Refazer setup"**.

### Regras dos repositórios
- Aceita URL de **repo GitHub** (`https://github.com/dono/repo` → localiza
  `data/games.json` na branch padrão) ou **JSON direto**
- Apenas `https://`; limite de **10 MB**; timeout; campos desconhecidos ignorados;
  jogos sem `id`/`title` descartados; nada do JSON é executado
- Ids duplicados entre repositórios: **vence o de maior prioridade** (reordenável)
- Caminhos relativos de imagem são resolvidos com o `baseUrl` do repositório de origem

---

## 🧬 Versão dinâmica e detecção de instalação

- O catálogo **não traz mais a versão** — ela é obtida dinamicamente das **releases
  do GitHub do autor do port** (`links.github` → `releases/latest`), sob demanda,
  ao abrir a página do jogo
- **Cache no Room** (TTL 6 h) + **ETag** (304 não consome cota) + respeito a
  **403/429** (`X-RateLimit-Reset`; usa o cache e avisa, sem loops de retry)
- **Token do GitHub** opcional em *Você → Configurações* para aumentar o limite
  (guardado apenas no armazenamento privado do app)
- `packageName` do catálogo (string única; também aceita lista) permite detectar
  se o port **já está instalado** (`PackageManager` + `QUERY_ALL_PACKAGES` —
  necessário porque o app é distribuído fora da Play Store), exibindo selo
  **"Instalado"**/**"Atualização disponível"** nos cards, seção
  **"Meus jogos instalados"** e **"Atualizações"** em *Você*, e os botões
  **Instalar → Baixando → Instalar APK → Abrir/Atualizar**
- Reação automática a instalar/desinstalar/atualizar: broadcast de pacotes +
  re-verificação em `ON_RESUME`
- **Tamanho exibido** vem do asset real do release (ordenado por ABI do aparelho:
  arm64/universal primeiro); `links.download` é **apenas fallback** (GitHub releases,
  URL direta ou MediaFire via scraping)
- Comparação de versão tolerante (números, ignora prefixo `v`/sufixos); quando não
  dá para comparar com segurança, o botão vira Instalar/Abrir normalmente
- **WorkManager periódico (12 h)** verifica somente os jogos **instalados** e
  notifica atualizações no canal `updates` (importância padrão)
- Após baixar, o pacote do APK é conferido via `getPackageArchiveInfo`; divergência
  com o catálogo gera aviso na página do jogo

---

## ✨ Recursos

### Interface (estilo Play Store)
- **5 abas** com barra inferior Material 3: Início · Jogos · Pesquisa · Downloads · Você
- **Chips horizontais** de filtro (`Em alta`, `Novos`, categorias, Web)
- **Hero carrossel** 16:9 com banners dos destaques, selos (Novo/Em breve/Web) e auto-avance
- **Carrosséis** de capa 3:4: *Novos ports*, *Mais baixados*, *Leves para seu aparelho*, *Ports Web*
- Lista **"Sugestões para você"** com pílulas de desempenho e tamanho
- **Skeleton/shimmer** no carregamento, pull-to-refresh, imagens com Coil (cache memória+disco, suporte a **SVG**)
- **Página de detalhes**: autor linkado, infos (desempenho/tamanho/versão dinâmica/downloads,
  selo de instalado), galeria de screenshots + vídeo do YouTube, "Sobre este jogo" expansível,
  requisitos mínimo/recomendado, passo a passo de instalação, controles, créditos e links
- **Botão Instalar com máquina de estados** igual à Play Store: `Instalar` → `Baixando 42%`
  (barra de progresso própria em pílula, velocidade, ETA, pausar/cancelar) → `Instalar APK` →
  `Abrir`/`Atualizar` → `Tentar novamente`
- **Dropdown de variantes**: se o release tem vários APKs, abre bottom sheet para escolher
- **Pesquisa** com foco automático, buscas recentes (Room), termo destacado e filtros
- **Downloads**: progresso, velocidade (média móvel), ETA, bytes, ações por estado, limpar concluídos
- **Você**: biblioteca instalada, atualizações, tema, conexões paralelas, limite de
  velocidade, só no Wi-Fi, pasta de downloads (SAF opcional), repositórios, token do
  GitHub, pré-lançamentos, refazer setup, limpar cache, aviso legal

### Catálogo multi-repositório
- Download **paralelo** dos `games.json` de todos os repositórios ativos com **ETag**
- **Cache offline no Room** (`repo_cache`) — o app abre sem internet com o último catálogo
- Mesclagem por prioridade; base de imagens e de site por repositório
- Ports `type: "web"` → botão **"Jogar no navegador"** (link relativo resolvido contra o site do repo)

### Motor de download em Rust (`core/`)
- **Download segmentado multi-conexão** com segmentação dinâmica (worker ocioso
  "rouba" metade do maior intervalo em voo)
- **Fallback single-stream** quando o servidor não suporta Range
- **Retomada real**: estado persistido em `<arquivo>.state` (JSON atômico) —
  sobrevive a morte do processo e reboot
- **Pausar/retomar/cancelar** + fila com limite de simultâneos; **retry com backoff
  exponencial + jitter**, `Retry-After` respeitado
- **Detecção de rede**: pausa sem conexão e retoma automaticamente; opção "só no Wi-Fi"
- **Token bucket** global para limite de velocidade; velocidade/ETA por média móvel
- **Verificação**: tamanho, assinatura de APK/ZIP e **SHA-256** quando o release expõe `digest`
- **Escrita segura**: baixa em `.part` e **renomeia atomicamente** ao concluir
- Perfil release otimizado (`lto = fat`, `strip = true`) + **testes unitários** no CI

### Segundo plano
- **Foreground Service** (`dataSync`) com notificação persistente (progresso,
  Pausar/Retomar/Cancelar) e canal `downloads_done` para conclusão/falha
- `WakeLock` + `WifiLock` apenas com download ativo
- **WorkManager**: retomada após reboot + verificação periódica de atualizações (12 h)
- **Instalação**: `ACTION_VIEW` + FileProvider, guiando para "Permitir desta fonte"
- Estado do histórico/downloads em **Room**; configurações e setup em **DataStore**

---

## 🧱 Stack

| Camada | Tecnologia |
|---|---|
| UI | Kotlin 2.0, Jetpack Compose (BOM), Material 3, Navigation Compose |
| Dados | Room, DataStore, kotlinx.serialization, OkHttp, Coil (+svg) |
| Motor | Rust (tokio, reqwest/rustls-ring, sha2), JNI (`jni` crate 0.21), `cdylib` |
| Serviço | Foreground Service `dataSync`, WorkManager, notificações agrupadas |
| Build | AGP 8.7.3, Gradle 8.11.1, `cargo-ndk`, GitHub Actions |

ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64` · `minSdk 26` · `targetSdk 35`

```
port-store/
├── .github/workflows/build.yml   # CI: Rust (cargo-ndk) + APK release
├── app/                          # módulo Android (Kotlin/Compose)
│   └── src/main/java/com/deivid22srk/portstore/
│       ├── catalog/  # multi-repositório, parse, merge, cache
│       ├── setup/    # assistente em etapas + tela de carregamento
│       ├── install/  # InstalledAppsMonitor (PackageManager)
│       ├── github/   # ReleaseResolver (fallback) + VersionResolver (releases)
│       ├── core/ db/ settings/ service/ work/ installer/ ui/ util/
├── core/                         # crate Rust (motor de download)
└── gradle/libs.versions.toml
```

---

## 🔐 Permissões e privacidade

- `INTERNET` / `ACCESS_NETWORK_STATE` — baixar catálogo e APKs
- `POST_NOTIFICATIONS` (13+) — progresso dos downloads e avisos (opcional)
- `REQUEST_INSTALL_PACKAGES` + "fontes desconhecidas" — instalar os APKs baixados
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (opcional) — downloads longos em segundo plano
- `QUERY_ALL_PACKAGES` — **motivo**: o app é distribuído como APK (fora da Play
  Store) e precisa detectar quais ports do catálogo já estão instalados no
  aparelho, para exibir "Instalado/Atualização disponível" e permitir **Abrir**.
  Nada é enviado para fora do aparelho.
- `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` — serviço de download

---

## 🏗️ Build (GitHub Actions — nada é compilado localmente)

O workflow [`.github/workflows/build.yml`](.github/workflows/build.yml) roda em `ubuntu-latest`:

1. Checkout + JDK 17 (Temurin) + Android SDK + **NDK r27**
2. Rust stable com targets Android + caches
3. `cargo install cargo-ndk --locked`
4. `cargo test --release` (testes unitários do motor no host)
5. `cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ../app/src/main/jniLibs build --release`
6. **Keystore**: usa os secrets `KEYSTORE_B64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`
   se existirem; caso contrário **gera uma keystore efêmera no próprio CI**
7. `./gradlew assembleRelease` → APK renomeado `PortStore-<versão>-<sha>.apk`
8. Upload como **artifact** e, em `workflow_dispatch`/tags, publicação de **GitHub Release**

### Como usar
1. Abra a aba **Actions** → **Build APK** → **Run workflow** (ou faça push em `main`)
2. Ao terminar, baixe o APK em **Artifacts** (ou na Release, se acionado manualmente)
3. Instale permitindo "fontes desconhecidas" para o app

> **Nota sobre assinatura:** a keystore efêmera muda a cada build, então atualizações
> exigem desinstalar a versão anterior. Para assinatura estável, adicione os secrets
> `KEYSTORE_B64` (keystore `.jks` em base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`.

---

## ⚠️ Limitações conhecidas

- Portas com link **MediaFire** dependem de scraping do HTML — se a página mudar, o app
  oferece abrir no navegador como fallback
- Sem token, o limite da API do GitHub (~60 req/h) é contornado com cache/ETag e
  verificação sob demanda; jogar com muitos ports instalados pode demorar a mostrar
  todas as atualizações
- A keystore efêmera do CI impede atualização direta entre builds (documentado acima)
- `download` via **SAF**: o APK é baixado na pasta interna do app e copiado para a pasta
  escolhida ao concluir

## 📄 Licença

MIT — veja [LICENSE](LICENSE). O catálogo port-db e o projeto port-droid pertencem
ao canal Hail Games (deivid22srk); cada port mantém a licença do seu autor.
