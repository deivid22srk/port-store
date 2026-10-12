//! Download de ARQUIVO do provedor (rota `/d/…` do Buzzheavier) com o MESMO
//! fingerprint de navegador do resolvedor (`buzzheavier.rs`).
//!
//! Por que NÃO usar o cliente do motor (reqwest/rustls) nem o OkHttp do app:
//! a rota `/d/` fica atrás do MESMO Cloudflare que desafia (403
//! `cf-mitigated: challenge`) clientes sem fingerprint de navegador — o
//! resolvedor passa com emulação Chrome (wreq), mas o download com outro
//! cliente voltaria a ser bloqueado. Ferramenta de referência também baixa o
//! arquivo com o fingerprint emulado.
//!
//! Fluxo Kotlin: `NativeDownloader.downloadProviderFile(url, dest, offset)`
//! (JNI BLOQUEANTE) — o Kotlin mantém a orquestração (tentativas, retomada
//! por `Range`, renomear/atômico, extração) e acompanha o progresso lendo
//! [progress_json] via `NativeDownloader.providerDownloadProgress()`.
//! Cancelamento: [cancel] interrompe o loop de streaming no próximo chunk.

use wreq::Client;
use wreq_util::Emulation;
use std::io::Write;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::time::Duration;

/// Cancelamento do download em andamento (requisição JNI do Kotlin).
static DL_CANCEL: AtomicBool = AtomicBool::new(false);

/// Progresso do download em andamento (visível ao Kotlin via JNI).
static DL_TOTAL: AtomicI64 = AtomicI64::new(-1);
static DL_WRITTEN: AtomicU64 = AtomicU64::new(0);

pub fn cancel() {
    DL_CANCEL.store(true, Ordering::SeqCst);
}

/// Cliente compartilhado (emulação Chrome — mesmo motivo do resolvedor).
/// Usado quando NÃO há UA custom (caminho rápido); com UA do WebView
/// (plano B do challenge) o cliente é construído por chamada — ver topo de
/// `buzzheavier.rs`: cf_clearance é ligado a IP+UA.
fn client() -> &'static Client {
    static C: std::sync::OnceLock<Client> = std::sync::OnceLock::new();
    C.get_or_init(|| build_client(None).expect("client provider download"))
}

fn build_client(user_agent: Option<&str>) -> wreq::Result<Client> {
    let mut builder = Client::builder()
        .emulation(Emulation::Chrome147)
        .connect_timeout(Duration::from_secs(15))
        .read_timeout(Duration::from_secs(60))
        .pool_idle_timeout(Duration::from_secs(60))
        // o link `/d/…` pode redirecionar para CDN — seguir com limite
        .redirect(wreq::redirect::Policy::limited(8));
    if let Some(ua) = user_agent.map(str::trim).filter(|s| !s.is_empty()) {
        builder = builder.user_agent(ua);
    }
    builder.build()
}

/// Resultado do download — vira JSON para o Kotlin (ver `jni_bridge.rs`).
#[derive(Debug)]
pub struct Outcome {
    pub ok: bool,
    /// HTTP do arquivo (200/206/416/outros).
    pub status: u16,
    /// Bytes efetivamente gravados NESTA chamada.
    pub bytes_written: u64,
    /// Tamanho total do arquivo quando conhecido (Content-Length/Range); -1.
    pub total: i64,
    /// Offset de onde a chamada retomou (append_from informado).
    pub resumed_from: u64,
    /// true se o provedor respondeu com desafio Cloudflare (repetir cedo
    /// não resolve — o Kotlin NÃO deve tentar de novo imediatamente).
    pub challenge: bool,
    /// true se cancelado via [cancel].
    pub canceled: bool,
    pub error: String,
}

impl Outcome {
    fn fail(status: u16, error: impl Into<String>) -> Self {
        Outcome {
            ok: false,
            status,
            bytes_written: 0,
            total: -1,
            resumed_from: 0,
            challenge: false,
            canceled: false,
            error: error.into(),
        }
    }
}

/// Total de um header `Content-Range` ("bytes 100-999/1234" ou "bytes */1234").
fn content_range_total(value: Option<&str>) -> i64 {
    let Some(v) = value else { return -1 };
    let Some(slash) = v.rfind('/') else { return -1 };
    let total = v[slash + 1..].trim();
    if total.is_empty() || total == "*" {
        return -1;
    }
    total.parse::<i64>().unwrap_or(-1)
}

/// Baixa `url` para `dest`:
/// - `append_from == 0` → cria/trunca o arquivo;
/// - `append_from > 0`  → envia `Range: bytes=<append_from>-`; 206 → append;
///   200 (servidor ignorou Range) → reescreve do zero; 416 → devolve o total
///   (o Kotlin decide se o arquivo local já está completo).
///
/// O progresso é publicado em [DL_TOTAL]/[DL_WRITTEN] a cada chunk.
pub async fn download_to_file(
    url: &str,
    dest: &Path,
    append_from: u64,
    cookies: Option<&str>,
    user_agent: Option<&str>,
) -> Outcome {
    DL_CANCEL.store(false, Ordering::SeqCst);
    DL_TOTAL.store(-1, Ordering::SeqCst);
    DL_WRITTEN.store(0, Ordering::SeqCst);

    // Com UA do WebView (plano B) o cliente é construído na hora — cf_clearance
    // é ligado a IP+UA e o UA tem de bater EXATO com o que o Cloudflare viu.
    let owned;
    let client_ref: &Client = match user_agent.map(str::trim).filter(|s| !s.is_empty()) {
        None => client(),
        Some(ua) => match build_client(Some(ua)) {
            Ok(c) => {
                owned = c;
                &owned
            }
            Err(e) => return Outcome::fail(0, format!("cliente: {e}")),
        },
    };

    let mut request = client_ref.get(url);
    if append_from > 0 {
        request = request.header(wreq::header::RANGE, format!("bytes={append_from}-"));
    }
    if let Some(c) = cookies.map(str::trim).filter(|c| !c.is_empty()) {
        request = request.header(wreq::header::COOKIE, c);
    }

    let resp = match request.send().await {
        Ok(r) => r,
        Err(e) => return Outcome::fail(0, format!("conexão: {e}")),
    };
    let status = resp.status().as_u16();
    let headers = resp.headers().clone();

    if crate::buzzheavier::is_challenge(status, &headers, "") {
        return Outcome::fail(status, "verificação de segurança do provedor (Cloudflare)").challenge();
    }

    let total: i64;
    let appending: bool;
    match status {
        206 => {
            total = content_range_total(headers.get("content-range").and_then(|v| v.to_str().ok()));
            appending = append_from > 0;
        }
        200 => {
            total = resp.content_length().map(|l| l as i64).unwrap_or(-1);
            appending = false; // servidor ignorou o Range → reescreve do zero
        }
        416 => {
            // Total vem no Content-Range "bytes */<total>" do 416.
            let t = content_range_total(headers.get("content-range").and_then(|v| v.to_str().ok()));
            return Outcome {
                ok: false,
                status: 416,
                bytes_written: 0,
                total: t,
                resumed_from: append_from,
                challenge: false,
                canceled: false,
                error: "retomada além do fim do arquivo".into(),
            };
        }
        _ => {
            return Outcome::fail(status, format!("HTTP {status} ao baixar o arquivo"));
        }
    }
    DL_TOTAL.store(total, Ordering::SeqCst);

    let mut file = if appending {
        match std::fs::OpenOptions::new().append(true).open(dest) {
            Ok(f) => f,
            Err(e) => return Outcome::fail(status, format!("abrir .part para append: {e}")),
        }
    } else {
        if let Some(parent) = dest.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        match std::fs::File::create(dest) {
            Ok(f) => f,
            Err(e) => return Outcome::fail(status, format!("criar arquivo: {e}")),
        }
    };

    let mut written: u64 = 0;
    let mut stream = std::pin::pin!(resp.bytes_stream());
    use futures_util::StreamExt;
    loop {
        if DL_CANCEL.load(Ordering::SeqCst) {
            // mantém o .part — a próxima chamada retoma de onde parou
            return Outcome {
                ok: false,
                status,
                bytes_written: written,
                total,
                resumed_from: append_from,
                challenge: false,
                canceled: true,
                error: "cancelado".into(),
            };
        }
        match stream.next().await {
            Some(Ok(chunk)) => {
                if let Err(e) = file.write_all(&chunk) {
                    return Outcome::fail(status, format!("gravar no disco: {e}"));
                }
                written += chunk.len() as u64;
                DL_WRITTEN.store(append_from + written, Ordering::SeqCst);
            }
            Some(Err(e)) => {
                // rede caiu no meio: mantém o .part para retomada por Range
                return Outcome::fail(status, format!("fluxo interrompido: {e}"));
            }
            None => break,
        }
    }
    if let Err(e) = file.flush() {
        return Outcome::fail(status, format!("flush: {e}"));
    }

    Outcome {
        ok: true,
        status,
        bytes_written: written,
        total,
        resumed_from: append_from,
        challenge: false,
        canceled: false,
        error: String::new(),
    }
}

impl Outcome {
    fn challenge(mut self) -> Self {
        self.challenge = true;
        self
    }
}

/// Snapshot de progresso — JSON pequeno lido pelo Kotlin a cada ~400 ms:
/// `{"written":123,"total":456}` (total = -1 quando desconhecido).
pub fn progress_json() -> String {
    format!(
        "{{\"written\":{},\"total\":{}}}",
        DL_WRITTEN.load(Ordering::SeqCst),
        DL_TOTAL.load(Ordering::SeqCst)
    )
}

/// Helper de teste interno: outcome "completo" plausível.
#[cfg(test)]
fn sample_ok_outcome() -> Outcome {
    Outcome {
        ok: true,
        status: 200,
        bytes_written: 10,
        total: 10,
        resumed_from: 0,
        challenge: false,
        canceled: false,
        error: String::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn content_range_total_casos() {
        assert_eq!(content_range_total(Some("bytes 100-999/1234")), 1234);
        assert_eq!(content_range_total(Some("bytes */1234")), 1234);
        assert_eq!(content_range_total(Some("bytes */*")), -1);
        assert_eq!(content_range_total(None), -1);
        assert_eq!(content_range_total(Some("lixo")), -1);
    }

    #[test]
    fn progress_json_formato() {
        let json = progress_json();
        assert!(json.starts_with("{\"written\":"));
        assert!(json.contains("\"total\":"));
    }

    #[test]
    fn outcome_fail_preenche_campos() {
        let o = Outcome::fail(403, "bloqueado").challenge();
        assert!(!o.ok);
        assert!(o.challenge);
        assert_eq!(o.status, 403);
        assert_eq!(o.total, -1);
        let _ = sample_ok_outcome();
    }
}
