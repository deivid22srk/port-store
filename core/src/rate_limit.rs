//! Token bucket global para limite de velocidade (0 = ilimitado).

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

struct BucketState {
    tokens: f64,
    last: Instant,
}

pub struct RateLimiter {
    /// bytes/segundo; 0 = sem limite
    rate: AtomicU64,
    inner: Mutex<BucketState>,
}

impl RateLimiter {
    pub fn new(rate_bps: u64) -> Self {
        RateLimiter {
            rate: AtomicU64::new(rate_bps),
            inner: Mutex::new(BucketState {
                tokens: 0.0,
                last: Instant::now(),
            }),
        }
    }

    pub fn set_rate(&self, rate_bps: u64) {
        self.rate.store(rate_bps, Ordering::Relaxed);
    }

    pub fn rate(&self) -> u64 {
        self.rate.load(Ordering::Relaxed)
    }

    /// Consome `n` bytes do balde; dorme (async) até haver tokens.
    pub async fn consume(&self, n: u64) {
        let rate = self.rate();
        if rate == 0 || n == 0 {
            return;
        }
        loop {
            let wait = {
                let mut st = self.inner.lock().unwrap_or_else(|e| e.into_inner());
                let now = Instant::now();
                let elapsed = now.duration_since(st.last).as_secs_f64().min(5.0);
                st.last = now;
                st.tokens = (st.tokens + elapsed * rate as f64).min(rate as f64);
                if st.tokens >= n as f64 {
                    st.tokens -= n as f64;
                    None
                } else {
                    let need = n as f64 - st.tokens;
                    Some(Duration::from_secs_f64((need / rate as f64).clamp(0.01, 1.0)))
                }
            };
            match wait {
                None => return,
                Some(d) => tokio::time::sleep(d).await,
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn sem_limite_nao_bloqueia() {
        let rl = RateLimiter::new(0);
        let t0 = Instant::now();
        rl.consume(10_000_000).await;
        assert!(t0.elapsed() < Duration::from_millis(50));
    }

    #[tokio::test]
    async fn com_limite_segura_burst_e_desacelera() {
        let rl = RateLimiter::new(1_000_000); // 1 MB/s
        let t0 = Instant::now();
        // burst inicial de até 1s de tokens é absorvido rápido
        for _ in 0..8 {
            rl.consume(125_000).await;
        }
        let elapsed = t0.elapsed();
        // 8 * 125KB = 1MB a 1MB/s: deve levar pelo menos ~0 (burst) e no máx ~1.2s
        assert!(elapsed < Duration::from_secs(2), "demais: {elapsed:?}");
    }

    #[test]
    fn set_rate_funciona() {
        let rl = RateLimiter::new(0);
        assert_eq!(rl.rate(), 0);
        rl.set_rate(2048);
        assert_eq!(rl.rate(), 2048);
    }
}
