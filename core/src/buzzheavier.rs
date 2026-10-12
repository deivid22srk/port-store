//! Resolução do link direto do Buzzheavier.
//!
//! O repositório de dados (`port-store-data`) passou a hospedar apenas
//! LINKS (index.json v2): a entrada do jogo aponta para uma PÁGINA do
//! provedor (ex.: `https://buzzheavier.com/<id>`), e não para o arquivo.
//! Este módulo extrai o link direto (path `/d/…`) para o motor baixar o
//! pacote — a extração roda ANTES do probe/download, igual ao MediaFire
//! (`mediafire.rs`).
//!
//! Fluxo (referência: github.com/VolcharaVasiliy/buzzheavier-tool):
//!  1. GET `https://{host}/{id}` — o HTML contém o ticket de download em
//!     `download?t=<token>` (regex do guia: `download\?t=([^"'&\\#]+)`);
//!  2. GET `https://{host}/{id}/download?t=<token>` SEM seguir redirects e
//!     com os cabeçalhos hx-* (htmx): a resposta traz o link direto no
//!     header `Hx-Redirect` (fallback: `Location` ou corpo JSON com
//!     `download_url`); o caminho DEVE começar com `/d/` — outra coisa é
//!     página de espera/anúncio;
//!  3. fallback: mesmas tentativas com `alt=true`, re-buscando o token
//!     (2 tentativas padrão + 5 alternativas, como no guia);
//!  4. espelhos: `buzzheavier.com`, `bzzhr.co`, `fuckingfast.net` — o host
//!     da URL é tentado primeiro; falhas de Cloudflare pulam para o próximo.
//!
//! Cloudflare: resposta com header `cf-mitigated: challenge` ou página
//! "Just a moment…" vira erro [BuzzError::Challenge] — repetir cedo não
//! resolve; a mensagem orienta o usuário. Sem dependências novas: o casamento
//! de padrões é feito à mão sobre bytes ASCII (mesmo estilo do `mediafire.rs`).
//!
//! ⚠️ TRANSPORTE: as requisições usam **wreq com emulação Chrome**
//! (BoringSSL + fingerprint TLS/JA4/HTTP-2 de navegador real). O Cloudflare
//! do provedor DESAFIA clientes com fingerprint rustls/hyper (403 challenge)
//! MESMO em IP residencial — a ferramenta de referência (buzzheavier-tool)
//! só passa porque usa `curl_cffi` com `impersonate="chrome"`; este módulo
//! é o equivalente Rust disso. Diagnóstico: cada falha de espelho é logada
//! com o motivo (status/erro) para o logcat.
//!
//! ⚠️ PLANO B (empírico 2026-10): mesmo com emulação o Cloudflare do aparelho
//! do usuário desafiou TODOS os espelhos (403 challenge). A saída é o plano
//! de contorno do app: um WebView REAL (mesmo motor do Chrome do aparelho)
//! carrega a página, passa a verificação e devolve os cookies
//! (`cf_clearance`) + o User-Agent EXATO do WebView; como `cf_clearance` é
//! ligado a IP+UA, TODAS as requisições aqui re-aplicam esses valores
//! (`clients_for`): o cliente continua com fingerprint de Chrome, mas o UA
//! vira exatamente o que o Cloudflare viu no WebView.

use wreq::Client;
use wreq::header::{HeaderMap, HeaderValue};
use wreq_util::Emulation;
use std::time::Duration;

/// Erros da resolução do Buzzheavier.
#[derive(Debug)]
pub enum BuzzError {
    /// Rede/HTTP transiente — pode valer nova tentativa.
    Network(String),
    /// Verificação de segurança do provedor (Cloudflare) — repetir cedo
    /// não resolve; trocar de mirror/rede pode.
    Challenge,
    /// Página ok, mas sem link direto (arquivo removido ou HTML mudou) —
    /// repetir não resolve.
    NotFound(String),
}

impl BuzzError {
    pub fn kind(&self) -> &'static str {
        match self {
            BuzzError::Network(_) => "network",
            BuzzError::Challenge => "challenge",
            BuzzError::NotFound(_) => "notfound",
        }
    }

    pub fn message(&self) -> String {
        match self {
            BuzzError::Network(e) => format!("falha de rede ao contatar o provedor: {e}"),
            BuzzError::Challenge => {
                "o provedor está pedindo verificação de segurança (Cloudflare). Tente novamente em instantes ou use outra conexão.".into()
            }
            BuzzError::NotFound(e) => {
                format!("não foi possível extrair o link direto ({e}). Arquivo removido ou provedor mudou a página.")
            }
        }
    }
}

/// User-Agent de DESKTOP (mantido para o `resolve_check`/diagnóstico — os
/// clientes de verdade usam o UA do próprio navegador emulado pelo wreq).
pub const DESKTOP_UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

/// Espelhos conhecidos do provedor (referência: SITE_HOSTS do buzzheavier-tool).
pub const HOSTS: [&str; 3] = ["buzzheavier.com", "bzzhr.co", "fuckingfast.net"];

/// Limite de tempo total da resolução (todas as tentativas + espelhos).
const RESOLVE_TIMEOUT: Duration = Duration::from_secs(150);

const ACCEPT_HTML: &str = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

/// Par de clientes usados numa resolução: (segue redirects, NÃO segue).
/// Ambos com EMULAÇÃO Chrome (fingerprint TLS/HTTP-2 de navegador — ver topo).
struct Clients {
    page: Client,
    nodirect: Client,
}

fn build_client(policy: wreq::redirect::Policy, user_agent: Option<&str>) -> wreq::Result<Client> {
    let mut builder = Client::builder()
        .emulation(Emulation::Chrome147)
        .connect_timeout(Duration::from_secs(15))
        .read_timeout(Duration::from_secs(30))
        .pool_idle_timeout(Duration::from_secs(60))
        .redirect(policy);
    // cf_clearance do WebView é ligado ao UA — sobrescreve o UA da emulação
    // pelo EXATO que o Cloudflare viu no navegador (ver topo do arquivo).
    if let Some(ua) = user_agent.map(str::trim).filter(|s| !s.is_empty()) {
        builder = builder.user_agent(ua);
    }
    builder.build()
}

/// Clientes para UMA resolução: os compartilhados (sem UA custom) ou um par
/// novo com o UA do WebView. Construir 2 clientes é barato e só acontece no
/// plano B (challenge → WebView).
fn clients_for(user_agent: Option<&str>) -> wreq::Result<Clients> {
    let ua = user_agent.map(str::trim).filter(|s| !s.is_empty());
    Ok(Clients {
        page: build_client(wreq::redirect::Policy::limited(10), ua)?,
        nodirect: build_client(wreq::redirect::Policy::none(), ua)?,
    })
}

// ======================================================================
// Detecção e parsing de URL
// ======================================================================

/// true se a URL é uma PÁGINA do provedor (precisa de resolução antes do
/// download). Links diretos (`/d/…`) e outros hosts retornam false.
pub fn is_buzzheavier_page(url: &str) -> bool {
    let host = match host_of(url) {
        Some(h) => h.to_ascii_lowercase(),
        None => return false,
    };
    let known = HOSTS
        .iter()
        .any(|h| host == *h || host.ends_with(&format!(".{h}")));
    if !known {
        return false;
    }
    match path_of(url) {
        Some(p) => !p.starts_with("/d/"),
        None => true,
    }
}

/// Host de uma URL (sem esquema, caminho, query e userinfo).
fn host_of(url: &str) -> Option<&str> {
    let rest = url.split("://").nth(1)?;
    let mut host = rest.split(['/', '?', '#']).next()?;
    if let Some(at) = host.rfind('@') {
        host = &host[at + 1..];
    }
    if host.is_empty() {
        None
    } else {
        Some(host)
    }
}

/// Path de uma URL (começa no primeiro `/` após o host).
fn path_of(url: &str) -> Option<&str> {
    let rest = url.split("://").nth(1)?;
    let after_host = rest.split(['?', '#']).next()?;
    after_host.find('/').map(|i| &after_host[i..])
}

/// Extrai o id do arquivo da página (primeiro segmento do path).
/// Cobre os formatos do provedor: `/{id}`, `/f/{id}.html` e o id puro
/// (mesma tolerância do guia — `parse_url_or_id`).
pub fn file_id_from_url(url: &str) -> Option<String> {
    let trimmed = url.trim();
    if !trimmed.contains("://") {
        // id puro (ou caminho relativo): primeira parte até ?/#
        let seg = trimmed
            .split(['/', '?', '#'])
            .find(|s| !s.is_empty())?;
        return Some(strip_html_ext(seg));
    }
    let path = path_of(trimmed)?;
    let mut segs = path.split('/').filter(|s| !s.is_empty());
    let first = segs.next()?;
    if first.eq_ignore_ascii_case("f") {
        let second = segs.next()?;
        Some(strip_html_ext(second))
    } else {
        Some(strip_html_ext(first))
    }
}

fn strip_html_ext(seg: &str) -> String {
    seg.trim_end_matches(".html")
        .trim_end_matches(".htm")
        .to_string()
}

// ======================================================================
// Extração do token e do link (funções puras — testáveis sem rede)
// ======================================================================

/// Token do ticket de download: `download?t=<token>` no HTML da página
/// (termina em `"`, `'`, `&`, `\`, `#`, `<`, `>`, `)` ou espaço).
pub fn extract_token(html: &str) -> Option<String> {
    const NEEDLE: &str = "download?t=";
    let mut from = 0usize;
    while let Some(rel) = html[from..].find(NEEDLE) {
        let start = from + rel + NEEDLE.len();
        let bytes = html.as_bytes();
        let mut end = start;
        while end < bytes.len() {
            let b = bytes[end];
            let c = bytes[end] as char;
            if matches!(b, b'"' | b'\'' | b'&' | b'\\' | b'#' | b'<' | b'>' | b')')
                || c.is_ascii_whitespace()
            {
                break;
            }
            end += 1;
        }
        if end > start {
            return Some(html[start..end].to_string());
        }
        from = start;
    }
    None
}

/// Valida o candidato ao link direto: URL absoluta http(s), host que não
/// seja de anúncio (fafda.to) e path `/d/…` (o arquivo de verdade).
/// Desfaz entidades HTML (`&amp;` → `&`) — links com query quebrariam.
pub fn validate_candidate(redirect: &str) -> Option<String> {
    let r = unescape_html(redirect.trim());
    let rest = r.split("://").nth(1)?;
    if !r.starts_with("http://") && !r.starts_with("https://") {
        return None;
    }
    let (host_part, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, ""),
    };
    let host = host_part.rsplit('@').next().unwrap_or(host_part);
    if host.is_empty() {
        return None;
    }
    let host = host.to_ascii_lowercase();
    if host == "fafda.to" || host.ends_with(".fafda.to") {
        return None; // redirect de anúncio (ver guia)
    }
    if !path.starts_with("/d/") {
        return None; // aponta para página, não para o arquivo
    }
    Some(r)
}

/// Junta um candidato relativo (`/d/…`) ao host do provedor.
pub fn absolute(host: &str, candidate: &str) -> String {
    if candidate.starts_with("http://") || candidate.starts_with("https://") {
        candidate.to_string()
    } else if candidate.starts_with('/') {
        format!("https://{host}{candidate}")
    } else {
        candidate.to_string()
    }
}

/// `download_url` em corpos JSON do provedor (defensivo — o fluxo oficial
/// devolve o link no header `Hx-Redirect`).
pub fn json_download_url(body: &str) -> Option<String> {
    const KEY: &str = "\"download_url\"";
    let idx = body.find(KEY)?;
    let rest = &body[idx + KEY.len()..];
    let colon = rest.find(':')?;
    let after = rest[colon + 1..].trim_start();
    let after = after.strip_prefix('"')?;
    let end = after.find('"')?;
    let url = &after[..end];
    if url.is_empty() {
        None
    } else {
        Some(url.to_string())
    }
}

/// Detecta a verificação do Cloudflare (header oficial ou marcadores da
/// página "Just a moment…"). `pub(crate)` — reusado pelo download do pacote
/// (`provider_download.rs`).
pub(crate) fn is_challenge(status: u16, headers: &HeaderMap, body: &str) -> bool {
    if headers
        .get("cf-mitigated")
        .and_then(|v| v.to_str().ok())
        .map(|v| v.contains("challenge"))
        .unwrap_or(false)
    {
        return true;
    }
    if status == 403 || status == 503 {
        return body.contains("Just a moment")
            || body.contains("challenge-platform")
            || body.contains("cf-chl");
    }
    false
}

fn unescape_html(v: &str) -> String {
    v.replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}

// ======================================================================
// Resolução (rede)
// ======================================================================

/// Baixa a página e extrai o link direto. Tenta o host da própria URL
/// primeiro e depois os outros espelhos; Cloudflare em um espelho não
/// impede a tentativa no seguinte.
pub async fn resolve_direct_link(
    page_url: &str,
    cookies: Option<&str>,
    user_agent: Option<&str>,
) -> Result<String, BuzzError> {
    let fut = async {
        let clients = clients_for(user_agent)
            .map_err(|e| BuzzError::Network(format!("cliente: {e}")))?;
        resolve_impl(&clients, page_url, cookies).await
    };
    match tokio::time::timeout(RESOLVE_TIMEOUT, fut).await {
        Ok(r) => r,
        Err(_) => Err(BuzzError::Network("tempo esgotado ao resolver o link".into())),
    }
}

async fn resolve_impl(
    clients: &Clients,
    page_url: &str,
    cookies: Option<&str>,
) -> Result<String, BuzzError> {
    let id = file_id_from_url(page_url)
        .ok_or_else(|| BuzzError::NotFound("URL sem id de arquivo".into()))?;
    let url_host = host_of(page_url)
        .map(|h| h.to_ascii_lowercase())
        .unwrap_or_default();

    // host da URL primeiro (quando conhecido), depois os demais espelhos
    let mut hosts: Vec<&'static str> = Vec::with_capacity(HOSTS.len());
    if let Some(pos) = HOSTS
        .iter()
        .position(|h| url_host == *h || url_host.ends_with(&format!(".{h}")))
    {
        hosts.push(HOSTS[pos]);
    }
    for h in HOSTS.iter() {
        if !hosts.contains(h) {
            hosts.push(h);
        }
    }

    let mut saw_challenge = false;
    let mut last: Option<BuzzError> = None;
    for host in hosts {
        log::info!("buzzheavier: resolvendo {id} no espelho {host}");
        match resolve_on_host(clients, host, &id, cookies).await {
            Ok(direct) => {
                log::info!("buzzheavier: link direto obtido via {host}");
                return Ok(direct);
            }
            Err(e) => {
                // Diagnóstico no logcat: qual espelho falhou e POR QUÊ.
                log::warn!(
                    "buzzheavier: espelho {host} falhou (kind={}): {}",
                    e.kind(),
                    truncate_log(&e.message(), 240)
                );
                match e {
                    BuzzError::Challenge => {
                        saw_challenge = true;
                        last = Some(BuzzError::Challenge);
                    }
                    other => last = Some(other),
                }
            }
        }
    }
    if saw_challenge {
        return Err(BuzzError::Challenge);
    }
    Err(last.unwrap_or_else(|| BuzzError::NotFound("sem resposta dos provedores".into())))
}

/// Encurta mensagens para o log (páginas de erro podem vir inteiras).
fn truncate_log(s: &str, max: usize) -> String {
    let compact: String = s.chars().map(|c| if c.is_control() { ' ' } else { c }).collect();
    let compact = compact.trim();
    if compact.chars().count() <= max {
        compact.to_string()
    } else {
        let cut: String = compact.chars().take(max).collect();
        format!("{cut}…")
    }
}

/// Fluxo completo em UM espelho: página → token → 2 tentativas padrão →
/// 5 tentativas `alt=true` (re-buscando o token), como no guia.
async fn resolve_on_host(
    clients: &Clients,
    host: &str,
    id: &str,
    cookies: Option<&str>,
) -> Result<String, BuzzError> {
    let page_url = format!("https://{host}/{id}");

    // ---- 1. página do arquivo → token ----
    let resp = clients
        .page
        .get(&page_url)
        .header(wreq::header::ACCEPT, ACCEPT_HTML)
        .apply_cookies(cookies)
        .send()
        .await
        .map_err(|e| BuzzError::Network(format!("página: {e}")))?;
    let status = resp.status().as_u16();
    let headers = resp.headers().clone();
    let body = resp
        .text()
        .await
        .map_err(|e| BuzzError::Network(format!("página (corpo): {e}")))?;
    if is_challenge(status, &headers, &body) {
        return Err(BuzzError::Challenge);
    }
    if status == 404 || status == 410 {
        return Err(BuzzError::NotFound("arquivo não encontrado (HTTP 404)".into()));
    }
    if !(200..300).contains(&status) {
        return Err(BuzzError::Network(format!("página: HTTP {status}")));
    }
    let mut token = extract_token(&body);

    // ---- 2. tentativas padrão (sem alt) ----
    let mut last_err = String::from("sem resposta válida do provedor");
    for attempt in 1..=2u32 {
        if attempt > 1 {
            tokio::time::sleep(Duration::from_millis(500 * attempt as u64)).await;
        }
        match request_redirect(clients, host, id, token.as_deref(), false, cookies).await {
            Ok(url) => return Ok(url),
            Err(BuzzError::Challenge) => return Err(BuzzError::Challenge),
            Err(e) => last_err = e.message(),
        }
    }

    // ---- 3. fallback alt=true (re-busca o token a cada tentativa) ----
    for attempt in 1..=5u32 {
        if attempt > 1 {
            tokio::time::sleep(Duration::from_millis(350 * attempt as u64)).await;
        }
        if let Some(t) = fetch_token(clients, host, id, cookies).await {
            token = Some(t);
        }
        match request_redirect(clients, host, id, token.as_deref(), true, cookies).await {
            Ok(url) => return Ok(url),
            Err(BuzzError::Challenge) => return Err(BuzzError::Challenge),
            Err(e) => last_err = e.message(),
        }
    }
    Err(BuzzError::NotFound(last_err))
}

/// Re-busca o token da página (fallback alt; ver fetch_download_token do guia).
async fn fetch_token(
    clients: &Clients,
    host: &str,
    id: &str,
    cookies: Option<&str>,
) -> Option<String> {
    let resp = clients
        .page
        .get(&format!("https://{host}/{id}"))
        .header(wreq::header::ACCEPT, ACCEPT_HTML)
        .apply_cookies(cookies)
        .send()
        .await
        .ok()?;
    if resp.status().as_u16() != 200 {
        return None;
    }
    let body = resp.text().await.ok()?;
    extract_token(&body)
}

/// GET `/{id}/download?t=<token>` SEM seguir redirects; extrai o link do
/// header `Hx-Redirect` (ou `Location`, ou corpo JSON). Ver request_download_
/// redirect + resolve_download_target do guia.
async fn request_redirect(
    clients: &Clients,
    host: &str,
    id: &str,
    token: Option<&str>,
    alt: bool,
    cookies: Option<&str>,
) -> Result<String, BuzzError> {
    let mut url = format!("https://{host}/{id}/download");
    let mut params: Vec<String> = Vec::with_capacity(2);
    if let Some(t) = token {
        params.push(format!("t={t}"));
    }
    if alt {
        params.push("alt=true".into());
    }
    if !params.is_empty() {
        url.push('?');
        url.push_str(&params.join("&"));
    }
    let page_url = format!("https://{host}/{id}");

    let resp = clients
        .nodirect
        .get(&url)
        .header("hx-current-url", HeaderValue::from_str(&page_url).map_err(|e| BuzzError::Network(format!("header: {e}")))?)
        .header("hx-request", HeaderValue::from_static("true"))
        .header(wreq::header::REFERER, HeaderValue::from_str(&page_url).map_err(|e| BuzzError::Network(format!("header: {e}")))?)
        .header(wreq::header::ACCEPT, HeaderValue::from_static("*/*"))
        .apply_cookies(cookies)
        .send()
        .await
        .map_err(|e| BuzzError::Network(format!("download: {e}")))?;

    let status = resp.status().as_u16();
    let headers = resp.headers().clone();
    if is_challenge(status, &headers, "") {
        return Err(BuzzError::Challenge);
    }
    const OK_REDIRECT: [u16; 7] = [200, 204, 301, 302, 303, 307, 308];
    if !OK_REDIRECT.contains(&status) {
        return Err(BuzzError::Network(format!("HTTP {status} no /download")));
    }

    // header Hx-Redirect → Location → corpo JSON (defensivo)
    let raw = headers
        .get("hx-redirect")
        .or_else(|| headers.get("location"))
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string());
    let candidate = match raw {
        Some(r) => Some(absolute(host, &r)),
        None => resp
            .text()
            .await
            .ok()
            .and_then(|body| json_download_url(&body))
            .map(|u| absolute(host, &u)),
    };
    match candidate {
        Some(c) => match validate_candidate(&c) {
            Some(direct) => Ok(direct),
            None => Err(BuzzError::NotFound(format!(
                "resposta aponta para página, não arquivo: {c}"
            ))),
        },
        None => Err(BuzzError::NotFound(format!(
            "resposta sem Hx-Redirect (HTTP {status})"
        ))),
    }
}

trait ApplyCookies {
    fn apply_cookies(self, cookies: Option<&str>) -> wreq::RequestBuilder;
}

impl ApplyCookies for wreq::RequestBuilder {
    /// Cookies opcionais (ex.: cf_clearance capturado pela UI, quando houver).
    fn apply_cookies(self, cookies: Option<&str>) -> wreq::RequestBuilder {
        match cookies {
            Some(c) if !c.trim().is_empty() => {
                self.header(wreq::header::COOKIE, c.trim())
            }
            _ => self,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---------- is_buzzheavier_page ----------

    #[test]
    fn paginas_buzzheavier_sao_detectadas() {
        assert!(is_buzzheavier_page("https://buzzheavier.com/cqt08005094h"));
        assert!(is_buzzheavier_page("https://buzzheavier.com/f/abc123.html"));
        assert!(is_buzzheavier_page("https://bzzhr.co/cqt08005094h"));
        assert!(is_buzzheavier_page("https://fuckingfast.net/xyz#d"))
    }

    #[test]
    fn link_direto_e_outros_hosts_nao_sao_pagina() {
        // já é o arquivo: o motor baixa direto, sem resolução
        assert!(!is_buzzheavier_page(
            "https://bzzhr.co/d/8f2c/pacote.tar.gz"
        ));
        assert!(!is_buzzheavier_page(
            "https://buzzheavier.com/d/8f2c/pacote.tar.gz"
        ));
        // fora do provedor
        assert!(!is_buzzheavier_page(
            "https://github.com/owner/repo/releases/download/v1/app.apk"
        ));
        assert!(!is_buzzheavier_page(""));
        assert!(!is_buzzheavier_page("https://example.com/file.apk"));
    }

    // ---------- file_id_from_url ----------

    #[test]
    fn extrai_id_dos_formatos_conhecidos() {
        assert_eq!(
            file_id_from_url("https://buzzheavier.com/cqt08005094h"),
            Some("cqt08005094h".into())
        );
        assert_eq!(
            file_id_from_url("https://buzzheavier.com/cqt08005094h?x=1"),
            Some("cqt08005094h".into())
        );
        assert_eq!(
            file_id_from_url("https://buzzheavier.com/f/abc123.html"),
            Some("abc123".into())
        );
        assert_eq!(file_id_from_url("cqt08005094h"), Some("cqt08005094h".into()));
        assert_eq!(file_id_from_url("https://buzzheavier.com/"), None);
    }

    // ---------- extract_token ----------

    #[test]
    fn extrai_token_do_html() {
        let html = r#"<a href="https://buzzheavier.com/cqt08005094h/download?t=8f2c9d1e4b">Download</a>"#;
        assert_eq!(extract_token(html), Some("8f2c9d1e4b".into()));
    }

    #[test]
    fn token_termina_nos_delimitadores_do_guia() {
        assert_eq!(
            extract_token(r#"href="/x/download?t=tok&amp;x"#),
            Some("tok".into())
        );
        assert_eq!(
            extract_token("download?t=tok' trailing"),
            Some("tok".into())
        );
        assert_eq!(
            extract_token("download?t=tok#frag"),
            Some("tok".into())
        );
    }

    #[test]
    fn sem_token_retorna_none() {
        assert!(extract_token("<html><body>sem ticket</body></html>").is_none());
        assert!(extract_token("").is_none());
        assert!(extract_token("download?t=").is_none());
    }

    // ---------- validate_candidate / absolute / json_download_url ----------

    #[test]
    fn candidato_valido_passa_e_desfaz_entidades() {
        let ok = validate_candidate(
            "https://bzzhr.co/d/8f2c/pacote.tar.gz?a=1&amp;b=2",
        )
        .expect("deveria validar");
        assert_eq!(ok, "https://bzzhr.co/d/8f2c/pacote.tar.gz?a=1&b=2");
    }

    #[test]
    fn anuncios_e_paginas_sao_rejeitados() {
        // redirect de anúncio (fafda.to)
        assert!(validate_candidate("https://fafda.to/go?u=x").is_none());
        // path sem /d/ = página de espera
        assert!(validate_candidate("https://buzzheavier.com/waiting?id=1").is_none());
        // relativa sem host
        assert!(validate_candidate("/d/x/y").is_none());
        assert!(validate_candidate("").is_none());
    }

    #[test]
    fn relativa_vira_absoluta() {
        assert_eq!(
            absolute("bzzhr.co", "/d/8f2c/pacote.tar.gz"),
            "https://bzzhr.co/d/8f2c/pacote.tar.gz"
        );
        assert_eq!(
            absolute("bzzhr.co", "https://outro/d/x"),
            "https://outro/d/x"
        );
    }

    #[test]
    fn extrai_download_url_de_corpo_json() {
        let body = r#"{"code":200,"status":"ok","data":{"download_url":"/d/8f2c/pacote.tar.gz"}}"#;
        assert_eq!(
            json_download_url(body),
            Some("/d/8f2c/pacote.tar.gz".into())
        );
        assert_eq!(json_download_url("<html>sem json</html>"), None);
        assert_eq!(json_download_url(r#"{"data":{"download_url":""}}"#), None);
    }
}
