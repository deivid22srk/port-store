//! Erros tipados do motor de downloads.
//!
//! Cada variante carrega informação suficiente para decidir retentativa e
//! mapear uma mensagem amigável em português do Brasil na UI (o Kotlin nunca
//! interpreta strings internas: o estado `Failed` + `error.user_message()`
//! bastam).

use thiserror::Error;

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum DownloadError {
    /// Resposta HTTP inesperada. `fatal` = não adianta repetir (404, 403
    /// persistente, 410...); o resto (5xx, 429, 408) é transitório.
    #[error("HTTP {code}")]
    Http { code: u16, fatal: bool },

    /// Falha de transporte (timeout, reset, DNS, TLS).
    #[error("erro de rede: {0}")]
    Network(String),

    /// Falha de arquivo/disco (abrir, escrever, renomear).
    #[error("falha de arquivo: {0}")]
    File(String),

    /// Espaço livre insuficiente (pre-flight ou durante o download).
    #[error("espaço insuficiente no armazenamento")]
    InsufficientSpace,

    /// Hash divergente ao concluir.
    #[error("verificação de integridade falhou ({algorithm})")]
    ChecksumMismatch { algorithm: &'static str },

    /// O servidor não suporta `Range`; retomada impossível.
    #[error("o servidor não permite retomar este download")]
    CannotResume,

    /// ETag/Last-Modified mudou entre tentativas — o conteúdo mudou.
    #[error("o arquivo mudou no servidor; o download será reiniciado")]
    ResourceChanged,

    /// Limite de redirecionamentos excedido (possível loop).
    #[error("muitos redirecionamentos")]
    TooManyRedirects,

    /// Contrato da captura da WebView violado (URL, headers, nome...).
    #[error("captura inválida: {0}")]
    InvalidCapture(String),

    /// Arquivo compactado corrompido/ilegível.
    #[error("arquivo corrompido ou ilegível")]
    Corrupted,

    /// Falha de persistência (SQLite). O download continua em memória; a
    /// retomada cai no `.state` legado.
    #[error("persistência: {0}")]
    Storage(String),

    /// Pedido de cancelamento do usuário (controle de fluxo, não erro).
    #[error("download cancelado")]
    Canceled,

    #[error("{0}")]
    Other(String),
}

impl DownloadError {
    /// Vale repetir com backoff? Transitórios: rede, 5xx/429/408, panics de
    /// worker. Fatais: 4xx definitivos, disco, checksum, captura inválida.
    pub fn is_retryable(&self) -> bool {
        match self {
            DownloadError::Http { fatal, .. } => !fatal,
            DownloadError::Network(_) => true,
            DownloadError::File(_) => false,
            DownloadError::InsufficientSpace => false,
            DownloadError::ChecksumMismatch { .. } => false,
            DownloadError::CannotResume => false,
            DownloadError::ResourceChanged => true, // reinicia do zero com novo recurso
            DownloadError::TooManyRedirects => false,
            DownloadError::InvalidCapture(_) => false,
            DownloadError::Corrupted => false,
            DownloadError::Storage(_) => false,
            DownloadError::Canceled => false,
            DownloadError::Other(_) => false,
        }
    }

    /// Classificador curto para logs/telemetria (sem conteúdo sensível).
    pub fn kind(&self) -> &'static str {
        match self {
            DownloadError::Http { .. } => "http",
            DownloadError::Network(_) => "network",
            DownloadError::File(_) => "file",
            DownloadError::InsufficientSpace => "space",
            DownloadError::ChecksumMismatch { .. } => "checksum",
            DownloadError::CannotResume => "cannot_resume",
            DownloadError::ResourceChanged => "resource_changed",
            DownloadError::TooManyRedirects => "redirects",
            DownloadError::InvalidCapture(_) => "capture",
            DownloadError::Corrupted => "corrupted",
            DownloadError::Storage(_) => "storage",
            DownloadError::Canceled => "canceled",
            DownloadError::Other(_) => "other",
        }
    }

    /// Mensagem amigável em pt-BR para a UI. NUNCA inclui URL/cookies/headers.
    pub fn user_message(&self) -> String {
        match self {
            DownloadError::Http { code, .. } => match code {
                400 => "O servidor rejeitou a solicitação (HTTP 400)".into(),
                401 | 403 => format!(
                    "Acesso negado pelo servidor (HTTP {code}): o link pode ter expirado — tente baixar de novo"
                ),
                404 | 410 => format!("Arquivo não encontrado no servidor (HTTP {code})"),
                429 => "O servidor está limitando as solicitações (HTTP 429); tentaremos de novo".into(),
                500..=599 => format!("Erro do servidor (HTTP {code}); tentaremos de novo"),
                _ => format!("Resposta inesperada do servidor (HTTP {code})"),
            },
            DownloadError::Network(_) => {
                "Falha de rede. Verifique a conexão — o download continuará quando a internet voltar".into()
            }
            DownloadError::File(e) => format!("Falha ao gravar o arquivo: {e}"),
            DownloadError::InsufficientSpace => {
                "Não há espaço livre suficiente no armazenamento".into()
            }
            DownloadError::ChecksumMismatch { algorithm } => {
                format!("O arquivo baixado está corrompido (verificação {algorithm} falhou)")
            }
            DownloadError::CannotResume => {
                "O servidor não permite continuar de onde parou".into()
            }
            DownloadError::ResourceChanged => {
                "O arquivo mudou no servidor — o download foi reiniciado".into()
            }
            DownloadError::TooManyRedirects => "O servidor redirecionou demais (possível loop)".into(),
            DownloadError::InvalidCapture(_) => {
                "O link capturado da página não é válido para download".into()
            }
            DownloadError::Corrupted => "O arquivo está corrompido e será descartado".into(),
            DownloadError::Canceled => "Download cancelado".into(),
            DownloadError::Other(m) => m.clone(),
        }
    }

    /// Mensagem técnica (logs) — nunca contém cookies/tokens.
    pub fn technical(&self) -> String {
        match self {
            DownloadError::Http { code, fatal } => {
                format!("HTTP {code} ({})", if *fatal { "fatal" } else { "retryable" })
            }
            other => other.to_string(),
        }
    }
}

impl From<std::io::Error> for DownloadError {
    fn from(e: std::io::Error) -> Self {
        match e.kind() {
            std::io::ErrorKind::StorageFull => DownloadError::InsufficientSpace,
            _ => DownloadError::File(e.to_string()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classificacao_retryable() {
        assert!(!DownloadError::Http { code: 404, fatal: true }.is_retryable());
        assert!(DownloadError::Http { code: 503, fatal: false }.is_retryable());
        assert!(DownloadError::Network("timeout".into()).is_retryable());
        assert!(!DownloadError::InsufficientSpace.is_retryable());
        assert!(!DownloadError::ChecksumMismatch { algorithm: "SHA-256" }.is_retryable());
        assert!(DownloadError::ResourceChanged.is_retryable());
        assert!(!DownloadError::Canceled.is_retryable());
    }

    #[test]
    fn mensagens_nao_vazam_dados_sensiveis() {
        let e = DownloadError::Network("cookie: SEGREDO=1; tls alert".into());
        let m = e.user_message();
        assert!(!m.contains("SEGREDO"), "mensagem não deve conter detalhes: {m}");
        assert_eq!(e.kind(), "network");
    }

    #[test]
    fn io_storage_full_vira_espaco_insuficiente() {
        let e: DownloadError = std::io::Error::from(std::io::ErrorKind::StorageFull).into();
        assert_eq!(e, DownloadError::InsufficientSpace);
    }
}
