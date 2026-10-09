//! Resolução do link direto de páginas do MediaFire.
//!
//! O catálogo usa links do MediaFire em `links.download`, que são PÁGINAS
//! (ex.: `https://www.mediafire.com/file/CHAVE/nome.apk/file`) e não o
//! arquivo em si. Este módulo extrai do HTML da página o link direto
//! (host `downloadNNN.mediafire.com`) para que o motor baixe o arquivo
//! de verdade — a extração é parte do motor (roda antes do probe/download).
//!
//! Estratégia (referência: github.com/juvenal-yescas/mediafire-dl):
//!  1. GET da página com User-Agent de DESKTOP — a página completa, a mesma
//!     de navegadores de PC; a versão mobile pode ter layout diferente;
//!  2. se a resposta já vier com `Content-Disposition`, os redirects caíram
//!     direto no arquivo e a URL final já é o link direto;
//!  3. senão, extrai do HTML em camadas:
//!     a. janela ao redor do botão oficial (`id="downloadButton"`);
//!     b. primeiro `href` (aspas duplas/simples/sem aspas, inclui
//!        `location.href='…'` em JS) que aponte para um host "download…";
//!     c. primeira ocorrência literal de `http(s)://download` no documento;
//!  4. desfaz entidades HTML (`&amp;` → `&`) — links com query string
//!     quebrariam sem isso.
//!
//! Sem dependências novas: o casamento de padrões é feito à mão sobre bytes
//! ASCII (seguro para HTML UTF-8, pois os índices vêm de padrões ASCII).
//! Observação de retomada: o link direto do MediaFire é assinado e pode
//! expirar; se mudar entre tentativas, o `.state` é descartado e o download
//! recomeça do zero (comportamento seguro, apenas menos eficiente).

use reqwest::Client;

/// Erros da resolução do MediaFire.
#[derive(Debug)]
pub enum MediafireError {
    /// Rede/HTTP transiente — o motor pode tentar de novo (backoff do job).
    Network(String),
    /// Página ok, mas sem link direto (arquivo restrito/removido ou HTML
    /// mudou) — repetir não resolve.
    NotFound,
}

impl MediafireError {
    pub fn message(&self) -> String {
        match self {
            MediafireError::Network(e) => format!("falha ao abrir a página do MediaFire: {e}"),
            MediafireError::NotFound => {
                "link direto não encontrado na página do MediaFire (arquivo restrito ou removido). Use \"Abrir no navegador\".".into()
            }
        }
    }
}

/// User-Agent de DESKTOP: garante a página completa com o botão de download.
pub const DESKTOP_UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

/// true se a URL é uma PÁGINA do MediaFire (precisa de resolução antes do
/// download). Links diretos (host `downloadNNN.mediafire.com`) retornam
/// false: o motor já sabe baixá-los como qualquer arquivo.
pub fn is_mediafire_page(url: &str) -> bool {
    let lower = url.to_ascii_lowercase();
    if !lower.contains("mediafire.com") {
        return false;
    }
    match host_of(url) {
        Some(host) => {
            let host = host.to_ascii_lowercase();
            // "download", "download123", "download944"… = hosts de arquivo direto
            !(host.starts_with("download") && host.ends_with(".mediafire.com"))
        }
        None => false,
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

/// Baixa a página e extrai o link direto.
pub async fn resolve_direct_link(client: &Client, page_url: &str) -> Result<String, MediafireError> {
    let resp = client
        .get(page_url)
        .header(reqwest::header::USER_AGENT, DESKTOP_UA)
        .send()
        .await
        .map_err(|e| MediafireError::Network(e.to_string()))?;

    let status = resp.status().as_u16();
    if !(200..300).contains(&status) {
        return Err(if status == 403 || status == 404 || status == 410 {
            MediafireError::NotFound
        } else {
            MediafireError::Network(format!("HTTP {status}"))
        });
    }

    // Os redirects já podem ter caído no arquivo: a URL final serve o binário.
    if resp
        .headers()
        .get(reqwest::header::CONTENT_DISPOSITION)
        .is_some()
    {
        return Ok(resp.url().to_string());
    }

    let html = resp
        .text()
        .await
        .map_err(|e| MediafireError::Network(e.to_string()))?;
    extract_direct_link(&html).ok_or(MediafireError::NotFound)
}

/// Extrai o link direto do HTML da página (função pura — testável sem rede).
pub fn extract_direct_link(html: &str) -> Option<String> {
    // a) janela ao redor do botão oficial de download
    if let Some(pos) = html.find("downloadButton") {
        let start = pos.saturating_sub(600);
        let end = (pos + "downloadButton".len() + 400).min(html.len());
        if let Some(window) = html.get(start..end) {
            if let Some(link) = first_download_href(window) {
                return Some(link);
            }
        }
    }
    // b) primeiro href cujo valor aponte para um host "download…"
    if let Some(link) = first_download_href(html) {
        return Some(link);
    }
    // c) ocorrência literal no documento (redirect por JS, etc.)
    first_download_literal(html)
}

/// Procura o primeiro atributo `href` cujo valor comece com http(s)://download.
/// Aceita aspas duplas, simples e valores sem aspas (e `location.href='…'`).
fn first_download_href(html: &str) -> Option<String> {
    let lower = html.to_ascii_lowercase();
    let bytes = lower.as_bytes();
    let mut search_from = 0usize;
    while let Some(rel) = lower[search_from..].find("href") {
        let mut i = search_from + rel + 4;
        search_from = i;
        // pula espaços e exige '=' (evita "hreflang" etc.)
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        if i >= bytes.len() || bytes[i] != b'=' {
            continue;
        }
        i += 1;
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        let (value, next) = read_attr_value(html, &lower, i)?;
        search_from = next.max(i + 1);
        if let Some(url) = download_url_or_none(value) {
            return Some(url);
        }
    }
    None
}

/// Lê o valor do atributo a partir de `i` (já posicionado no início do valor).
/// Retorna (valor, índice logo após o valor). `lower` é a versão ascii-lowercase
/// de `html` com os MESMOS offsets de bytes.
fn read_attr_value<'a>(html: &'a str, lower: &str, i: usize) -> Option<(&'a str, usize)> {
    let bytes = lower.as_bytes();
    if i >= bytes.len() {
        return None;
    }
    let quote = bytes[i];
    if quote == b'"' || quote == b'\'' {
        let end = lower[i + 1..].find(quote as char).map(|p| i + 1 + p)?;
        Some((html.get(i + 1..end)?, end + 1))
    } else {
        // valor sem aspas: termina em espaço ou '>'
        let end = lower[i..]
            .find(|c: char| c.is_ascii_whitespace() || c == '>')
            .map(|p| i + p)?;
        Some((html.get(i..end)?, end))
    }
}

/// Procura a primeira ocorrência literal de http(s)://download no documento
/// (equivale à regex do mediafire-dl, mas independente de aspas).
fn first_download_literal(html: &str) -> Option<String> {
    let lower = html.to_ascii_lowercase();
    let mut from = 0usize;
    while let Some(rel) = lower[from..].find("://download") {
        let colon = from + rel;
        let start = if lower[..colon].ends_with("https") {
            colon - 5
        } else if lower[..colon].ends_with("http") {
            colon - 4
        } else {
            from = colon + 3;
            continue;
        };
        let rest = html.get(start..)?;
        // exige terminador (aspas/espaço/…): sem ele o HTML está truncado e a
        // própria URL estaria incompleta — ignora esta ocorrência
        let end = match rest
            .find(|c: char| matches!(c, '"' | '\'' | '<' | ')' | ' ') || c.is_control())
        {
            Some(e) => e,
            None => {
                from = start + 8;
                continue;
            }
        };
        from = start + 8;
        if let Some(url) = download_url_or_none(rest.get(..end)?) {
            return Some(url);
        }
    }
    None
}

/// Valida o candidato (prefixo http(s)://download) e desfaz entidades HTML.
fn download_url_or_none(value: &str) -> Option<String> {
    let v = value.trim();
    let lower = v.to_ascii_lowercase();
    if !(lower.starts_with("https://download") || lower.starts_with("http://download")) {
        return None;
    }
    Some(unescape_html(v))
}

fn unescape_html(v: &str) -> String {
    v.replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---------- is_mediafire_page ----------

    #[test]
    fn paginas_mediafire_sao_detectadas() {
        assert!(is_mediafire_page(
            "https://www.mediafire.com/file/abc123/meujogo.apk/file"
        ));
        assert!(is_mediafire_page("https://mediafire.com/file/abc123/meujogo.apk"));
        assert!(is_mediafire_page("http://www.mediafire.com/download/abc123"));
        assert!(is_mediafire_page(
            "https://www.mediafire.com/download.php?d=abc123"
        ));
        assert!(is_mediafire_page("https://app.mediafire.com/abc123"));
    }

    #[test]
    fn link_direto_e_outras_urls_nao_sao_pagina() {
        // hosts de download direto: o motor baixa direto, sem resolução
        assert!(!is_mediafire_page(
            "https://download944.mediafire.com/1a2b3c/meujogo.apk"
        ));
        assert!(!is_mediafire_page("https://download.mediafire.com/x/y"));
        // fora do MediaFire
        assert!(!is_mediafire_page(
            "https://github.com/owner/repo/releases/download/v1/app.apk"
        ));
        assert!(!is_mediafire_page(""));
        assert!(!is_mediafire_page("https://example.com/file.apk"));
    }

    // ---------- extract_direct_link ----------

    /// Trecho real da página de arquivo do MediaFire (href ANTES do id).
    const PAGINA_REAL: &str = r#"<html><body>
    <div class="download_link" style="margin-bottom:-9px">
        <a href="https://download944.mediafire.com/k3yb4it0k3n/meujogo.apk"
           id="downloadButton" class="input popsok" aria-label="Download file">
            <span>Download (25.4 MB)</span>
        </a>
    </div>
    <a href="https://www.mediafire.com/report/copyright">Report</a>
    </body></html>"#;

    #[test]
    fn extrai_do_botao_oficial() {
        let link = extract_direct_link(PAGINA_REAL).expect("deveria extrair");
        assert_eq!(link, "https://download944.mediafire.com/k3yb4it0k3n/meujogo.apk");
    }

    #[test]
    fn extrai_com_aspas_simples() {
        let html = r#"<div><a href='https://download233.mediafire.com/t0k3n/outro.apk' id='downloadButton'>Baixar</a></div>"#;
        let link = extract_direct_link(html).expect("deveria extrair");
        assert_eq!(link, "https://download233.mediafire.com/t0k3n/outro.apk");
    }

    #[test]
    fn desfaz_entidades_html() {
        let html = r#"<a id="downloadButton" href="https://download100.mediafire.com/x/y.apk?a=1&amp;b=2">Baixar</a>"#;
        let link = extract_direct_link(html).expect("deveria extrair");
        assert_eq!(link, "https://download100.mediafire.com/x/y.apk?a=1&b=2");
    }

    #[test]
    fn extrai_de_redirect_js() {
        let html = r#"<script>window.location.href='https://download101.mediafire.com/j5/x.apk';</script>"#;
        let link = extract_direct_link(html).expect("deveria extrair");
        assert_eq!(link, "https://download101.mediafire.com/j5/x.apk");
    }

    #[test]
    fn cai_no_href_generico_sem_botao() {
        // página sem id="downloadButton", mas com o href direto (fallback b)
        let html = r#"<html><a href="https://www.mediafire.com/home">Início</a>
        <a href="https://download555.mediafire.com/tok/n.arquivo">dl</a></html>"#;
        let link = extract_direct_link(html).expect("deveria extrair");
        assert_eq!(link, "https://download555.mediafire.com/tok/n.arquivo");
    }

    #[test]
    fn sem_link_direto_retorna_none() {
        // hrefs normais do site: nenhum aponta para host "download…"
        let html = r#"<html>
        <a href="https://www.mediafire.com/file/abc/x.apk/file">página</a>
        <a href="https://static.mediafire.com/images/logo.png">logo</a>
        <a href="/robots.txt">relativo</a>
        </html>"#;
        assert!(extract_direct_link(html).is_none());
    }

    #[test]
    fn html_vazio_ou_truncado_nao_panica() {
        assert!(extract_direct_link("").is_none());
        assert!(extract_direct_link("<a href=\"https://download").is_none());
        assert!(extract_direct_link("href='").is_none());
        assert!(extract_direct_link("href=").is_none());
    }
}
