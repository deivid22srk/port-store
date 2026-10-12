//! Ponte WebView ↔ Rust: contrato tipado das capturas de download.
//!
//! Fluxo (espelha o navegador embutido do Download Navi, mas capturando o
//! contexto HTTP completo que o Navi descartava): o usuário toca no botão de
//! download da página na WebView do app; o Kotlin intercepta
//! (`DownloadListener`) e entrega URL final, cookies da sessão
//! (`CookieManager`), User-Agent, Referer/Origin e headers extra ao motor —
//! que baixa com multipartes, retomada, retry e verificação de hash.
//!
//! SEGURANÇA (toda entrada vem de uma página web — é hostil por definição):
//! - `validate()` impõe limites de tamanho/tipo em TODOS os campos;
//! - `file_name` nunca compõe caminho — passa por `sanitize_file_name`;
//! - headers controlados pelo motor (Range, Cookie, Content-Length,
//!   Connection…) são rejeitados em `extra_headers`;
//! - `game_id` é restringido a `[A-Za-z0-9._-]` (compõe caminhos no Kotlin).

use crate::error::DownloadError;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Limites do contrato (defesa contra captura maliciosa/exagerada).
pub const MAX_URL_LEN: usize = 2048;
pub const MAX_COOKIES: usize = 64;
pub const MAX_COOKIE_NAME: usize = 128;
pub const MAX_COOKIE_VALUE: usize = 4096;
pub const MAX_EXTRA_HEADERS: usize = 32;
pub const MAX_HEADER_VALUE: usize = 2048;
pub const MAX_USER_AGENT: usize = 512;
pub const MAX_CONTENT_LENGTH: u64 = 512 * 1024 * 1024 * 1024; // sanidade: 512 GiB

/// Um cookie capturado da sessão da WebView (`CookieManager`).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default, rename_all = "camelCase")]
pub struct Cookie {
    pub name: String,
    pub value: String,
}

impl Default for Cookie {
    fn default() -> Self {
        Cookie { name: String::new(), value: String::new() }
    }
}

/// Hash publicado pela página (quando existir) para verificação pós-download.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default, rename_all = "camelCase")]
pub struct ExpectedHash {
    /// "sha256" | "md5"
    pub algorithm: String,
    pub hex: String,
}

impl Default for ExpectedHash {
    fn default() -> Self {
        ExpectedHash { algorithm: "sha256".into(), hex: String::new() }
    }
}

/// Transporte do download capturado.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Transport {
    /// reqwest/rustls — hosts sem proteção por fingerprint.
    Native,
    /// wreq/emulação Chrome — padrão para capturas (provedores com Cloudflare).
    Browser,
}

/// Sessão HTTP de um download capturado (imutável durante o job).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CaptureSession {
    pub user_agent: String,
    /// Header `Cookie` pronto ("a=b; c=d") ou None.
    pub cookies: Option<String>,
    pub referer: Option<String>,
    pub extra_headers: Vec<(String, String)>,
    pub transport: Transport,
}

/// Contrato completo da captura — JSON camelCase enviado pelo Kotlin via JNI.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(default, rename_all = "camelCase")]
pub struct CapturedDownload {
    /// Jogo ao qual o arquivo pertence (id do catálogo — compõe caminhos).
    pub game_id: String,
    /// URL final do arquivo (após redirecionamentos da página).
    pub url: String,
    /// Nome sugerido (Content-Disposition/URL). NUNCA confiado como caminho.
    pub file_name: Option<String>,
    pub mime_type: Option<String>,
    pub content_length: Option<u64>,
    /// UA EXATO da WebView (cf_clearance é ligado a IP+UA).
    pub user_agent: String,
    pub referer: Option<String>,
    /// Origin da página (fallback de referer quando a página não envia).
    pub origin: Option<String>,
    pub cookies: Vec<Cookie>,
    pub extra_headers: HashMap<String, String>,
    pub expected_hash: Option<ExpectedHash>,
    /// "native" | "browser" (padrão: browser).
    pub transport: Option<String>,
}

/// Header controlado pelo motor — jamais aceito do exterior.
const FORBIDDEN_HEADERS: &[&str] = &[
    "host",
    "content-length",
    "content-range",
    "range",
    "if-range",
    "if-match",
    "cookie",
    "connection",
    "accept-encoding",
    "transfer-encoding",
    "expect",
    "upgrade",
];

/// Nome de header/cookie válido (token RFC 7230 simplificado).
fn is_token(s: &str) -> bool {
    !s.is_empty()
        && s.bytes().all(|b| {
            b.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&b)
        })
}

impl CapturedDownload {
    /// Validação completa. Toda violação vira `InvalidCapture` (fatal —
    /// repetir não conserta uma captura malformada).
    pub fn validate(&self) -> Result<(), DownloadError> {
        let bad = |what: &str| Err(DownloadError::InvalidCapture(what.to_string()));

        // game_id: 1..64 chars seguros (compõe caminho no Kotlin)
        let gid_ok = !self.game_id.is_empty()
            && self.game_id.len() <= 64
            && self
                .game_id
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'));
        if !gid_ok {
            return bad("game_id");
        }

        // URL: http(s), com host, tamanho limitado
        if self.url.is_empty() || self.url.len() > MAX_URL_LEN {
            return bad("url");
        }
        let parsed = reqwest::Url::parse(&self.url).map_err(|_| {
            DownloadError::InvalidCapture("url".into())
        })?;
        if !matches!(parsed.scheme(), "http" | "https") {
            return bad("esquema da url");
        }
        if parsed.host_str().map_or(true, str::is_empty) {
            return bad("host da url");
        }

        // user-agent
        if self.user_agent.len() > MAX_USER_AGENT {
            return bad("user_agent");
        }

        // cookies
        if self.cookies.len() > MAX_COOKIES {
            return bad("cookies: excesso");
        }
        for c in &self.cookies {
            if !is_token(&c.name) || c.name.len() > MAX_COOKIE_NAME {
                return bad("nome de cookie");
            }
            if c.value.len() > MAX_COOKIE_VALUE {
                return bad("valor de cookie");
            }
        }

        // headers extra: limite + proibidos + valores controlados
        if self.extra_headers.len() > MAX_EXTRA_HEADERS {
            return bad("headers: excesso");
        }
        for (name, value) in &self.extra_headers {
            if !is_token(name) || name.len() > MAX_COOKIE_NAME {
                return bad("nome de header");
            }
            if FORBIDDEN_HEADERS.contains(&name.to_ascii_lowercase().as_str()) {
                return bad("header controlado pelo motor");
            }
            if value.len() > MAX_HEADER_VALUE || value.chars().any(|c| c.is_control() && c != '\t') {
                return bad("valor de header");
            }
        }

        // mime
        if let Some(m) = &self.mime_type {
            if m.len() > 128 || m.chars().any(|c| c.is_control()) {
                return bad("mime_type");
            }
        }

        // content-length de sanidade
        if let Some(n) = self.content_length {
            if n > MAX_CONTENT_LENGTH {
                return bad("content_length");
            }
        }

        // hash esperado
        if let Some(h) = &self.expected_hash {
            let algo = match h.algorithm.to_ascii_lowercase().as_str() {
                "sha256" | "sha-256" => crate::integrity::HashAlgo::Sha256,
                "md5" => crate::integrity::HashAlgo::Md5,
                _ => return bad("algoritmo de hash"),
            };
            if !crate::integrity::is_valid_digest(&h.hex, algo) {
                return bad("hex de hash");
            }
        }

        Ok(())
    }

    /// Nome de arquivo sanitizado (nunca compõe caminho; sempre nome base).
    pub fn safe_file_name(&self) -> String {
        let raw = self
            .file_name
            .as_deref()
            .filter(|s| !s.trim().is_empty())
            .unwrap_or("dados-complementares.bin");
        crate::integrity::sanitize_file_name(raw, "dados-complementares.bin")
    }

    /// Sessão HTTP derivada da captura validada.
    pub fn session(&self) -> CaptureSession {
        let transport = match self.transport.as_deref() {
            Some("native") => Transport::Native,
            _ => Transport::Browser, // padrão: provedores costumam ter Cloudflare
        };
        let referer = self
            .referer
            .as_deref()
            .filter(|s| !s.trim().is_empty())
            .map(str::to_string)
            .or_else(|| {
                self.origin
                    .as_deref()
                    .filter(|s| !s.trim().is_empty())
                    .map(str::to_string)
            });
        CaptureSession {
            user_agent: if self.user_agent.trim().is_empty() {
                crate::UA_FALLBACK.to_string()
            } else {
                self.user_agent.trim().to_string()
            },
            cookies: cookie_header(&self.cookies),
            referer,
            extra_headers: self
                .extra_headers
                .iter()
                .map(|(k, v)| (k.clone(), v.clone()))
                .collect(),
            transport,
        }
    }

    /// Hash normalizado (algoritmo canônico + hex lower) quando presente.
    pub fn normalized_hash(&self) -> Option<(crate::integrity::HashAlgo, String)> {
        let h = self.expected_hash.as_ref()?;
        match h.algorithm.to_ascii_lowercase().as_str() {
            "sha256" | "sha-256" => Some((crate::integrity::HashAlgo::Sha256, h.hex.to_lowercase())),
            "md5" => Some((crate::integrity::HashAlgo::Md5, h.hex.to_lowercase())),
            _ => None,
        }
    }
}

/// `Vec<Cookie>` → header `Cookie` ("a=b; c=d"). None quando vazio.
pub fn cookie_header(cookies: &[Cookie]) -> Option<String> {
    if cookies.is_empty() {
        return None;
    }
    let joined = cookies
        .iter()
        .map(|c| format!("{}={}", c.name, c.value))
        .collect::<Vec<_>>()
        .join("; ");
    if joined.is_empty() {
        None
    } else {
        Some(joined)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid_capture() -> CapturedDownload {
        CapturedDownload {
            game_id: "skate-3".into(),
            url: "https://cdn.example.com/files/skate3_data.bin".into(),
            file_name: Some("skate3_data.bin".into()),
            mime_type: Some("application/octet-stream".into()),
            content_length: Some(1_479_401_843),
            user_agent: "Mozilla/5.0 (Windows NT 10.0) Chrome/124.0.0.0".into(),
            referer: Some("https://page.example.com/dl".into()),
            origin: Some("https://page.example.com".into()),
            cookies: vec![
                Cookie { name: "cf_clearance".into(), value: "abc123".into() },
                Cookie { name: "session".into(), value: "xyz".into() },
            ],
            extra_headers: HashMap::from([("X-Requested-With".to_string(), "com.android.webview".to_string())]),
            expected_hash: Some(ExpectedHash {
                algorithm: "sha256".into(),
                hex: "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".into(),
            }),
            transport: None,
        }
    }

    #[test]
    fn captura_valida_passa_e_deriva_sessao() {
        let c = valid_capture();
        c.validate().unwrap();
        let s = c.session();
        assert_eq!(s.transport, Transport::Browser, "padrão é browser");
        assert_eq!(
            s.cookies.as_deref(),
            Some("cf_clearance=abc123; session=xyz")
        );
        assert!(s.user_agent.contains("Chrome/124"));
        assert_eq!(s.referer.as_deref(), Some("https://page.example.com/dl"));
        assert_eq!(c.safe_file_name(), "skate3_data.bin");
    }

    #[test]
    fn violacoes_fatais() {
        let mut c = valid_capture();
        c.game_id = "../etc".into();
        assert!(c.validate().is_err());

        let mut c = valid_capture();
        c.url = "file:///etc/passwd".into();
        assert!(c.validate().is_err());

        let mut c = valid_capture();
        c.url = "javascript:alert(1)".into();
        assert!(c.validate().is_err());

        let mut c = valid_capture();
        c.url = "notaurl".into();
        assert!(c.validate().is_err());

        let mut c = valid_capture();
        c.cookies.push(Cookie { name: "com espaços".into(), value: "x".into() });
        assert!(c.validate().is_err());

        let mut c = valid_capture();
        c.extra_headers.insert("Range".into(), "bytes=0-".into());
        assert!(c.validate().is_err(), "Range é header do motor");

        let mut c = valid_capture();
        c.extra_headers.insert("Cookie".into(), "a=b".into());
        assert!(c.validate().is_err());

        let mut c = valid_capture();
        c.extra_headers.insert("Host".into(), "evil.com".into());
        assert!(c.validate().is_err());

        let mut c = valid_capture();
        c.expected_hash = Some(ExpectedHash { algorithm: "crc32".into(), hex: "1234".into() });
        assert!(c.validate().is_err());

        let mut c = valid_capture();
        c.expected_hash = Some(ExpectedHash { algorithm: "md5".into(), hex: "zz".into() });
        assert!(c.validate().is_err());

        let mut c = valid_capture();
        c.content_length = Some(u64::MAX);
        assert!(c.validate().is_err());

        let mut c = valid_capture();
        c.user_agent = "a".repeat(MAX_USER_AGENT + 1);
        assert!(c.validate().is_err());
    }

    #[test]
    fn nome_de_arquivo_malicioso_e_sanitizado() {
        let mut c = valid_capture();
        c.file_name = Some("../../../../data/data/app/dados.tar.gz".into());
        assert_eq!(c.safe_file_name(), "dados.tar.gz");
        c.file_name = Some("".into());
        assert_eq!(c.safe_file_name(), "dados-complementares.bin");
        c.file_name = None;
        assert_eq!(c.safe_file_name(), "dados-complementares.bin");
    }

    #[test]
    fn referer_cai_para_origin() {
        let mut c = valid_capture();
        c.referer = None;
        assert_eq!(c.session().referer.as_deref(), Some("https://page.example.com"));
        c.origin = None;
        assert_eq!(c.session().referer, None);
    }

    #[test]
    fn transporte_native_aceito_e_ua_padrao() {
        let mut c = valid_capture();
        c.transport = Some("native".into());
        assert_eq!(c.session().transport, Transport::Native);
        c.user_agent = String::new();
        assert_eq!(c.session().user_agent, crate::UA_FALLBACK);
    }

    #[test]
    fn serde_roundtrip_camel_case() {
        let c = valid_capture();
        let json = serde_json::to_string(&c).unwrap();
        assert!(json.contains("\"gameId\""));
        assert!(json.contains("\"expectedHash\""));
        assert!(json.contains("\"cf_clearance\""));
        let back: CapturedDownload = serde_json::from_str(&json).unwrap();
        assert_eq!(back, c);
        // JSON mínimo também parseia (todos os campos têm default)
        let minimal: CapturedDownload = serde_json::from_str(r#"{"gameId":"x","url":"https://a/b"}"#).unwrap();
        minimal.validate().unwrap();
    }

    #[test]
    fn hash_normalizado() {
        let c = valid_capture();
        let (algo, hex) = c.normalized_hash().unwrap();
        assert_eq!(algo, crate::integrity::HashAlgo::Sha256);
        assert_eq!(hex, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
