//! Backoff exponencial com jitter + PRNG leve (sem dependências).

use std::time::Duration;

/// PRNG xorshift64* — determinístico e barato, semeado por tempo/pid.
pub struct Jitter {
    state: u64,
}

impl Jitter {
    pub fn new(seed: u64) -> Self {
        // semente não-nula
        let seed = if seed == 0 { 0x9E3779B97F4A7C15 } else { seed };
        Jitter { state: seed }
    }

    pub fn from_time() -> Self {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos() as u64)
            .unwrap_or(0x1234_5678_9ABC_DEF0);
        let pid = std::process::id() as u64;
        Jitter::new(nanos ^ (pid << 32) ^ 0xA5A5_5A5A_0000_FFFF)
    }

    pub fn next_u64(&mut self) -> u64 {
        let mut x = self.state;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.state = x;
        x.wrapping_mul(0x2545F4914F6CDD1D)
    }

    /// Valor em [0, max).
    pub fn below(&mut self, max: u64) -> u64 {
        if max == 0 {
            0
        } else {
            self.next_u64() % max
        }
    }
}

/// Backoff exponencial: base * 2^(attempt-1), com teto de 30s e jitter de
/// até 30% do valor. `attempt` começa em 1.
pub fn backoff_delay(attempt: u32, retry_after: Option<Duration>) -> Duration {
    if let Some(ra) = retry_after {
        return ra.min(Duration::from_secs(120));
    }
    let base_ms: u64 = 500u64.saturating_mul(1u64 << (attempt.min(7) - 1).min(6));
    let capped = base_ms.min(30_000);
    let mut j = Jitter::from_time();
    let jitter = j.below((capped / 3).max(1));
    Duration::from_millis(capped + jitter)
}

/// Erros classificados entre recuperáveis e fatais.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ErrorKind {
    /// timeout, reset, 5xx, 429 — vale tentar de novo
    Retryable,
    /// 404, 403 persistente, disco cheio, etc.
    Fatal,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_cresce_e_tem_teto() {
        let d1 = backoff_delay(1, None);
        let d2 = backoff_delay(2, None);
        let d9 = backoff_delay(9, None);
        assert!(d1 <= d2, "backoff deve crescer");
        assert!(d9 <= Duration::from_secs(40), "com jitter, <= 30s + 30%");
        assert!(d1 >= Duration::from_millis(500));
    }

    #[test]
    fn retry_after_tem_prioridade() {
        let d = backoff_delay(3, Some(Duration::from_secs(2)));
        assert_eq!(d, Duration::from_secs(2));
    }

    #[test]
    fn jitter_eh_deterministico_por_semente() {
        let mut a = Jitter::new(42);
        let mut b = Jitter::new(42);
        for _ in 0..100 {
            assert_eq!(a.next_u64(), b.next_u64());
        }
        let mut c = Jitter::new(7);
        let mut seen_zero = false;
        for _ in 0..64 {
            if c.below(3) == 0 {
                seen_zero = true;
            }
        }
        assert!(seen_zero);
    }
}
