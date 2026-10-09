# Port Store

**Loja/baixador de ports de jogos para Android — Hail Games**

App Android nativo (Kotlin + Jetpack Compose) com motor de download em **Rust**,
interface inspirada na **Google Play Store** (Material 3, tema escuro com destaque
**amarelo-limão `#DFFF00`**) e catálogo dinâmico alimentado pelo projeto
[port-droid](https://github.com/deivid22srk/port-droid) (canal
[Hail Games](https://www.youtube.com/@Hail-Games1)).

- **Package:** `com.deivid22srk.portstore`
- **Catálogo:** `data/games.json` do repositório [port-droid](https://deivid22srk.github.io/port-droid/)
- **Motor de download:** Rust (`tokio` + `reqwest`/rustls-ring) compilado como `cdylib` e ponteado via **JNI**

---

## ⚖️ Aviso legal

O Port Store é **apenas uma vitrine e um gerenciador de downloads**. Nenhum jogo,
ROM, ISO ou APK é hospedado por este app — todos os links de download apontam para
os **repositórios oficiais dos autores** de cada port. Sem afiliação com SEGA, EA,
Microsoft, Valve, Nintendo ou qualquer outra empresa. Créditos e licenças de cada
port são exibidos na página do jogo. Ports que exigem arquivos originais do jogo
(dump) deixam isso indicado.

O aviso é exibido na primeira abertura ("Entendi") e fica sempre disponível em
**Você → Aviso legal**.

---

## ✨ Recursos

### Interface (estilo Play Store)
- **5 abas** com barra inferior Material 3 (pill no item ativo): Início · Jogos · Pesquisa · Downloads · Você
- **Chips horizontais** de filtro (`Em alta`, `Novos`, categorias, Web)
- **Hero carrossel** 16:9 com banners dos destaques, selos (Novo/Em breve/Web) e auto-avance
- **Carrosséis** de capa 3:4: *Novos ports*, *Mais baixados*, *Leves para seu aparelho*, *Ports Web*
- Lista **"Sugestões para você"** com pílulas de desempenho e tamanho (`Leve` `63 MB`)
- **Skeleton/shimmer** no carregamento, pull-to-refresh, imagens com Coil (cache memória+disco, suporte a **SVG**)
- **Página de detalhes**: autor linkado, infos (desempenho/tamanho/versão/downloads), galeria de
  screenshots + vídeo do YouTube, "Sobre este jogo" expansível, requisitos mínimo/recomendado,
  passo a passo de instalação, controles, créditos e links
- **Botão Instalar com máquina de estados** igual à Play Store: `Instalar` → `Baixando 42%`
  (com barra de progresso, velocidade, ETA, pausar/cancelar) → `Instalar APK` → `Tentar novamente`
- **Dropdown de variantes**: se o release tem vários APKs, abre bottom sheet para escolher
  (universal/arm64 sugeridos primeiro); menu ⋮ com repositório do autor e compartilhar
- **Pesquisa** com foco automático, buscas recentes (Room), termo destacado e filtros (Android/Web/Leve/Médio/Pesado)
- **Downloads**: progresso, velocidade (média móvel), ETA, bytes, ações por estado, limpar concluídos
- **Você**: tema (escuro/claro/sistema), conexões paralelas (1–16), simultâneos (1–6),
  limite de velocidade (0–100 Mbps), só no Wi-Fi, pasta de downloads (SAF opcional), limpar cache, aviso legal

### Catálogo
- Carregado de `https://raw.githubusercontent.com/deivid22srk/port-droid/main/data/games.json`
- **Cache offline** (arquivo local + `If-None-Match`/ETag) — abre sem internet com o último catálogo
- **Resolvedor de links**:
  - URL direta `.apk` → usada como está
  - `github.com/<dono>/<repo>/releases...` → consulta a **API do GitHub**, lista os assets `.apk`
    (com `digest` SHA-256 quando disponível) e trata rate limit 403/429 com mensagem amigável
  - Página do **MediaFire** → tenta extrair o link direto do HTML (fallback: abrir no navegador)
- Ports `type: "web"` → botão **"Jogar no navegador"** (link relativo `links.play` resolvido contra o site)
- `status: "removido"` ignorado; `em-breve` mostra botão desabilitado

### Motor de download em Rust (`core/`)
- **Download segmentado multi-conexão**: `HEAD`/`Range: bytes=0-0` para descobrir tamanho e
  `Accept-Ranges`; arquivo **pré-alocado** (`set_len`) com escrita posicionada (`pwrite`) por N workers
- **Segmentação dinâmica**: worker ocioso "rouba" metade do maior intervalo em voo
- **Fallback single-stream** quando o servidor não suporta Range (retomada linear)
- **Retomada real**: estado persistido em `<arquivo>.state` (JSON, escrito atomicamente a cada ~3s,
  em pausa e ao sair) com ETag/Last-Modified/pendências; sobrevive a morte do processo e reboot
- **Pausar/retomar/cancelar** por download + **fila** com limite de simultâneos (semáforo tokio)
- **Retry com backoff exponencial + jitter** (por segmento e por job), `Retry-After` respeitado,
  distinção entre erros recuperáveis (timeout/5xx/429) e fatais (404/403)
- **Detecção de rede**: pausa sem conexão e retoma automaticamente (Kotlin → `setNetworkState`),
  com opção "só no Wi-Fi"
- **Token bucket** global para limite de velocidade; velocidade/ETA por média móvel (janela ~6s)
- **Verificação**: tamanho final, assinatura de APK/ZIP (`PK\x03\x04`) e **SHA-256** quando o
  release do GitHub expõe `digest`
- **Escrita segura**: baixa em `.part` e **renomeia atomicamente** ao concluir; checa espaço livre
- **Eventos JNI** limitados a ~4/s (progresso) + eventos de estado; panics capturados no limite JNI
  e nos workers (`JoinError` → estado `Failed`)
- Perfil release otimizado: `opt-level 3`, `lto = fat`, `codegen-units = 1`, `strip = true`
- **Testes unitários** (`cargo test` roda no CI em host): divisão de segmentos, split/roubo,
  backoff+jitter, token bucket, parsing de `Content-Range`, roundtrip do estado, hex

### Segundo plano
- **Foreground Service** (`dataSync`) com `START_STICKY`, notificação persistente (canal `downloads`,
  importância baixa) com progresso, velocidade, ETA, **Pausar/Retomar/Cancelar** e notificação-resumo
  agrupada; canal separado `downloads_done` para conclusão/falha
- `WakeLock` parcial + `WifiLock` apenas com download ativo; serviço para sozinho quando a fila esvazia
- **WorkManager** retoma downloads pendentes após reboot (`RECEIVE_BOOT_COMPLETED`) ou rede de volta
- Android 13+: permissão `POST_NOTIFICATIONS` solicitada na primeira abertura (o serviço funciona mesmo sem ela)
- **Instalação**: `ACTION_VIEW` + FileProvider, guiando para "Permitir desta fonte" quando necessário
- Estado do histórico em **Room**; configurações em **DataStore**

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
│       ├── catalog/ github/ core/ db/ settings/ service/ work/ installer/ ui/ util/
├── core/                         # crate Rust (motor de download)
│   └── src/{lib,engine,download,segment,state,backoff,rate_limit,callback,jni_bridge,util}.rs
├── gradle/libs.versions.toml     # versões fixas
└── README.md
```

---

## 🏗️ Build (GitHub Actions — nada é compilado localmente)

O workflow [`.github/workflows/build.yml`](.github/workflows/build.yml) roda em `ubuntu-latest`:

1. Checkout + JDK 17 (Temurin) + Android SDK (`android-actions/setup-android@v3`) + **NDK r27**
2. Rust stable com targets Android + caches (`Swatinem/rust-cache`, `gradle/actions/setup-gradle`)
3. `cargo install cargo-ndk --locked`
4. `cargo test --release` (testes unitários do motor no host)
5. `cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ../app/src/main/jniLibs build --release`
6. **Keystore**: usa os secrets `KEYSTORE_B64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`
   (keystore em base64) se existirem; caso contrário **gera uma keystore efêmera no próprio CI**
   — o APK sempre sai instalável. Assine com sua própria keystore para receber atualizações
   sem desinstalar (veja nota abaixo).
7. `./gradlew assembleRelease` → APK renomeado `PortStore-<versão>-<sha>.apk`
8. Upload como **artifact** e, em `workflow_dispatch`/tags, publicação de **GitHub Release**

### Como usar
1. Abra a aba **Actions** → **Build APK** → **Run workflow** (ou faça push em `main`)
2. Ao terminar, baixe o APK em **Artifacts** (ou na Release, se acionado manualmente)
3. Instale permitindo "fontes desconhecidas" para o app

> **Nota sobre assinatura:** a keystore efêmera muda a cada build, então atualizações exigem
> desinstalar a versão anterior. Para assinatura estável, adicione os secrets
> `KEYSTORE_B64` (keystore `.jks` em base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`.

### Rodar o motor em desktop (debug)
```bash
cd core && cargo test    # testes unitários
```

---

## 📲 Como funciona o download (resumo técnico)

```
Kotlin (UI/Service) ──enqueue/pause/resume/cancel──▶ Engine (Rust, tokio)
        ▲                                                  │
        └── onProgress/onStateChanged (JNI, ≤4/s) ──────────┤
                                                           ▼
            N workers ──Range: bytes=a-b──▶ CDN (GitHub/MediaFire)
                │ pwrite(pos) no arquivo .part pré-alocado
                ▼
        <dest>.state (JSON: etag, pendências, bytes) → retomada após crash/reboot
                ▼
        Verificação (tamanho + PK\x03\x04 + SHA-256) → rename .part → .apk
```

---

## ⚠️ Limitações conhecidas

- Portas com link **MediaFire** dependem de scraping do HTML — se a página mudar, o app
  oferece abrir no navegador como fallback
- Rate limit da API do GitHub (60 req/h sem token) tem tratamento amigável, mas sem token
- `Abrir/Atualizar` não detecta se o jogo do port já está instalado (cada port tem um
  package próprio não informado no catálogo)
- A keystore efêmera do CI impede atualização direta entre builds (documentado acima)
- `download` via **SAF**: o APK é baixado na pasta interna do app e copiado para a pasta
  escolhida ao concluir (evita complexidade no motor Rust)

## 📄 Licença

MIT — veja [LICENSE](LICENSE). O catálogo e o projeto port-droid pertencem
ao canal Hail Games (deivid22srk); cada port mantém a licença do seu autor.
