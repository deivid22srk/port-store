//! Abstração dos dois transportes HTTP do motor:
//!
//! - `Native`  — reqwest/rustls: downloads "comuns" (APK do catálogo, GitHub…).
//! - `Browser` — wreq (emulação Chrome/BoringSSL): downloads **capturados da
//!   WebView** — provedores atrás de Cloudflare validam fingerprint TLS e
//!   ligam `cf_clearance` a IP+UA (ver `provider_download.rs`/`buzzheavier.rs`).
//!
//! Um enum (e não `trait`) porque os dois tipos de `Response` são concretos e
//! diferentes; um `trait` de resposta exigiria GATs/`impl Trait` em objetos —
//! o enum é simples, zero-custo no dispatch e cobre exatamente os 2 casos.
//! Ambos os clientes são `Arc` internamente (clone barato).

use bytes::Bytes;
use futures_util::StreamExt;
use std::pin::Pin;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HttpClient {
    Native(reqwest::Client),
    Browser(wreq::Client),
}

/// Header enviado por nome (lowercase) + valor. Nomes são normalizados aqui
/// para evitar divergência reqwest/wreq.
pub type HeaderList = Vec<(String, String)>;

impl HttpClient {
    pub async fn head(&self, url: &str, headers: &HeaderList) -> Result<HttpResponse, String> {
        match self {
            HttpClient::Native(c) => send_reqwest(c.head(url), headers).await,
            HttpClient::Browser(c) => send_wreq(c.head(url), headers).await,
        }
    }

    pub async fn get(&self, url: &str, headers: &HeaderList) -> Result<HttpResponse, String> {
        match self {
            HttpClient::Native(c) => send_reqwest(c.get(url), headers).await,
            HttpClient::Browser(c) => send_wreq(c.get(url), headers).await,
        }
    }
}

type ReqwestBuilder = reqwest::RequestBuilder;
type WreqBuilder = wreq::RequestBuilder;

fn apply_headers_rb(mut b: ReqwestBuilder, headers: &HeaderList) -> ReqwestBuilder {
    for (k, v) in headers {
        b = b.header(k.as_str(), v.as_str());
    }
    b
}

fn apply_headers_wb(mut b: WreqBuilder, headers: &HeaderList) -> WreqBuilder {
    for (k, v) in headers {
        b = b.header(k.as_str(), v.as_str());
    }
    b
}

async fn send_reqwest(b: ReqwestBuilder, headers: &HeaderList) -> Result<HttpResponse, String> {
    let resp = apply_headers_rb(b, headers)
        .send()
        .await
        .map_err(|e| e.to_string())?;
    Ok(HttpResponse::from_reqwest(resp))
}

async fn send_wreq(b: WreqBuilder, headers: &HeaderList) -> Result<HttpResponse, String> {
    let resp = apply_headers_wb(b, headers)
        .send()
        .await
        .map_err(|e| e.to_string())?;
    Ok(HttpResponse::from_wreq(resp))
}

/// Resposta normalizada: status, headers relevantes e fluxo de bytes.
/// O stream é box-pinnado na construção — `next_chunk` é o único consumo.
pub struct HttpResponse {
    status: u16,
    headers: Vec<(String, String)>,
    content_length: Option<u64>,
    stream: Pin<Box<dyn futures_util::Stream<Item = Result<Bytes, String>> + Send>>,
}

impl HttpResponse {
    pub fn status(&self) -> u16 {
        self.status
    }

    /// Valor do header (case-insensitive, primeiro occurrence).
    pub fn header(&self, name: &str) -> Option<String> {
        let name = name.to_ascii_lowercase();
        self.headers
            .iter()
            .find(|(k, _)| *k == name)
            .map(|(_, v)| v.clone())
    }

    pub fn content_length(&self) -> Option<u64> {
        self.content_length
    }

    /// `Retry-After` em segundos (formato numérico; formato-data não é
    /// suportado — servidores que o usam caem no backoff normal).
    pub fn retry_after(&self) -> Option<std::time::Duration> {
        self.header("retry-after")
            .and_then(|v| v.trim().parse::<u64>().ok())
            .map(std::time::Duration::from_secs)
    }

    /// Total de um `Content-Range: bytes X-Y/TOTAL` (0-0 do probe).
    pub fn content_range_total(&self) -> Option<u64> {
        self.header("content-range")
            .as_deref()
            .and_then(crate::util::parse_content_range_total)
    }

    /// Próximo chunk do corpo. `None` = fim do stream;
    /// `Some(Err)` = rede caiu no meio (o offset atual é recuperável).
    pub async fn next_chunk(&mut self) -> Option<Result<Bytes, String>> {
        self.stream.next().await
    }

    fn from_reqwest(resp: reqwest::Response) -> Self {
        let status = resp.status().as_u16();
        let headers = snapshot(resp.headers().iter());
        let content_length = resp.content_length();
        let stream = Box::pin(resp.bytes_stream().map(|r| r.map_err(|e| e.to_string())));
        HttpResponse { status, headers, content_length, stream }
    }

    fn from_wreq(resp: wreq::Response) -> Self {
        let status = resp.status().as_u16();
        let headers = snapshot(resp.headers().iter());
        let content_length = resp.content_length();
        let stream = Box::pin(resp.bytes_stream().map(|r| r.map_err(|e| e.to_string())));
        HttpResponse { status, headers, content_length, stream }
    }
}

/// Snapshot dos headers como pares lowercase (o suficiente para o motor;
/// nunca exposto à UI — pode conter cookies). Genérico para não acoplar os
/// tipos de header de reqwest e wreq (versões do crate `http` podem divergir).
fn snapshot<I, K, V>(it: I) -> Vec<(String, String)>
where
    I: IntoIterator<Item = (K, V)>,
    K: AsRef<str>,
    V: AsRef<[u8]>,
{
    it.into_iter()
        .filter_map(|(k, v)| {
            let key = k.as_ref().to_ascii_lowercase();
            let val = std::str::from_utf8(v.as_ref()).ok()?.to_string();
            Some((key, val))
        })
        .collect()
}
