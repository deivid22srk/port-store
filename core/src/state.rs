//! Estado persistente de um download (arquivo `<dest>.state`, JSON), escrito
//! atomicamente (tmp + rename) e usado para retomar após pausa/crash.

use serde::{Deserialize, Serialize};
use std::io::Write;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PersistState {
    /// URL usada (para validar se é a mesma na retomada)
    pub url: String,
    /// ETag do recurso (validação de mudança no servidor)
    pub etag: Option<String>,
    /// Last-Modified (fallback quando não há ETag)
    pub last_modified: Option<String>,
    /// Tamanho total conhecido (0 = desconhecido)
    pub total: u64,
    /// Servidor suporta Range?
    pub ranges: bool,
    /// Modo fluxo único (sem Range) — `downloaded` é a posição linear
    pub single: bool,
    /// Bytes já baixados
    pub downloaded: u64,
    /// Intervalos pendentes (segmentado): (start, end) inclusivos
    pub pending: Vec<(u64, u64)>,
    /// Timestamp da última atualização (unix segs)
    pub updated_at: u64,
}

pub fn state_path(dest: &Path) -> PathBuf {
    let mut p = dest.as_os_str().to_os_string();
    p.push(".state");
    PathBuf::from(p)
}

pub fn part_path(dest: &Path) -> PathBuf {
    let mut p = dest.as_os_str().to_os_string();
    p.push(".part");
    PathBuf::from(p)
}

pub fn save(dest: &Path, state: &PersistState) -> std::io::Result<()> {
    let path = state_path(dest);
    let tmp = {
        let mut p = path.clone().into_os_string();
        p.push(".tmp");
        PathBuf::from(p)
    };
    let json = serde_json::to_vec(state)
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e))?;
    {
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(&json)?;
        f.sync_all().ok();
    }
    std::fs::rename(&tmp, &path)
}

pub fn load(dest: &Path) -> Option<PersistState> {
    let path = state_path(dest);
    let bytes = std::fs::read(&path).ok()?;
    serde_json::from_slice(&bytes).ok()
}

pub fn remove(dest: &Path) {
    let _ = std::fs::remove_file(state_path(dest));
}

/// Compatibilidade do estado salvo com o recurso atual (etag/last-modified/size).
pub fn compatible(saved: &PersistState, etag: &Option<String>, last_modified: &Option<String>, total: u64) -> bool {
    if let (Some(a), Some(b)) = (&saved.etag, etag) {
        return a == b;
    }
    if let (Some(a), Some(b)) = (&saved.last_modified, last_modified) {
        return a == b;
    }
    // sem validadores: só aceita se o tamanho bate (quando conhecido dos dois lados)
    if saved.total > 0 && total > 0 {
        return saved.total == total;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let st = PersistState {
            url: "https://x/y.apk".into(),
            etag: Some("\"abc\"".into()),
            last_modified: None,
            total: 1000,
            ranges: true,
            single: false,
            downloaded: 400,
            pending: vec![(400, 700), (701, 999)],
            updated_at: 12345,
        };
        let json = serde_json::to_string(&st).unwrap();
        let back: PersistState = serde_json::from_str(&json).unwrap();
        assert_eq!(back.downloaded, 400);
        assert_eq!(back.pending.len(), 2);
        assert_eq!(back.etag.as_deref(), Some("\"abc\""));
    }

    #[test]
    fn compat_por_etag() {
        let st = PersistState {
            etag: Some("v1".into()),
            total: 100,
            ..Default::default()
        };
        assert!(compatible(&st, &Some("v1".into()), &None, 100));
        assert!(!compatible(&st, &Some("v2".into()), &None, 100));
        assert!(!compatible(&st, &None, &None, 999)); // size mismatch
        assert!(compatible(&st, &None, &None, 100));
    }
}
