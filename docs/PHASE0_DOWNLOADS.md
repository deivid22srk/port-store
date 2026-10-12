# Fase 0 — Reescrita do sistema de downloads (referência: Download Navi)

> Relatório de análise prévia à implementação. Tudo o que está aqui foi
> verificado lendo o código-fonte; o que não pôde ser verificado está marcado
> explicitamente. Repositório de referência clonado em análise:
> `royt93/4_download-navi` (commit `c026d35`), fork do
> `TachibanaGeneralLaboratories/download-navi`.

## 0. Nota de licença (obrigatória)

O Download Navi é **GPLv3**. O Port Store é MIT (`core/Cargo.toml`, `LICENSE`).
MIT **não** é compatível com GPLv3 no sentido de incorporar código GPL em
distribuição MIT. Por isso esta tarefa usa o Download Navi **apenas como
referência de arquitetura, fluxos e regras de negócio** (o que a GPL permite —
idéias não são licenciáveis) e **nenhum trecho de Kotlin/Java foi copiado**.
A reimplementação em Rust é autoral. Esta nota permanece no repositório como
registro da decisão.

> **ATUALIZAÇÃO 2026-10-13 — resolvedor de provedor removido.** O caminho
> `buzzheavier.rs` + `provider_download.rs` + `nativeResolveBuzzheavier`/
> `nativeDownloadProviderFile` foi REMOVIDO: resolver link de um provedor
> específico em código acoplava o app a um host e continuava sendo desafiado
> por Cloudflare. O fluxo v2 agora é sempre: WebView de captura
> (`GameDataCaptureDialog`) → usuário clica no botão de download da página →
> `DownloadListener` captura URL final + cookies + UA → `nativeEnqueueCaptured`
> (`webview_bridge.rs`) → motor Rust com a sessão completa (multipartes,
> retomada, retry). Redirecionamentos para outros hosts pedem confirmação
> explícita do usuário mostrando a URL. As seções seguintes descrevem o
> estado anterior e permanecem como registro histórico.

---

## 1. Mapeamento do Download Navi (verificado no código-fonte)

Pacote raiz: `com.tachibana.downloader` (`app/src/main/java/...`).

### 1.1 Motor, scheduler e threads

- `core/model/DownloadEngine.java` — singleton com fila `DownloadQueue`
  (`ArrayDeque<UUID>`) e mapa `activeDownloads: HashMap<UUID, DownloadThread>`.
- Agendamento em **três camadas**: WorkManager (`core/model/DownloadScheduler.java`)
  aplica constraints (rede/roaming/carga/bateria) e re-agenda pós-reboot; o
  worker delega a um **foreground service** (`service/DownloadService.java`,
  `START_STICKY`) porque WorkManager tem limite de 10 min; o engine roda cada
  tarefa em RxJava `Schedulers.io()`.
- Simultaneidade: `maxActiveDownloads` (default **3**,
  `core/settings/SettingsRepositoryImpl.java:83`).
- Cada download = 1 `DownloadThreadImpl` com um `ExecutorService` próprio
  (`newFixedThreadPool(numPieces)`), rodando N `PieceThreadImpl` (Threads)
  via `invokeAll`.

### 1.2 Partes, Range, retomada e servidores sem Range

- Entidade `DownloadPiece` (`core/model/data/entity/DownloadPiece.java`):
  `pieceIndex`, `size`, `curBytes`, `statusCode` — **Room**, FK CASCADE.
- `MIN_PIECES=1`, `MAX_PIECES=16` (`DownloadInfo.java:50-52`); divisão
  igualitária em `makePieces()`; sem `Content-Length` → 1 peça.
- `PieceThreadImpl.addRequestHeaders` (306-340): `Range: bytes=<cur>[-<end>]`,
  `Accept-Encoding: identity` (derrota gzip p/ permitir retomada),
  `Connection: close`, e **`If-Match: <ETag>`** ao resumir.
- Retomada: `piece.curBytes` só é persistido **depois de `fd.sync()`** —
  o par (dados em disco, offset no banco) nunca diverge; escrita posicionada
  via `Os.lseek` (`SysCallImpl`). Progresso gravado com throttle
  64 KiB / 2 s. **Não há arquivo `.part` nem merge**: todas as peças escrevem
  no arquivo final em offsets distintos; pré-alocação opcional
  `posix_fallocate`.
- Servidor sem Range: flag `partialSupport` (de `Accept-Ranges`/`Content-Range`)
  força 1 peça e reinício do zero; 200 em resposta a retomada →
  `STATUS_CANNOT_RESUME`.
- Bônus: re-request com `Range: bytes=0-` quando há `Transfer-Encoding: chunked`
  sem `Content-Length`, para descobrir tamanho via `Content-Range`
  (`core/HttpConnection.java:130-173`).

### 1.3 Modelo de dados e máquina de estados

- Room (`core/storage/AppDatabase.java`, v6): `DownloadInfo` (URL, dirPath SAF,
  fileName, mime, totalBytes, numPieces, statusCode, retry, partialSupport,
  userAgent, numFailed, retryAfter, lastModify, checksum, uncompressArchive...),
  `DownloadPiece`, `Header` (**ETag/Referer persistidos por download**),
  `UserAgent`, `BrowserBookmark`.
- Estados (`core/model/data/StatusCode.java`, inspirado no DownloadManager do
  Android): PENDING=190, RUNNING=192, FETCH_METADATA=193, WAITING_TO_RETRY=194,
  WAITING_FOR_NETWORK=195, PAUSED=197, STOPPED=198 (cancelado), SUCCESS=200;
  erros: BAD_REQUEST=400, CANNOT_RESUME=489, CHECKSUM_ERROR=490,
  UNKNOWN_ERROR=491, FILE_ERROR=492, UNHANDLED_REDIRECT=493,
  UNHANDLED_HTTP_CODE=494, HTTP_DATA_ERROR=495, TOO_MANY_REDIRECTS=497,
  INSUFFICIENT_SPACE=498. **Não existe status "queued"** — a fila é só em
  memória (fragilidade).
- Recuperação pós-crash/reboot: `RestoreDownloadsWorker` reenfileira tudo com
  status PENDING/RUNNING/FETCH_METADATA; `BootReceiver` opcional;
  `numFailed`/`retryAfter` persistidos permitem backoff contínuo entre
  reinícios.

### 1.4 Navegador embutido

- `ui/browser/BrowserActivity.java` + `BrowserViewModel.java`.
- Detecção: `WebView.DownloadListener` (`BrowserViewModel.downloadListener`,
  379-389) e longo-clique em links (`HitTestResult`) → `AddDownloadActivity`
  (diálogo de prévia: nome via `Content-Disposition`, MIME, tamanho, ETag,
  `partialSupport`, seletor de nº de peças, campo de checksum, seletor de UA).
- **Limite importante**: o listener recebe `userAgent`, `contentDisposition`,
  `mimeType`, `contentLength` mas **os descarta** — só a URL é propagada.
- Cookies: o engine injeta `CookieManager.getInstance().getCookie(url)` em
  **cada** requisição (`core/HttpConnection.java:118-125`); `Referer` é
  heurístico (`Utils.needsReferer`) e persistido na tabela `Header`.
- **Não existe** suporte a `blob:`/`data:` (grep = 0 ocorrências) — a URL
  falharia no probe HTTP (scheme não-http).
- **Não existe** classe `DownloadTaskAssembler` nem `MergeChunksAlgorithm`
  (grep = 0).

### 1.5 Integridade, retentativas, notificação

- Hash: apenas **MD5 e SHA-256** (`core/utils/DigestUtils.java`); verificação
  pós-download em `DownloadEngine.doVerifyChecksum` (264-296) →
  `STATUS_CHECKSUM_ERROR`.
- Retry: limite default **5**; backoff `30s * 2^(numFailed-1)` com fuzz 1-1.5×
  OU header `Retry-After` (limitado 30 s–24 h). Transitórios
  (`Utils.isStatusRetryable`): HTTP_DATA_ERROR, 500, 503, FILE_ERROR;
  definitivos: CANNOT_RESUME, TOO_MANY_REDIRECTS, BAD_REQUEST,
  INSUFFICIENT_SPACE; `412 Precondition Failed` → CANNOT_RESUME (ETag mudou);
  **401/407 caem num TODO** (sem fluxo de autenticação).
- Notificação: `core/DownloadNotifier.java`, 5 canais, throttle 500 ms,
  progresso/velocidade/ETA (velocidade = soma das peças), ações
  pausar/retomar/parar por download e globais, foreground + conclusão com
  intent "abrir".
- Pós-download (`onBeforeFinished`, `DownloadEngine.java:542-603`): verificar
  checksum → mover (pref) → **descompactar** (`core/archive/ArchiveExtractor.java`,
  Apache Commons Compress + 7z dedicado com teto de memória 10 MB) → notificar.

### 1.6 Extras

- Limite de velocidade: token por segundo **por peça** (`PieceThreadImpl`:
  489-511) — com 16 peças o limite efetivo multiplica por 16 (fragilidade).
- Wi-Fi only/roaming: constraints do WorkManager + checagem runtime por peça
  + `ConnectionReceiver` (broadcast `CONNECTIVITY_ACTION`, **deprecado**).
- Bateria: `PowerReceiver` (BATTERY_LOW/OKAY, POWER_CONNECTED/DISCONNECTED)
  → `engine.rescheduleDownloads()`; WakeLock opcional; otimização de bateria
  Android 12+ com diálogo.

### 1.7 Pontos fortes (a adotar)

1. Offset da peça só persiste após `fd.sync()` — retomada à prova de crash.
2. `If-Match`/ETag na retomada + `Accept-Encoding: identity` — sem corrupção
   quando o recurso muda no servidor.
3. Contexto HTTP persistido por download (tabela `Header`: ETag/Referer + UA).
4. Duas camadas de agendamento (constraints × foreground service) + varredura
   de restauração pós-boot.
5. Backoff com fuzz + respeito a `Retry-After`.
6. Pré-alocação + pre-flight de espaço livre + `deleteFileIfError`.
7. Extração pós-download integrada ao motor, com teto de memória no 7z.

### 1.8 Fragilidades (a corrigir na reimplementação)

1. Headers do `DownloadListener` descartados; `blob:`/`data:` não suportados.
2. 401/407 sem tratamento.
3. `HashMap`s do engine mutados fora de blocos `synchronized` (races possíveis).
4. Limite de velocidade por peça (limite global multiplica ×N).
5. `CONNECTIVITY_ACTION` deprecado; `info.retry` órfão (não honrado).
6. Sem mirrors/multi-source; sem status persistido de "queued".

---

## 2. Mapeamento do sistema atual do Port Store (verificado)

Rust (`core/src/`, crate `portstore_core`, exposto por JNI) + Kotlin
(`app/.../core/`).

### 2.1 Rust

| Módulo | Papel | Observações |
|---|---|---|
| `engine.rs` (819 l.) | fila, jobs, workers, pausa/retomada, snapshot | `Config{segments=6,maxConcurrent=3,speedLimitBps,wifiOnly,userAgent}`; `Job` com atomics (ctrl run/pause/cancel, downloaded/total, supports_ranges); `job_main` = loop de tentativas (6) com backoff; `run_attempt` = resolve MediaFire/Buzzheavier → probe → prepara `.part` → workers segmentados ou single |
| `download.rs` (645 l.) | probe, workers de segmento, single-stream, verificação | probe HEAD→GET `Range: bytes=0-0`; `classify_status` (404/405/410/451 e 4xx≠408/429 fatais); roubo dinâmico de ranges (`split_range`, min 4 MiB); `MAX_SEG_RETRIES=8`; escrita `pwrite` (`write_all_at`); `verify_and_finish` (tamanho, SHA-256 opcional, magic APK, rename `.part`→dest) |
| `segment.rs` | matemática de intervalos inclusivos | `plan_segments`, `split_range`, `range_len` (testado) |
| `state.rs` | `.state` JSON por download (tmp+rename) | url/etag/last_modified/total/ranges/single/downloaded/pending; `compatible()` valida ETag/LM/tamanho na retomada |
| `backoff.rs` | exponencial 500 ms·2^(n-1), teto 30 s, jitter xorshift ≤30% | `ErrorKind::{Retryable,Fatal}`; respeita `Retry-After` (teto 120 s) |
| `rate_limit.rs` | token bucket **global** | 0 = ilimitado; consumo async |
| `callback.rs` | `EventSink` (on_progress/on_state) + impl JNI | attach_current_thread + GlobalRef |
| `jni_bridge.rs` | nativeInit/Enqueue/Pause/Resume/Cancel/SetConfig/SetNetworkState/GetSnapshot + resolve/download do provedor | panics contidos com `catch_unwind` |
| `mediafire.rs`, `buzzheavier.rs` | resolvedores de página → link direto | buzzheavier detecta challenge Cloudflare (`cf-mitigated`/`Just a moment`) |
| `provider_download.rs` | download wreq (fingerprint Chrome147) com cookies/UA, Range/416/200, progresso por atomics | single-stream, **fora** da fila do engine; challenge detection |
| `util.rs` | `parse_content_range_*`, `hex`, `looks_like_apk` | |

### 2.2 Kotlin

- `core/NativeDownloader.kt`: objeto JNI com `available` (dlopen pode falhar —
  loga e desabilita o motor; `startDownload` grava FAILED claro).
- `core/DownloadModels.kt`: `DlState` (Queued/Connecting/Downloading/Paused/
  Verifying/Completed/Failed/Canceled), `DownloadItem`, `NativeConfig`,
  `NativeJobOptions{expectedSha256,isApk,estimatedSize}`, `SnapshotItem`.
- `core/DownloadRepository.kt`: Room (`DownloadEntity`) = fonte da verdade +
  `live` (progresso em RAM); `restorePending()` reenfileira não-terminais;
  polls do snapshot (700 ms) e persiste progresso no Room (10 s);
  **checagem de espaço (StatFs) só no `startDownload`**.
- `service/DownloadService.kt`: foreground service com notificação.
- `gamedata/GameDataInstaller.kt` (1121 l.): fluxo v2 dos dados
  complementares — resolve página do provedor (Rust) → (challenge →
  `GameDataChallengeDialog` WebView → cookies+UA) → `downloadPackage`
  (bloqueante, wreq, Range/416) → extração TAR **em streaming** direto no
  ambiente virtual (`extractPackage` + `PackageDestinations`) →
  `finalizeInstall` (APK pendente/registro virtual) → marker `Installed`.
  `PackageDestinations.isUnsafePath` barra zip-slip.
- `gamedata/GameDataChallengeDialog.kt`: WebView real (UA desktop igual ao do
  Rust, JS+DOM storage+cookies de terceiros) para passar o Cloudflare e
  devolver `cf_clearance`+UA; global no `MainActivity`.

### 2.3 Falhas, gargalos e dívidas técnicas identificadas

1. **Sem suporte a headers de sessão no engine principal** (`enqueue` não
   aceita cookies/Referer/headers) — downloads capturados da WebView não
   poderiam usar o motor robusto; só o caminho `provider_download` (single
   stream, sem fila, sem pausa, sem multipart) aceita sessão.
2. **Sem persistência estruturada no Rust**: `.state` JSON por job (bom), mas
   sem banco transacional de tarefas/partes/headers; metadados (hash esperado,
   headers de sessão) não sobrevivem ao processo.
3. **Sem `If-Range`/`If-Match` na requisição de retomada** — a validação de
   ETag ocorre só no probe; entre probe e spans o recurso poderia mudar.
4. **Sem fila com prioridade** (ordem do `HashMap`), **sem limite de
   velocidade por download** (só global), **sem proxy**, **sem roaming**
   (Kotlin não informa), **sem MD5** (só SHA-256), **sem checagem de espaço
   contínua** (só pre-flight no Kotlin), **sem `fsync` antes do rename final**.
5. **Máquina de estados implícita** — `State` é setado diretamente, sem
   transições validadas/testáveis; não há estado `Extracting`.
6. **Extração/post-process vive só no Kotlin** (correto p/ o ambiente virtual),
   mas o pipeline não é modelado no motor (idempotência por etapas não é
   observável).
7. **Fluxo de dados complementares não aparece na tela Downloads** — o
   `downloadPackage` é bloqueante e invisível à fila (sem pausa/retomada/
   cancelamento do usuário).
8. Erros são strings ad-hoc (não tipados); mensagens PT-BR espalhadas.
9. `blob:`/`data:` não suportados (igual ao Download Navi).
10. Sem testes de integração HTTP (só unitários de lógica pura).

---

## 3. Tabela comparativa (Download Navi × Port Store atual × alvo)

| Recurso | Download Navi | Port Store atual | Alvo desta tarefa |
|---|---|---|---|
| Multiparte (até 16) | ✅ 16 peças, threads por peça | ✅ até 16 segmentos, roubo dinâmico | ✅ manter + rebalanceamento (roubo) |
| Retomada | ✅ offsets no Room após fd.sync | ✅ `.state` JSON (3 s) | ✅ + SQLite transacional + fsync |
| Validação de mudança do recurso | ✅ `If-Match` por peça | ⚠️ só ETag no probe | ✅ `If-Range` nos spans + probe |
| Fallback sem Range | ✅ 1 peça do zero | ✅ single-stream | ✅ manter |
| Fila + simultâneo | ✅ 3 (pref), fila em RAM | ⚠️ semaphore 3, sem prioridade | ✅ + prioridade persistida |
| Limite de velocidade | ⚠️ por peça (×N) | ✅ global (token bucket) | ✅ global + por download |
| Retentativas | ✅ 5, exp+fuzz, Retry-After | ✅ 6, exp+jitter, Retry-After | ✅ manter + classificação tipada |
| Headers de sessão | ✅ CookieManager+Referer por download | ❌ no engine; ⚠️ no provider_download | ✅ sessão completa (cookies/UA/Referer/extra) |
| Captura do navegador | ⚠️ só URL (headers descartados) | ❌ (só challenge) | ✅ captura completa (URL+headers+cookies) |
| `blob:`/`data:` | ❌ | ❌ | ✅ blob via hook JS (teto de tamanho); data: pequenos |
| Persistência | ✅ Room v6 + migrações | ⚠️ Room (Kotlin) + `.state` JSON | ✅ SQLite (Rust) + migração/import |
| Recuperação pós-crash | ✅ RestoreDownloadsWorker | ✅ restorePending (Room) | ✅ + offsets no SQLite |
| Hash | ✅ MD5+SHA-256 | ⚠️ SHA-256 | ✅ SHA-256 + MD5 |
| Espaço em disco | ✅ pre-flight + fallocate | ⚠️ pre-flight Kotlin | ✅ pre-flight Kotlin + contínuo Rust |
| Sanitização/zip-slip | ⚠️ (nome FAT) | ✅ Kotlin (`isUnsafePath`) | ✅ + sanitização Rust do nome capturado |
| Wi-Fi only/roaming | ✅ constraints+runtime | ⚠️ wifi only | ✅ + roaming (config) |
| Bateria/energia | ✅ receivers | ⚠️ foreground service | ⚠️ manter (fora de escopo ampliar) |
| Proxy/UA configuráveis | ✅ UA (tabela), ❌ proxy | ⚠️ UA global, ❌ proxy | ✅ proxy+UA por config e por captura |
| Extração pós-download | ✅ commons-compress+7z | ✅ TAR streaming (Kotlin, ambiente virtual) | ✅ manter Kotlin (justificado) + estados no motor |
| Observabilidade | ⚠️ notificação rica | ⚠️ log crate + callbacks 4/s | ✅ eventos + erros tipados PT-BR |
| Notificações | ✅ 5 canais, ações | ✅ 1 canal (foreground) | ⚠️ manter (não é o foco) |
| Testes HTTP | ❌ não vistos | ❌ | ✅ integração com servidor local |

Legenda: ✅ completo · ⚠️ parcial/fragil · ❌ ausente.

---

## 4. Plano de migração (etapas compiláveis e testáveis de forma independente)

O motor atual **não é reescrito do zero**: as etapas reorganizam e estendem,
mantendo o JNI compatível (serde com defaults ⇒ JSON antigo continua válido).
Cada etapa = 1+ commit, `cargo test` verde no CI, sem regressão dos fluxos
existentes (APK do catálogo e dados complementares v2).

- **E1 — Fundações**: deps (`thiserror`, `md-5`, `rusqlite[bundled]`, `libc`);
  `error.rs` (erros tipados + mensagem PT-BR); `integrity.rs` (SHA-256/MD5
  streaming, `sanitize_filename`, espaço livre via `statvfs`). *Risco maior:
  cross-compile do SQLite — validado no primeiro CI.*
- **E2 — Máquina de estados explícita**: `fsm.rs` com transições válidas
  (inclui `Extracting`), integrada ao `set_job_state`; Kotlin recebe a nova
  string.
- **E3 — Captura da WebView (contrato)**: `webview_bridge.rs`
  (`CapturedDownload` serde + validação: scheme http/https, tamanhos, sanitização
  de nome, limite de cookies/headers) + sessão wreq (cookies/UA/Referer) no
  engine + `nativeEnqueueCaptured` (JNI).
- **E4 — Store SQLite**: `store/` (tabelas `tasks`/`parts`/migrações),
  persister grava lá (throttle 3 s), import de `.state` legado na primeira
  gravação, consulta de recuperação pós-crash.
- **E5 — Núcleo do engine**: fila com prioridade; limite por download; `If-Range`
  nos spans (200 na retomada ⇒ invalida e recomeça); proxy; `fsync`+dir fsync
  antes do rename; checagem contínua de espaço; MD5 na verificação.
- **E6 — Testes de integração**: servidor HTTP/1.1 local em `tokio::TcpListener`
  (Range on/off, queda no meio, ETag alterado, 403/404/5xx, 416) + teste de
  recuperação (persistir offsets, "reiniciar" engine, retomar) + cenário de
  captura ponta a ponta no Rust (validação → job com sessão → verificação).
- **E7 — Kotlin**: `DlState.EXTRACTING`; `NativeConfig{proxy}` e
  `NativeJobOptions` estendido (priority/md5/referer/cookies/ua/headers/
  transport); `NativeDownloader.enqueueCaptured`; **`GameDataCaptureActivity`**
  (tela cheia, landscape travado, imersivo, DownloadListener → captura completa,
  cookies via `CookieManager`, whitelist de domínios por jogo, bloqueio de
  `intent://`/`market://`, erro de página com "Tentar novamente", recarregar/
  fechar, timeout configurável, múltiplas capturas enfileiradas, gamepad+toque,
  blob: via hook JS com teto de tamanho); `DownloadRepository.enqueueCaptured`
  + conclusão → pipeline de extração existente; `GameDataInstaller` passa a
  enfileirar o download dos dados no motor (aparece na tela Downloads com
  pausa/retomada) em vez do download bloqueante invisível.
- **E8 — Docs e encerramento**: `docs/DOWNLOAD_ENGINE.md` (arquitetura, migração,
  teste manual), atualização do worklog e resumo final.

## 5. Desvios conscientes do pedido (justificados)

- **`tracing`**: mantém-se a facade `log` (o `android_logger` do Android e o
  ecossistema JNI já a usam; `tracing` exigiria subscriber próprio sem ganho
  neste estágio). Logs já são estruturais (campos explícitos); migração para
  `tracing` fica registrada como evolução.
- **Extração em Rust (zip/7z/…)**: a extração do Port Store acontece **dentro
  do ambiente virtual** (mapeamento TAR→OBB/extdata/data é Kotlin) e já tem
  proteção zip-slip testada em produção; duplicar em Rust adicionaria um
  segundo caminho de verdade. O motor modela as etapas (estado `Extracting`,
  idempotência) e delega a execução ao pipeline existente.
- **Foreground service/notificações**: já existem em Kotlin e funcionam; não
  serão refeitos.
