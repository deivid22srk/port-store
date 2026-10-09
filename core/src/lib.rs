//! Port Store — motor de download segmentado, retomável e paralelo.
//!
//! Exposto ao Kotlin via JNI (ver `jni_bridge.rs`). Cada download é dividido
//! em segmentos baixados em paralelo com escrita posicionada (`pwrite`) em um
//! arquivo pré-alocado; o estado é persistido em um arquivo `.state` (JSON)
//! para retomada após pausa, queda de rede ou morte do processo.

pub mod backoff;
pub mod callback;
pub mod download;
pub mod engine;
pub mod jni_bridge;
pub mod mediafire;
pub mod rate_limit;
pub mod segment;
pub mod state;
pub mod util;

use std::sync::Arc;
use std::sync::OnceLock;

pub const UA_FALLBACK: &str = "PortStore/1.0 (Android)";

/// Runtime tokio compartilhado (vive enquanto o processo viver).
static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
static ENGINE: OnceLock<Arc<engine::Engine>> = OnceLock::new();

pub fn runtime_handle() -> tokio::runtime::Handle {
    RUNTIME
        .get_or_init(|| {
            tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()
                .expect("falha ao criar runtime tokio")
        })
        .handle()
        .clone()
}

pub fn engine() -> Option<&'static Arc<engine::Engine>> {
    ENGINE.get()
}

pub fn set_engine(e: Arc<engine::Engine>) -> bool {
    ENGINE.set(e).is_ok()
}

/// Garante um provedor TLS no nível do processo (ring). Se outro provider
/// já estiver instalado, ignora silenciosamente.
pub fn init_crypto() {
    let _ = rustls::crypto::ring::default_provider().install_default();
}

#[cfg(target_os = "android")]
pub fn init_logger() {
    let _ = android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("portstore_core"),
    );
}

#[cfg(not(target_os = "android"))]
pub fn init_logger() {
    // host (testes): sem logger específico
}
