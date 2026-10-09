//! Sondagem HTTP, workers de segmento, fallback single-stream e verificação.

use crate::backoff::{backoff_delay, ErrorKind};
use crate::engine::{Engine, Job, Outcome, WorkerIo};
use crate::segment;
use crate::state::{self, PersistState};
use crate::util;

use futures_util::StreamExt;
use std::path::Path;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;

pub const MIN_STEAL_CHUNK: u64 = 4 * 1024 * 1024; // split só p/ ranges >= 8 MiB
pub const MAX_SEG_RETRIES: u32 = 8;

#[derive(Debug, Clone, Default)]
pub struct Probe {
    pub total: Option<u64>,
    pub ranges: bool,
    pub etag: Option<String>,
    pub last_modified: Option<String>,
}

#[derive(Debug)]
pub enum HttpError {
    Status { code: u16, retry_after: Option<Duration>, fatal: bool },
    Network(String),
}

impl HttpError {
    pub fn kind(&self) -> ErrorKind {
        match self {
            HttpError::Network(_) => ErrorKind::Retryable,
            HttpError::Status { fatal, .. } => {
                if *fatal {
                    ErrorKind::Fatal
                } else {
                    ErrorKind::Retryable
                }
            }
        }
    }

    pub fn message(&self) -> String {
        match self {
            HttpError::Status { code, .. } => format!("HTTP {code}"),
            HttpError::Network(e) => format!("erro de rede: {e}"),
        }
    }
}

fn classify_status(code: u16, retry_after: Option<Duration>) -> HttpError {
    let fatal = matches!(code, 404 | 405 | 410 | 451)
        || ((400..500).contains(&code) && code != 408 && code != 429);
    HttpError::Status { code, retry_after, fatal }
}

/// Sonda a URL: HEAD; se não der, GET com Range bytes=0-0.
pub async fn probe(client: &reqwest::Client, url: &str) -> Result<Probe, HttpError> {
    match client.head(url).send().await {
        Ok(resp) if resp.status().as_u16() == 200 => {
            let headers = resp.headers();
            let total = header_u64(headers, reqwest::header::CONTENT_LENGTH);
            let ranges = headers
                .get(reqwest::header::ACCEPT_RANGES)
                .and_then(|v| v.to_str().ok())
                .map(|v| v.to_ascii_lowercase().contains("bytes"))
                .unwrap_or(false);
            return Ok(Probe {
                total,
                ranges: ranges && total.is_some() && total.unwrap_or(0) > 0,
                etag: header_str(headers, reqwest::header::ETAG),
                last_modified: header_str(headers, reqwest::header::LAST_MODIFIED),
            });
        }
        // HEAD pode não ser suportado ou dar erro: cai para o GET Range.
        _ => {}
    }

    let resp = client
        .get(url)
        .header(reqwest::header::RANGE, "bytes=0-0")
        .send()
        .await
        .map_err(|e| HttpError::Network(e.to_string()))?;

    let status = resp.status();
    let headers = resp.headers().clone();
    let etag = header_str(&headers, reqwest::header::ETAG);
    let last_modified = header_str(&headers, reqwest::header::LAST_MODIFIED);

    match status.as_u16() {
        206 => {
            let total = headers
                .get(reqwest::header::CONTENT_RANGE)
                .and_then(|v| v.to_str().ok())
                .and_then(util::parse_content_range_total);
            Ok(Probe { total, ranges: true, etag, last_modified })
        }
        s if (200..300).contains(&s) => {
            let total = header_u64(&headers, reqwest::header::CONTENT_LENGTH);
            Ok(Probe { total, ranges: false, etag, last_modified })
        }
        s => Err(classify_status(s, parse_retry_after(&headers))),
    }
}

fn header_str(h: &reqwest::header::HeaderMap, k: reqwest::header::HeaderName) -> Option<String> {
    h.get(k).and_then(|v| v.to_str().ok()).map(String::from)
}

fn header_u64(h: &reqwest::header::HeaderMap, k: reqwest::header::HeaderName) -> Option<u64> {
    h.get(k).and_then(|v| v.to_str().ok()).and_then(|v| v.parse::<u64>().ok())
}

fn parse_retry_after(headers: &reqwest::header::HeaderMap) -> Option<Duration> {
    let v = headers.get(reqwest::header::RETRY_AFTER)?.to_str().ok()?;
    v.parse::<u64>().ok().map(Duration::from_secs)
}

/// Prepara o .part (cria/pré-aloca, valida estado salvo). Retorna o estado a usar.
pub fn prepare_part_file(
    dest: &Path,
    url: &str,
    probe: &Probe,
    saved: Option<PersistState>,
) -> std::io::Result<PersistState> {
    let part = state::part_path(dest);
    let total = probe.total.unwrap_or(0);

    // Estado salvo só é aproveitável se compatível com o recurso atual.
    let usable = saved.filter(|s| {
        s.url == url
            && state::compatible(s, &probe.etag, &probe.last_modified, total)
            && (s.total == 0 || total == 0 || s.total == total)
            && s.ranges == probe.ranges
            && (probe.ranges || s.downloaded == 0) // sem Range: não dá para retomar
    });

    let fresh = usable.is_none();
    if fresh {
        let _ = std::fs::remove_file(&part);
    }

    let file = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .open(&part)?;

    if let Some(t) = probe.total {
        if t > 0 && file.metadata()?.len() != t {
            file.set_len(t)?;
        }
    }
    drop(file);

    Ok(usable.unwrap_or_else(|| PersistState {
        url: url.to_string(),
        etag: probe.etag.clone(),
        last_modified: probe.last_modified.clone(),
        total,
        ranges: probe.ranges,
        single: !probe.ranges,
        downloaded: 0,
        pending: Vec::new(),
        updated_at: crate::download::now_secs(),
    }))
}

pub fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Snapshot dos intervalos pendentes + em voo (para o .state).
pub fn snapshot_pending(io: &WorkerIo) -> Vec<(u64, u64)> {
    let mut out: Vec<(u64, u64)> = {
        let p = io.pending.lock().unwrap_or_else(|e| e.into_inner());
        p.iter().copied().collect()
    };
    let infl = io.inflight.lock().unwrap_or_else(|e| e.into_inner());
    for slot in infl.iter() {
        if let Some((pos, end)) = slot {
            out.push((*pos, *end));
        }
    }
    out
}

/// Executa N workers em paralelo; retorna no primeiro resultado não-Done.
pub async fn run_workers(engine: Arc<Engine>, job: Arc<Job>, io: Arc<WorkerIo>, worker_count: usize) -> Outcome {
    let mut set = tokio::task::JoinSet::new();
    for wid in 0..worker_count.max(1) {
        let engine = engine.clone();
        let job = job.clone();
        let io = io.clone();
        set.spawn(async move { worker(engine, job, io, wid).await });
    }

    let mut result: Option<Outcome> = None;
    while let Some(res) = set.join_next().await {
        let outcome = match res {
            Ok(o) => o,
            Err(join_err) => Outcome::Failed {
                error: format!("worker panic: {join_err}"),
                retryable: true,
            },
        };
        match outcome {
            Outcome::Done => continue,
            other => {
                result = Some(other);
                break;
            }
        }
    }
    if result.is_some() {
        set.abort_all();
    }
    result.unwrap_or(Outcome::Done)
}

/// Worker (modo segmentado): consome a fila de intervalos com roubo dinâmico.
pub async fn worker(engine: Arc<Engine>, job: Arc<Job>, io: Arc<WorkerIo>, wid: usize) -> Outcome {
    loop {
        match job.ctrl.load(Ordering::Relaxed) {
            crate::engine::CTRL_PAUSE => return Outcome::PausedByUser,
            crate::engine::CTRL_CANCEL => return Outcome::Canceled,
            _ => {}
        }
        if !engine.network_allowed() {
            return Outcome::PausedNetwork;
        }

        let range = {
            let mut p = io.pending.lock().unwrap_or_else(|e| e.into_inner());
            p.pop_front()
        };

        let (s, e) = match range {
            Some(r) => r,
            None => {
                if io.busy.load(Ordering::Relaxed) > 0 {
                    // outro worker está baixando: pede roubo e espera um pouco
                    io.steal.store(true, Ordering::Relaxed);
                    io.work_notify.notify_waiters();
                    tokio::time::sleep(Duration::from_millis(120)).await;
                } else {
                    let pending_empty = {
                        let p = io.pending.lock().unwrap_or_else(|e| e.into_inner());
                        p.is_empty()
                    };
                    if pending_empty {
                        let all_idle = {
                            let infl = io.inflight.lock().unwrap_or_else(|e| e.into_inner());
                            infl.iter().all(|s| s.is_none())
                        };
                        if all_idle {
                            return Outcome::Done;
                        }
                    }
                    tokio::time::sleep(Duration::from_millis(60)).await;
                }
                continue;
            }
        };

        io.busy.fetch_add(1, Ordering::Relaxed);
        set_inflight(&io, wid, Some((s, e)));

        let outcome = download_span(&engine, &job, &io, wid, s, e).await;

        set_inflight(&io, wid, None);
        io.busy.fetch_sub(1, Ordering::Relaxed);
        io.work_notify.notify_waiters();

        match outcome {
            SpanOutcome::Done => continue,
            SpanOutcome::Pause(Some(rem)) => {
                let mut p = io.pending.lock().unwrap_or_else(|e| e.into_inner());
                p.push_back(rem);
                return Outcome::PausedByUser;
            }
            SpanOutcome::Pause(None) => return Outcome::PausedByUser,
            SpanOutcome::Cancel => return Outcome::Canceled,
            SpanOutcome::Fail { error, retryable } => return Outcome::Failed { error, retryable },
        }
    }
}

fn set_inflight(io: &Arc<WorkerIo>, wid: usize, slot: Option<(u64, u64)>) {
    let mut infl = io.inflight.lock().unwrap_or_else(|e| e.into_inner());
    if wid >= infl.len() {
        infl.resize(wid + 1, None);
    }
    infl[wid] = slot;
}

enum SpanOutcome {
    Done,
    /// pause com o intervalo restante a devolver à fila
    Pause(Option<(u64, u64)>),
    Cancel,
    Fail { error: String, retryable: bool },
}

/// Baixa o intervalo [start, end] com retries, escrita posicionada e roubo.
async fn download_span(
    engine: &Arc<Engine>,
    job: &Arc<Job>,
    io: &Arc<WorkerIo>,
    wid: usize,
    start: u64,
    end_in: u64,
) -> SpanOutcome {
    let part = state::part_path(&job.dest);
    let file = match std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .open(&part)
    {
        Ok(f) => f,
        Err(e) => {
            return SpanOutcome::Fail {
                error: format!("disco: {e}"),
                retryable: false,
            }
        }
    };

    let mut end = end_in;
    let mut pos = start;
    let mut attempt: u32 = 0;

    'attempts: loop {
        attempt += 1;
        if attempt > MAX_SEG_RETRIES {
            return SpanOutcome::Fail {
                error: "excedeu o número de tentativas".into(),
                retryable: true,
            };
        }

        match job.ctrl.load(Ordering::Relaxed) {
            crate::engine::CTRL_PAUSE => return SpanOutcome::Pause(Some((pos, end))),
            crate::engine::CTRL_CANCEL => return SpanOutcome::Cancel,
            _ => {}
        }
        if !engine.network_allowed() {
            return SpanOutcome::Pause(Some((pos, end)));
        }

        let range_header = format!("bytes={pos}-{end}");
        let resp = match engine
            .client
            .get(&job.url)
            .header(reqwest::header::RANGE, &range_header)
            .send()
            .await
        {
            Ok(r) => r,
            Err(_) => {
                if pos > start {
                    attempt = 0; // houve progresso: zera backoff
                }
                tokio::time::sleep(backoff_delay(attempt.max(1), None)).await;
                continue 'attempts;
            }
        };

        let status = resp.status().as_u16();
        if !(200..300).contains(&status) {
            let ra = parse_retry_after(resp.headers());
            let err = classify_status(status, ra);
            if err.kind() == ErrorKind::Fatal {
                return SpanOutcome::Fail { error: err.message(), retryable: false };
            }
            tokio::time::sleep(backoff_delay(attempt, ra)).await;
            continue 'attempts;
        }

        // 200 para um sub-intervalo => servidor ignorou Range
        if status == 200 && !(start == 0 && end + 1 == job.total_or_max()) {
            return SpanOutcome::Fail {
                error: "o servidor não respeitou Range".into(),
                retryable: false,
            };
        }

        let mut stream = resp.bytes_stream();
        loop {
            match stream.next().await {
                Some(Ok(bytes)) => {
                    let n = bytes.len() as u64;
                    if n == 0 {
                        continue;
                    }

                    engine.limiter.consume(n).await;

                    match job.ctrl.load(Ordering::Relaxed) {
                        crate::engine::CTRL_PAUSE => {
                            set_inflight(io, wid, Some((pos, end)));
                            return SpanOutcome::Pause(Some((pos, end)));
                        }
                        crate::engine::CTRL_CANCEL => return SpanOutcome::Cancel,
                        _ => {}
                    }
                    if !engine.network_allowed() {
                        set_inflight(io, wid, Some((pos, end)));
                        return SpanOutcome::Pause(Some((pos, end)));
                    }

                    // roubo dinâmico: outro worker ocioso pediu metade deste range
                    if io.steal.swap(false, Ordering::Relaxed) {
                        if let Some((a, b)) = segment::split_range(pos, end, MIN_STEAL_CHUNK) {
                            end = a.1;
                            {
                                let mut p = io.pending.lock().unwrap_or_else(|e| e.into_inner());
                                p.push_back(b);
                            }
                            io.work_notify.notify_waiters();
                        } else {
                            io.steal.store(true, Ordering::Relaxed);
                        }
                    }

                    if let Err(e) = write_at(&file, pos, &bytes) {
                        return SpanOutcome::Fail { error: format!("disco: {e}"), retryable: false };
                    }
                    pos += n;
                    job.downloaded.fetch_add(n, Ordering::Relaxed);
                    if pos <= end {
                        set_inflight(io, wid, Some((pos, end)));
                    }
                }
                Some(Err(_)) => {
                    if pos > start {
                        attempt = 0; // progresso conta
                        set_inflight(io, wid, Some((pos, end)));
                    }
                    tokio::time::sleep(backoff_delay(attempt.max(1), None)).await;
                    continue 'attempts;
                }
                None => {
                    // stream encerrado
                    if pos == end + 1 {
                        return SpanOutcome::Done;
                    }
                    if pos > end + 1 {
                        return SpanOutcome::Fail {
                            error: "servidor enviou mais bytes que o intervalo".into(),
                            retryable: false,
                        };
                    }
                    // encerrou cedo: tenta de novo a partir de `pos`
                    set_inflight(io, wid, Some((pos, end)));
                    tokio::time::sleep(backoff_delay(attempt, None)).await;
                    continue 'attempts;
                }
            }
        }
    }
}

/// Worker (modo single-stream, sem Range): baixa tudo sequencialmente.
pub async fn worker_single(engine: Arc<Engine>, job: Arc<Job>) -> Outcome {
    let part = state::part_path(&job.dest);
    let file = match std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .open(&part)
    {
        Ok(f) => f,
        Err(e) => {
            return Outcome::Failed {
                error: format!("disco: {e}"),
                retryable: false,
            }
        }
    };

    let mut pos = job.downloaded.load(Ordering::Relaxed);
    let mut attempt: u32 = 0;

    'attempts: loop {
        attempt += 1;
        if attempt > MAX_SEG_RETRIES {
            return Outcome::Failed {
                error: "excedeu o número de tentativas".into(),
                retryable: true,
            };
        }

        match job.ctrl.load(Ordering::Relaxed) {
            crate::engine::CTRL_PAUSE => return Outcome::PausedByUser,
            crate::engine::CTRL_CANCEL => return Outcome::Canceled,
            _ => {}
        }
        if !engine.network_allowed() {
            return Outcome::PausedNetwork;
        }

        let mut request = engine.client.get(&job.url);
        if pos > 0 {
            request = request.header(reqwest::header::RANGE, format!("bytes={pos}-"));
        }
        let resp = match request.send().await {
            Ok(r) => r,
            Err(_) => {
                tokio::time::sleep(backoff_delay(attempt, None)).await;
                continue 'attempts;
            }
        };

        let status = resp.status().as_u16();
        if !(200..300).contains(&status) {
            let ra = parse_retry_after(resp.headers());
            let err = classify_status(status, ra);
            if err.kind() == ErrorKind::Fatal {
                return Outcome::Failed { error: err.message(), retryable: false };
            }
            tokio::time::sleep(backoff_delay(attempt, ra)).await;
            continue 'attempts;
        }

        if status == 200 && pos > 0 {
            // servidor ignorou o Range: recomeça do zero
            if file.set_len(0).is_err() {
                return Outcome::Failed {
                    error: "não foi possível reiniciar o arquivo".into(),
                    retryable: false,
                };
            }
            let dropped = pos;
            job.downloaded.fetch_sub(dropped, Ordering::Relaxed);
            pos = 0;
        }

        let mut stream = resp.bytes_stream();
        loop {
            match stream.next().await {
                Some(Ok(bytes)) => {
                    let n = bytes.len() as u64;
                    if n == 0 {
                        continue;
                    }
                    engine.limiter.consume(n).await;

                    match job.ctrl.load(Ordering::Relaxed) {
                        crate::engine::CTRL_PAUSE => return Outcome::PausedByUser,
                        crate::engine::CTRL_CANCEL => return Outcome::Canceled,
                        _ => {}
                    }
                    if !engine.network_allowed() {
                        return Outcome::PausedNetwork;
                    }

                    if let Err(e) = write_at(&file, pos, &bytes) {
                        return Outcome::Failed { error: format!("disco: {e}"), retryable: false };
                    }
                    pos += n;
                    job.downloaded.fetch_add(n, Ordering::Relaxed);
                }
                Some(Err(_)) => {
                    if attempt >= MAX_SEG_RETRIES {
                        return Outcome::Failed {
                            error: "conexão interrompida".into(),
                            retryable: true,
                        };
                    }
                    tokio::time::sleep(backoff_delay(attempt, None)).await;
                    continue 'attempts;
                }
                None => {
                    let total = job.total.load(Ordering::Relaxed);
                    if total > 0 && pos >= total {
                        return Outcome::Done;
                    }
                    if total == 0 && pos > 0 {
                        // sem Content-Length: considera completo quando o stream acaba
                        return Outcome::Done;
                    }
                    tokio::time::sleep(backoff_delay(attempt, None)).await;
                    continue 'attempts;
                }
            }
        }
    }
}

pub fn write_at(file: &std::fs::File, pos: u64, data: &[u8]) -> std::io::Result<()> {
    use std::os::unix::fs::FileExt;
    FileExt::write_all_at(file, data, pos)
}

/// Verificação final: tamanho, SHA-256 (se esperado), assinatura de APK.
/// Em caso de sucesso renomeia .part -> dest e remove o .state.
pub fn verify_and_finish(job: &Job, probe_total: Option<u64>) -> Result<(), String> {
    let part = state::part_path(&job.dest);
    let meta = std::fs::metadata(&part).map_err(|e| format!("arquivo sumiu: {e}"))?;

    if let Some(t) = probe_total {
        if t > 0 && meta.len() != t {
            return Err(format!("tamanho divergente: esperado {t}, obtido {}", meta.len()));
        }
    }
    if let Some(expected) = &job.options.expected_sha256 {
        let got = sha256_file(&part).map_err(|e| format!("hash: {e}"))?;
        if got != expected.to_lowercase() {
            return Err(format!("SHA-256 divergente (esperado {expected})"));
        }
    }

    if job.options.is_apk && meta.len() > 0 && !util::looks_like_apk(&part) {
        return Err("arquivo baixado não é um APK válido".into());
    }

    std::fs::rename(&part, &job.dest).map_err(|e| format!("rename: {e}"))?;
    state::remove(&job.dest);
    Ok(())
}

pub fn sha256_file(path: &Path) -> std::io::Result<String> {
    use sha2::Digest;
    use std::io::Read;
    let mut file = std::fs::File::open(path)?;
    let mut hasher = sha2::Sha256::new();
    let mut buf = vec![0u8; 1024 * 1024];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(util::hex(&hasher.finalize()))
}
