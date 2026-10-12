//! Integridade: hash em streaming (SHA-256/MD5), espaço livre em disco e
//! sanitização de nomes de arquivo vindos de fontes não confiáveis
//! (Content-Disposition / captura da WebView).

use std::io::Read;
use std::path::Path;

/// Algoritmos de verificação suportados. SHA-256 é o padrão; MD5 existe por
/// compatibilidade com servidores que só publicam MD5 (mesma política do
/// Download Navi — `DigestUtils`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HashAlgo {
    Sha256,
    Md5,
}

impl HashAlgo {
    /// Hex esperado (32 chars MD5, 64 chars SHA-256).
    pub fn hex_len(self) -> usize {
        match self {
            HashAlgo::Sha256 => 64,
            HashAlgo::Md5 => 32,
        }
    }

    /// Nome legível para mensagens de UI.
    pub fn label(self) -> &'static str {
        match self {
            HashAlgo::Sha256 => "SHA-256",
            HashAlgo::Md5 => "MD5",
        }
    }

    /// Detecta o algoritmo pelo comprimento do hex (mesma heurística do
    /// Download Navi: 32 → MD5, 64 → SHA-256).
    pub fn from_hex(hex: &str) -> Option<HashAlgo> {
        match hex.len() {
            32 => Some(HashAlgo::Md5),
            64 => Some(HashAlgo::Sha256),
            _ => None,
        }
    }
}

/// Hash de arquivo em streaming — 1 MiB por leitura, nada fica em memória.
pub fn hash_file(path: &Path, algo: HashAlgo) -> std::io::Result<String> {
    use md5::Digest as _;
    let mut file = std::fs::File::open(path)?;
    let mut buf = vec![0u8; 1024 * 1024];
    match algo {
        HashAlgo::Sha256 => {
            let mut h = sha2::Sha256::new();
            loop {
                let n = file.read(&mut buf)?;
                if n == 0 {
                    break;
                }
                h.update(&buf[..n]);
            }
            Ok(crate::util::hex(&h.finalize()))
        }
        HashAlgo::Md5 => {
            let mut h = md5::Md5::new();
            loop {
                let n = file.read(&mut buf)?;
                if n == 0 {
                    break;
                }
                h.update(&buf[..n]);
            }
            Ok(crate::util::hex(&h.finalize()))
        }
    }
}

/// Confere um digest esperado (hex, qualquer caixa) contra o hash real.
pub fn verify_hash(path: &Path, algo: HashAlgo, expected_hex: &str) -> Result<(), crate::error::DownloadError> {
    let expected = expected_hex.trim().to_lowercase();
    if expected.len() != algo.hex_len() || !expected.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err(crate::error::DownloadError::ChecksumMismatch { algorithm: algo.label() });
    }
    let got = hash_file(path, algo).map_err(crate::error::DownloadError::from)?;
    if got == expected {
        Ok(())
    } else {
        Err(crate::error::DownloadError::ChecksumMismatch { algorithm: algo.label() })
    }
}

/// `true` quando `hex` é um digest válido para o algoritmo (hex, tamanho certo).
pub fn is_valid_digest(hex: &str, algo: HashAlgo) -> bool {
    let h = hex.trim();
    h.len() == algo.hex_len() && h.chars().all(|c| c.is_ascii_hexdigit())
}

/// Espaço livre disponível (para usuários não-root) no volume de `path`.
/// `None` = não foi possível determinar (trata como "sem informação" — o
/// chamador decide se bloqueia; checagem é defesa, não fonte de verdade).
pub fn free_space_bytes(path: &Path) -> Option<u64> {
    use std::os::unix::ffi::OsStrExt;
    let c = std::ffi::CString::new(path.as_os_str().as_bytes()).ok()?;
    let mut st: libc::statvfs = unsafe { std::mem::zeroed() };
    let rc = unsafe { libc::statvfs(c.as_ptr(), &mut st) };
    if rc != 0 || st.f_frsize == 0 {
        return None;
    }
    Some(st.f_bavail as u64 * st.f_frsize as u64)
}

/// Sanitiza um nome de arquivo vindo de fonte NÃO confiável
/// (Content-Disposition da WebView, header do provedor):
/// - reduz ao nome base (sem diretórios — barra path traversal);
/// - remove caracteres de controle e proíbe FAT (`" * : < > ? | \`);
/// - remove pontos/espaços nas pontas (higiene multiplataforma);
/// - limita a 200 bytes sem cortar um codepoint UTF-8 ao meio;
/// - vazio/`.`/`..` → `fallback`.
pub fn sanitize_file_name(raw: &str, fallback: &str) -> String {
    // Base: último componente por qualquer separador (Unix e Windows).
    let base = raw
        .split(['/', '\\'])
        .last()
        .unwrap_or("")
        .trim();

    if base.is_empty() || base == "." || base == ".." {
        return sanitize_file_name(fallback, "download.bin");
    }

    let mut out = String::with_capacity(base.len());
    for ch in base.chars() {
        let ok = !ch.is_control()
            && !matches!(ch, '"' | '*' | ':' | '<' | '>' | '?' | '|' | '\\')
            && (ch as u32) >= 0x20;
        out.push(if ok { ch } else { '_' });
    }

    let trimmed = out.trim().trim_matches('.').to_string();
    let final_name = if trimmed.is_empty() { "download.bin".to_string() } else { trimmed };

    cap_utf8_bytes(&final_name, 200)
}

/// Corta a string para caber em `max_bytes` sem quebrar codepoints.
fn cap_utf8_bytes(s: &str, max_bytes: usize) -> String {
    if s.len() <= max_bytes {
        return s.to_string();
    }
    let mut end = max_bytes;
    while end > 0 && !s.is_char_boundary(end) {
        end -= 1;
    }
    s[..end].to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    const SHA_ABC: &str = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    const MD5_ABC: &str = "900150983cd24fb0d6963f7d28e17f72";

    fn tmp_with(content: &[u8]) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("ps_integrity_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join(format!("f_{}", crate::download::now_secs() as u128 * 1000 + content.len() as u128));
        std::fs::write(&p, content).unwrap();
        p
    }

    #[test]
    fn hash_sha256_e_md5_conhecidos() {
        let p = tmp_with(b"abc");
        assert_eq!(hash_file(&p, HashAlgo::Sha256).unwrap(), SHA_ABC);
        assert_eq!(hash_file(&p, HashAlgo::Md5).unwrap(), MD5_ABC);
        assert!(verify_hash(&p, HashAlgo::Sha256, SHA_ABC.to_uppercase().as_str()).is_ok());
        assert!(verify_hash(&p, HashAlgo::Md5, MD5_ABC).is_ok());
        assert!(verify_hash(&p, HashAlgo::Sha256, MD5_ABC).is_err());
        std::fs::remove_file(&p).ok();
    }

    #[test]
    fn deteccao_por_tamanho_e_validacao() {
        assert_eq!(HashAlgo::from_hex(SHA_ABC), Some(HashAlgo::Sha256));
        assert_eq!(HashAlgo::from_hex(MD5_ABC), Some(HashAlgo::Md5));
        assert_eq!(HashAlgo::from_hex("zz"), None);
        assert!(is_valid_digest(SHA_ABC, HashAlgo::Sha256));
        assert!(!is_valid_digest("abc", HashAlgo::Sha256));
        assert!(!is_valid_digest("zzzz", HashAlgo::Md5));
    }

    #[test]
    fn sanitizacao_bloqueia_traversal_e_caracteres_proibidos() {
        assert_eq!(sanitize_file_name("../../etc/passwd", "d.bin"), "passwd");
        assert_eq!(sanitize_file_name("a\\b\\evil.exe", "d.bin"), "evil.exe");
        assert_eq!(sanitize_file_name("..", "d.bin"), "d.bin");
        assert_eq!(sanitize_file_name("", "d.bin"), "d.bin");
        assert_eq!(sanitize_file_name("...hidden...", "d.bin"), "hidden");
        assert_eq!(sanitize_file_name("arquivo: com*proibidos?.zip", "d.bin"), "arquivo_ com_proibidos_.zip");
        assert_eq!(sanitize_file_name("linha\nquebrada.apk", "d.bin"), "linha_quebrada.apk");
        // sem cortar codepoint ao meio
        let emoji = "😄".repeat(120); // 4 bytes cada = 480 bytes
        let s = sanitize_file_name(&emoji, "d.bin");
        assert!(s.len() <= 200);
        assert!(s.is_char_boundary(s.len()));
        assert_eq!(s.chars().count(), 50);
    }

    #[test]
    fn espaco_livre_em_diretorio_real() {
        let v = free_space_bytes(Path::new("/tmp"));
        if let Some(b) = v {
            assert!(b > 0);
        }
        // None é aceitável em filesystems exóticos; o teste garante ausência de pânico.
        let _ = free_space_bytes(Path::new("/proc/nonexistent-xyz"));
    }
}
