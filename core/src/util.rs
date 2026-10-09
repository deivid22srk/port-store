//! Utilidades: parsing de headers, hex, verificação de APK.

use std::path::Path;

/// `Content-Range: bytes 0-0/12345` => Some(12345)
pub fn parse_content_range_total(value: &str) -> Option<u64> {
    let idx = value.rfind('/')?;
    let total = value[idx + 1..].trim();
    if total == "*" {
        return None;
    }
    total.parse::<u64>().ok()
}

/// `Content-Range: bytes 100-200/12345` => Some((100, 200))
pub fn parse_content_range_span(value: &str) -> Option<(u64, u64)> {
    let slash = value.rfind('/')?;
    let range = &value[..slash];
    let range = range.trim_start_matches("bytes").trim();
    let dash = range.find('-')?;
    let s = range[..dash].trim().parse::<u64>().ok()?;
    let e = range[dash + 1..].trim().parse::<u64>().ok()?;
    Some((s, e))
}

pub fn hex(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{b:02x}"));
    }
    s
}

/// Assinatura de ZIP/APK: "PK\x03\x04"
pub fn looks_like_apk(path: &Path) -> bool {
    use std::io::Read;
    let mut f = match std::fs::File::open(path) {
        Ok(f) => f,
        Err(_) => return false,
    };
    let mut magic = [0u8; 4];
    match f.read_exact(&mut magic) {
        Ok(()) => &magic == b"PK\x03\x04",
        Err(_) => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn content_range_total() {
        assert_eq!(parse_content_range_total("bytes 0-0/12345"), Some(12345));
        assert_eq!(parse_content_range_total("bytes 5-9/*"), None);
        assert_eq!(parse_content_range_total("lixo"), None);
    }

    #[test]
    fn content_range_span() {
        assert_eq!(parse_content_range_span("bytes 100-200/12345"), Some((100, 200)));
        assert_eq!(parse_content_range_span("bytes 0-0/1"), Some((0, 0)));
        assert_eq!(parse_content_range_span("lixo"), None);
    }

    #[test]
    fn hex_encoding() {
        assert_eq!(hex(&[0xde, 0xad, 0xbe, 0xef]), "deadbeef");
        assert_eq!(hex(&[]), "");
    }
}
