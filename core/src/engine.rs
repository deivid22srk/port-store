//! Motor: fila, jobs, workers, pausa/retomada, eventos e snapshot.

use crate::backoff::backoff_delay;
use crate::callback::EventSink;
use crate::download;
use crate::rate_limit::RateLimiter;
use crate::segment;
use crate::state::{self, PersistState};

use serde::{Deserialize, Serialize};
use std::collections::{HashMap, VecDeque};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub const CTRL_RUN: u8 = 0;
pub const CTRL_PAUSE: u8 = 1;
pub const CTRL_CANCEL: u8 = 2;

pub const MAX_JOB_ATTEMPTS: u32 = 6;

/// Config enviada pelo Kotlin em camelCase (NativeConfig).
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Config {
    pub segments: usize,
    pub max_concurrent: usize,
    pub speed_limit_bps: u64,
    pub wifi_only: bool,
    pub user_agent: String,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            segments: 6,
            max_concurrent: 3,
            speed_limit_bps: 0,
            wifi_only: false,
            user_agent: crate::UA_FALLBACK.to_string(),
        }
    }
}

/// Opções por job enviadas pelo Kotlin em camelCase (NativeJobOptions).
#[derive(Debug, Clone, Deserialize, Serialize, Default)]
#[serde(default, rename_all = "camelCase")]
pub struct JobOptions {
    pub expected_sha256: Option<String>,
    pub is_apk: bool,
    pub estimated_size: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum State {
    Queued,
    Connecting,
    Downloading,
    Paused,
    Verifying,
    Completed,
    Failed,
    Canceled,
}

impl State {
    pub fn as_str(&self) -> &'static str {
        match self {
            State::Queued => "Queued",
            State::Connecting => "Connecting",
            State::Downloading => "Downloading",
            State::Paused => "Paused",
            State::Verifying => "Verifying",
            State::Completed => "Completed",
            State::Failed => "Failed",
            State::Canceled => "Canceled",
        }
    }
}

/// Estrutura compartilhada entre workers (fila de ranges + roubo dinâmico).
pub struct WorkerIo {
    pub pending: Mutex<VecDeque<(u64, u64)>>,
    /// slot por worker: (posição atual, fim do intervalo)
    pub inflight: Mutex<Vec<Option<(u64, u64)>>>,
    pub steal: AtomicBool,
    pub busy: AtomicU64,
    pub work_notify: tokio::sync::Notify,
}

pub struct Job {
    pub id: String,
    pub url: String,
    pub dest: PathBuf,
    pub options: JobOptions,
    pub ctrl: AtomicU8,
    pub downloaded: AtomicU64,
    pub total: AtomicU64,
    pub supports_ranges: AtomicBool,
    pub delete_on_cancel: AtomicBool,
    pub state: Mutex<State>,
    pub error: Mutex<Option<String>>,
    /// WorkerIo do download em andamento (para snapshot do .state)
    pub io: Mutex<Option<Arc<WorkerIo>>>,
    /// amostras (instant, bytes acumulados) para média móvel de velocidade
    pub speed_samples: Mutex<VecDeque<(Instant, u64)>>,
    /// task principal viva?
    pub running: AtomicBool,
    pub job_notify: tokio::sync::Notify,
}

impl Job {
    pub fn total_or_max(&self) -> u64 {
        match self.total.load(Ordering::Relaxed) {
            0 => u64::MAX,
            t => t,
        }
    }

    pub fn speed_bps(&self) -> u64 {
        let mut samples = self.speed_samples.lock().unwrap_or_else(|e| e.into_inner());
        let now = Instant::now();
        let cur = self.downloaded.load(Ordering::Relaxed);
        samples.push_back((now, cur));
        while let Some((t, _)) = samples.front() {
            if now.duration_since(*t) > Duration::from_secs(6) {
                samples.pop_front();
            } else {
                break;
            }
        }
        if samples.len() < 2 {
            return 0;
        }
        let (t0, b0) = *samples.front().unwrap();
        let (t1, b1) = *samples.back().unwrap();
        let dt = t1.duration_since(t0).as_secs_f64();
        if dt < 0.25 {
            return 0;
        }
        ((b1 - b0) as f64 / dt) as u64
    }
}

#[derive(Debug, Clone)]
pub enum Outcome {
    Done,
    PausedByUser,
    PausedNetwork,
    Canceled,
    Failed { error: String, retryable: bool },
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SnapshotEntry {
    id: String,
    state: String,
    downloaded: u64,
    total: u64,
    speed_bps: u64,
    eta_sec: u64,
    error: Option<String>,
}

pub struct Engine {
    pub(crate) callback: Arc<dyn EventSink>,
    pub(crate) client: reqwest::Client,
    pub(crate) limiter: Arc<RateLimiter>,
    pub(crate) cfg: std::sync::RwLock<Config>,
    pub(crate) network_connected: AtomicBool,
    pub(crate) network_unmetered: AtomicBool,
    /// gerador de eventos de rede (watch: sem corrida)
    pub(crate) net_watch: tokio::sync::watch::Sender<u64>,
    pub(crate) slots: Arc<tokio::sync::Semaphore>,
    slot_permits: AtomicU64,
    pub(crate) jobs: Mutex<HashMap<String, Arc<Job>>>,
}

impl Engine {
    pub fn new(config: Config, callback: Arc<dyn EventSink>) -> reqwest::Result<Arc<Engine>> {
        let max_concurrent = config.max_concurrent.clamp(1, 6);
        let client = reqwest::Client::builder()
            .user_agent(config.user_agent.clone())
            .connect_timeout(Duration::from_secs(20))
            .read_timeout(Duration::from_secs(40))
            .pool_idle_timeout(Duration::from_secs(90))
            .pool_max_idle_per_host(16)
            .redirect(reqwest::redirect::Policy::limited(10))
            .build()?;

        let limiter = Arc::new(RateLimiter::new(config.speed_limit_bps));
        Ok(Arc::new(Engine {
            callback,
            client,
            limiter,
            cfg: std::sync::RwLock::new(config),
            network_connected: AtomicBool::new(true),
            network_unmetered: AtomicBool::new(false),
            net_watch: tokio::sync::watch::channel(0u64).0,
            slots: Arc::new(tokio::sync::Semaphore::new(max_concurrent)),
            slot_permits: AtomicU64::new(max_concurrent as u64),
            jobs: Mutex::new(HashMap::new()),
        }))
    }

    // ------------------------------------------------------------- configuração

    pub fn update_config(&self, cfg: Config) {
        self.limiter.set_rate(cfg.speed_limit_bps);
        let old_max = self.slot_permits.load(Ordering::Relaxed) as i64;
        let new_max = cfg.max_concurrent.clamp(1, 6) as i64;
        *self.cfg.write().unwrap_or_else(|e| e.into_inner()) = cfg;
        let diff = new_max - old_max;
        if diff > 0 {
            self.slots.add_permits(diff as usize);
        } else if diff < 0 {
            for _ in 0..(-diff) {
                if let Ok(permit) = self.slots.try_acquire() {
                    permit.forget();
                }
            }
        }
        self.slot_permits.store(new_max as u64, Ordering::Relaxed);
        let _ = self.net_watch.send(0);
    }

    pub fn set_network_state(&self, connected: bool, unmetered: bool) {
        self.network_connected.store(connected, Ordering::Relaxed);
        self.network_unmetered.store(unmetered, Ordering::Relaxed);
        let _ = self.net_watch.send(0);
    }

    pub(crate) fn network_allowed(&self) -> bool {
        let wifi_only = self.cfg.read().unwrap_or_else(|e| e.into_inner()).wifi_only;
        let connected = self.network_connected.load(Ordering::Relaxed);
        let unmetered = self.network_unmetered.load(Ordering::Relaxed);
        connected && (!wifi_only || unmetered)
    }

    async fn wait_network(&self) {
        let mut rx = self.net_watch.subscribe();
        while !self.network_allowed() {
            if rx.changed().await.is_err() {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ fila

    pub fn enqueue(self: &Arc<Self>, id: &str, url: &str, dest: &str, options: JobOptions) -> bool {
        let mut jobs = self.jobs.lock().unwrap_or_else(|e| e.into_inner());

        if let Some(existing) = jobs.get(id) {
            let running = existing.running.load(Ordering::Relaxed);
            if running {
                return true; // já ativo
            }
            jobs.remove(id); // terminal: recria
        }

        let dest_path = PathBuf::from(dest);
        if let Some(parent) = dest_path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }

        let job = Arc::new(Job {
            id: id.to_string(),
            url: url.to_string(),
            dest: dest_path,
            options,
            ctrl: AtomicU8::new(CTRL_RUN),
            downloaded: AtomicU64::new(0),
            total: AtomicU64::new(0),
            supports_ranges: AtomicBool::new(true),
            delete_on_cancel: AtomicBool::new(true),
            state: Mutex::new(State::Queued),
            error: Mutex::new(None),
            io: Mutex::new(None),
            speed_samples: Mutex::new(VecDeque::new()),
            running: AtomicBool::new(true),
            job_notify: tokio::sync::Notify::new(),
        });

        jobs.insert(id.to_string(), job.clone());
        drop(jobs);

        let engine = self.clone();
        crate::runtime_handle().spawn(async move {
            job_main(engine, job).await;
        });
        true
    }

    pub fn pause(self: &Arc<Self>, id: &str) {
        if let Some(job) = self.find_job(id) {
            job.ctrl.store(CTRL_PAUSE, Ordering::Relaxed);
            job.job_notify.notify_waiters();
            self.set_job_state(&job, State::Paused, None);
        }
    }

    pub fn resume(self: &Arc<Self>, id: &str) -> bool {
        if let Some(job) = self.find_job(id) {
            let running = job.running.load(Ordering::Relaxed);
            job.ctrl.store(CTRL_RUN, Ordering::Relaxed);
            self.set_job_state(&job, State::Queued, None);
            job.job_notify.notify_waiters();
            if !running {
                let engine = self.clone();
                crate::runtime_handle().spawn(async move {
                    job_main(engine, job).await;
                });
            }
            true
        } else {
            false
        }
    }

    pub fn cancel(self: &Arc<Self>, id: &str, delete_file: bool) {
        if let Some(job) = self.find_job(id) {
            job.delete_on_cancel.store(delete_file, Ordering::Relaxed);
            job.ctrl.store(CTRL_CANCEL, Ordering::Relaxed);
            job.job_notify.notify_waiters();
            let running = job.running.load(Ordering::Relaxed);
            if !running {
                // task já não existe: limpa direto
                self.cleanup_canceled(&job, delete_file);
            }
        }
        let _ = self.net_watch.send(0);
    }

    fn cleanup_canceled(&self, job: &Arc<Job>, delete_file: bool) {
        self.jobs
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&job.id);
        state::remove(&job.dest);
        if delete_file {
            let _ = std::fs::remove_file(state::part_path(&job.dest));
            let _ = std::fs::remove_file(&job.dest);
        }
    }

    fn find_job(&self, id: &str) -> Option<Arc<Job>> {
        self.jobs.lock().unwrap_or_else(|e| e.into_inner()).get(id).cloned()
    }

    pub(crate) fn set_job_state(&self, job: &Arc<Job>, state: State, error: Option<String>) {
        {
            let mut s = job.state.lock().unwrap_or_else(|e| e.into_inner());
            *s = state;
        }
        {
            let mut e = job.error.lock().unwrap_or_else(|e| e.into_inner());
            *e = error.clone();
        }
        self.callback
            .on_state(&job.id, state.as_str(), error.as_deref());
    }

    /// JSON com todos os downloads para o Kotlin.
    pub fn snapshot_json(&self) -> String {
        let jobs = self.jobs.lock().unwrap_or_else(|e| e.into_inner());
        let mut entries: Vec<SnapshotEntry> = Vec::with_capacity(jobs.len());
        for job in jobs.values() {
            let downloaded = job.downloaded.load(Ordering::Relaxed);
            let total = job.total.load(Ordering::Relaxed);
            let speed = job.speed_bps();
            let state = *job.state.lock().unwrap_or_else(|e| e.into_inner());
            let error = job.error.lock().unwrap_or_else(|e| e.into_inner()).clone();
            let eta = if speed > 0 && total > downloaded {
                (total - downloaded) / speed
            } else {
                0
            };
            entries.push(SnapshotEntry {
                id: job.id.clone(),
                state: state.as_str().to_string(),
                downloaded,
                total,
                speed_bps: speed,
                eta_sec: eta,
                error,
            });
        }
        serde_json::to_string(&entries).unwrap_or_else(|_| "[]".to_string())
    }
}

/// Task principal por job: espera rede, pega slot, roda tentativas com retry.
async fn job_main(engine: Arc<Engine>, job: Arc<Job>) {
    let mut attempts: u32 = 0;
    loop {
        match job.ctrl.load(Ordering::Relaxed) {
            CTRL_CANCEL => {
                let del = job.delete_on_cancel.load(Ordering::Relaxed);
                engine.cleanup_canceled(&job, del);
                engine.set_job_state(&job, State::Canceled, None);
                job.running.store(false, Ordering::Relaxed);
                return;
            }
            CTRL_PAUSE => {
                engine.set_job_state(&job, State::Paused, None);
                job.running.store(false, Ordering::Relaxed);
                return;
            }
            _ => {}
        }

        engine.wait_network().await;

        match job.ctrl.load(Ordering::Relaxed) {
            CTRL_CANCEL => {
                let del = job.delete_on_cancel.load(Ordering::Relaxed);
                engine.cleanup_canceled(&job, del);
                engine.set_job_state(&job, State::Canceled, None);
                job.running.store(false, Ordering::Relaxed);
                return;
            }
            CTRL_PAUSE => {
                engine.set_job_state(&job, State::Paused, None);
                job.running.store(false, Ordering::Relaxed);
                return;
            }
            _ => {}
        }

        // slot de concorrência global
        let permit = match engine.slots.clone().acquire_owned().await {
            Ok(p) => p,
            Err(_) => {
                job.running.store(false, Ordering::Relaxed);
                return;
            }
        };

        let outcome = run_attempt(engine.clone(), job.clone()).await;
        drop(permit);

        match outcome {
            Outcome::Done => {
                engine.set_job_state(&job, State::Completed, None);
                job.running.store(false, Ordering::Relaxed);
                return;
            }
            Outcome::PausedByUser => {
                engine.set_job_state(&job, State::Paused, None);
                job.running.store(false, Ordering::Relaxed);
                return;
            }
            Outcome::PausedNetwork => {
                engine.set_job_state(&job, State::Paused, Some("aguardando rede".into()));
                attempts = 0;
                continue;
            }
            Outcome::Canceled => {
                let del = job.delete_on_cancel.load(Ordering::Relaxed);
                engine.cleanup_canceled(&job, del);
                engine.set_job_state(&job, State::Canceled, None);
                job.running.store(false, Ordering::Relaxed);
                return;
            }
            Outcome::Failed { error, retryable } => {
                if retryable && attempts < MAX_JOB_ATTEMPTS {
                    attempts += 1;
                    engine.set_job_state(
                        &job,
                        State::Connecting,
                        Some(format!("{error} — tentativa {attempts}")),
                    );
                    tokio::time::sleep(backoff_delay(attempts, None)).await;
                    continue;
                }
                engine.set_job_state(&job, State::Failed, Some(error));
                job.running.store(false, Ordering::Relaxed);
                return;
            }
        }
    }
}

/// Uma "tentativa" completa: probe -> preparação -> workers -> verificação.
async fn run_attempt(engine: Arc<Engine>, job: Arc<Job>) -> Outcome {
    engine.set_job_state(&job, State::Connecting, None);

    // Probe (com retry curto)
    let probe = {
        let mut last_err: Option<String> = None;
        let mut ok: Option<download::Probe> = None;
        for attempt in 1..=4u32 {
            match download::probe(&engine.client, &job.url).await {
                Ok(p) => {
                    ok = Some(p);
                    break;
                }
                Err(e) => {
                    if e.kind() == crate::backoff::ErrorKind::Fatal {
                        return Outcome::Failed { error: e.message(), retryable: false };
                    }
                    last_err = Some(e.message());
                    tokio::time::sleep(backoff_delay(attempt, None)).await;
                }
            }
        }
        match ok {
            Some(p) => p,
            None => {
                let msg = last_err.unwrap_or_else(|| "falha ao conectar".into());
                return Outcome::Failed { error: msg, retryable: true };
            }
        }
    };

    if let Some(t) = probe.total {
        job.total.store(t, Ordering::Relaxed);
    }
    job.supports_ranges.store(probe.ranges, Ordering::Relaxed);

    // Espaço em disco é verificado no lado Kotlin (StatFs) antes do enqueue.

    // Estado salvo (retomada) + pré-alocação
    let saved = state::load(&job.dest);
    let persist = match download::prepare_part_file(&job.dest, &job.url, &probe, saved) {
        Ok(st) => st,
        Err(e) => {
            return Outcome::Failed {
                error: format!("arquivo: {e}"),
                retryable: false,
            }
        }
    };
    job.downloaded.store(persist.downloaded, Ordering::Relaxed);

    engine.set_job_state(&job, State::Downloading, None);

    let outcome = if probe.ranges {
        let total = probe.total.unwrap_or(0);
        let segments_cfg = engine.cfg.read().unwrap_or_else(|e| e.into_inner()).segments;
        let ranges: Vec<(u64, u64)> = if !persist.pending.is_empty() {
            persist.pending.clone()
        } else if persist.downloaded > 0 && total > persist.downloaded {
            vec![(persist.downloaded, total - 1)]
        } else if persist.downloaded >= total && total > 0 {
            Vec::new() // já completo
        } else {
            segment::plan_segments(total, segments_cfg)
        };

        if ranges.is_empty() {
            return finish(engine, &job, probe.total).await;
        }

        let io = Arc::new(WorkerIo {
            pending: Mutex::new(VecDeque::from(ranges)),
            inflight: Mutex::new(Vec::new()),
            steal: AtomicBool::new(false),
            busy: AtomicU64::new(0),
            work_notify: tokio::sync::Notify::new(),
        });
        {
            let mut slot = job.io.lock().unwrap_or_else(|e| e.into_inner());
            *slot = Some(io.clone());
        }

        let workers = segments_cfg.max(1).min(16);
        let res = download::run_workers(engine.clone(), job.clone(), io.clone(), workers).await;

        // persiste estado ao sair (pause/fail/cancel)
        if !matches!(res, Outcome::Done) {
            let pending_now = download::snapshot_pending(&io);
            let remaining: u64 = pending_now.iter().map(|(s, e)| segment::range_len(*s, *e)).sum();
            let downloaded_now = if total > 0 {
                total.saturating_sub(remaining)
            } else {
                persist.downloaded
            };
            save_state(&job, &probe, downloaded_now, pending_now, false);
        }
        {
            let mut slot = job.io.lock().unwrap_or_else(|e| e.into_inner());
            *slot = None;
        }
        res
    } else {
        // fluxo único (sem Range)
        let res = download::worker_single(engine.clone(), job.clone()).await;
        save_state(
            &job,
            &probe,
            job.downloaded.load(Ordering::Relaxed),
            Vec::new(),
            true,
        );
        res
    };

    match outcome {
        Outcome::Done => finish(engine, &job, probe.total).await,
        other => other,
    }
}

async fn finish(engine: Arc<Engine>, job: &Arc<Job>, probe_total: Option<u64>) -> Outcome {
    engine.set_job_state(job, State::Verifying, None);
    let job_for_blocking = job.clone();
    match tokio::task::spawn_blocking(move || download::verify_and_finish(&job_for_blocking, probe_total)).await {
        Ok(Ok(())) => Outcome::Done,
        Ok(Err(e)) => Outcome::Failed { error: e, retryable: false },
        Err(e) => Outcome::Failed {
            error: format!("verify panic: {e}"),
            retryable: true,
        },
    }
}

fn save_state(job: &Arc<Job>, probe: &download::Probe, downloaded: u64, pending: Vec<(u64, u64)>, single: bool) {
    let st = PersistState {
        url: job.url.clone(),
        etag: probe.etag.clone(),
        last_modified: probe.last_modified.clone(),
        total: probe.total.unwrap_or(0),
        ranges: probe.ranges,
        single,
        downloaded,
        pending,
        updated_at: download::now_secs(),
    };
    let _ = state::save(&job.dest, &st);
}

fn fmt_bytes(b: u64) -> String {
    let mb = b as f64 / (1024.0 * 1024.0);
    if mb >= 1024.0 {
        format!("{:.2} GB", mb / 1024.0)
    } else {
        format!("{:.0} MB", mb)
    }
}

#[cfg(test)]
mod fmt_tests {
    #[test]
    fn formata_bytes() {
        assert_eq!(super::fmt_bytes(63 * 1024 * 1024), "63 MB");
        assert_eq!(super::fmt_bytes(2u64 * 1024 * 1024 * 1024), "2.00 GB");
    }
}

/// Persiste o .state de jobs em Downloading a cada 3s (crash-safe).
pub fn spawn_state_persister(engine: Arc<Engine>) {
    crate::runtime_handle().spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_secs(3)).await;
            let jobs: Vec<Arc<Job>> = {
                let map = engine.jobs.lock().unwrap_or_else(|e| e.into_inner());
                map.values()
                    .filter(|j| {
                        *j.state.lock().unwrap_or_else(|e| e.into_inner()) == State::Downloading
                    })
                    .cloned()
                    .collect()
            };
            for job in jobs {
                let saved = state::load(&job.dest);
                let pending = match job.io.lock().unwrap_or_else(|e| e.into_inner()).clone() {
                    Some(io) => download::snapshot_pending(&io),
                    None => saved.as_ref().map(|s| s.pending.clone()).unwrap_or_default(),
                };
                let st = PersistState {
                    url: job.url.clone(),
                    etag: saved.as_ref().and_then(|s| s.etag.clone()),
                    last_modified: saved.as_ref().and_then(|s| s.last_modified.clone()),
                    total: job.total.load(Ordering::Relaxed),
                    ranges: job.supports_ranges.load(Ordering::Relaxed),
                    single: !job.supports_ranges.load(Ordering::Relaxed),
                    downloaded: job.downloaded.load(Ordering::Relaxed),
                    pending,
                    updated_at: download::now_secs(),
                };
                let _ = state::save(&job.dest, &st);
            }
        }
    });
}

/// Emite progresso (~4/s) para cada job em Downloading.
pub fn spawn_progress_notifier(engine: Arc<Engine>) {
    crate::runtime_handle().spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_millis(250)).await;
            let jobs: Vec<Arc<Job>> = {
                let map = engine.jobs.lock().unwrap_or_else(|e| e.into_inner());
                map.values()
                    .filter(|j| {
                        *j.state.lock().unwrap_or_else(|e| e.into_inner()) == State::Downloading
                    })
                    .cloned()
                    .collect()
            };
            for job in jobs {
                let downloaded = job.downloaded.load(Ordering::Relaxed);
                let total = job.total.load(Ordering::Relaxed);
                let speed = job.speed_bps();
                let eta = if speed > 0 && total > downloaded {
                    (total - downloaded) / speed
                } else {
                    0
                };
                engine.callback.on_progress(&job.id, downloaded, total, speed, eta);
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn config_default_e_serializacao() {
        let cfg = Config::default();
        let json = serde_json::to_string(&cfg).unwrap();
        let back: Config = serde_json::from_str(&json).unwrap();
        assert_eq!(back.segments, 6);
        assert_eq!(back.max_concurrent, 3);

        // campos parciais usam defaults
        let partial: Config = serde_json::from_str(r#"{"segments": 9}"#).unwrap();
        assert_eq!(partial.segments, 9);
        assert_eq!(partial.max_concurrent, 3);
    }

    #[test]
    fn job_options_parse() {
        let opts: JobOptions =
            serde_json::from_str(r#"{"expectedSha256":"abc","isApk":true,"estimatedSize":123}"#).unwrap();
        assert_eq!(opts.expected_sha256.as_deref(), Some("abc"));
        let empty: JobOptions = serde_json::from_str("{}").unwrap();
        assert!(empty.expected_sha256.is_none());
    }

    #[test]
    fn state_strings() {
        assert_eq!(State::Downloading.as_str(), "Downloading");
        assert_eq!(State::Paused.as_str(), "Paused");
        assert_eq!(State::Verifying.as_str(), "Verifying");
    }
}
