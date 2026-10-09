//! Matemática de segmentos de download (intervalos inclusivos [start, end]).

/// Divide `total` em até `n` segmentos inclusivos. `total == 0` => vazio.
/// Se `total < n`, gera `total` segmentos de 1 byte (ou nenhum, se 0).
pub fn plan_segments(total: u64, n: usize) -> Vec<(u64, u64)> {
    let n = n.max(1);
    if total == 0 {
        return Vec::new();
    }
    let count = (n as u64).min(total) as usize;
    let chunk = total / count as u64;
    let rem = total % count as u64;
    let mut out = Vec::with_capacity(count);
    let mut start: u64 = 0;
    for i in 0..count {
        let len = chunk + if (i as u64) < rem { 1 } else { 0 };
        let end = start + len - 1;
        out.push((start, end));
        start = end + 1;
    }
    out
}

/// Divide um intervalo em dois (para "roubo" dinâmico). Retorna None se
/// pequeno demais (min_chunk) para valer a pena.
pub fn split_range(s: u64, e: u64, min_chunk: u64) -> Option<((u64, u64), (u64, u64))> {
    if e < s {
        return None;
    }
    let len = e - s + 1;
    if len < min_chunk * 2 {
        return None;
    }
    let half = len / 2;
    let a = (s, s + half - 1);
    let b = (s + half, e);
    Some((a, b))
}

/// Bytes restantes de um intervalo inclusivo.
pub fn range_len(s: u64, e: u64) -> u64 {
    if e < s {
        0
    } else {
        e - s + 1
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plano_cobre_todo_o_arquivo_sem_sobreposicao() {
        for total in [0u64, 1, 7, 100, 1_000_007, 10_000_000] {
            for n in [1usize, 2, 4, 6, 16] {
                let segs = plan_segments(total, n);
                assert!(!segs.is_empty() || total == 0);
                let sum: u64 = segs.iter().map(|(s, e)| range_len(*s, *e)).sum();
                assert_eq!(sum, total, "total={total} n={n} segs={segs:?}");
                // contíguos e ordenados
                for w in segs.windows(2) {
                    assert_eq!(w[0].1 + 1, w[1].0, "segmentos não contíguos: {segs:?}");
                }
                // nenhum segmento vazio
                for (s, e) in &segs {
                    assert!(e >= s);
                }
                // no máximo n segmentos
                assert!(segs.len() <= n.max(1));
            }
        }
    }

    #[test]
    fn arquivo_menor_que_numero_de_segmentos() {
        let segs = plan_segments(3, 8);
        assert_eq!(segs, vec![(0, 0), (1, 1), (2, 2)]);
    }

    #[test]
    fn split_respeita_min_chunk() {
        assert!(split_range(0, 10, 6).is_none(), "10 bytes < 2*6");
        let (a, b) = split_range(0, 15, 4).unwrap();
        assert_eq!(a.0, 0);
        assert_eq!(b.1, 15);
        assert_eq!(range_len(a.0, a.1) + range_len(b.0, b.1), 16);
        assert!(a.1 < b.0, "metades disjuntas");
    }

    #[test]
    fn len_inclusivo() {
        assert_eq!(range_len(5, 5), 1);
        assert_eq!(range_len(0, 9), 10);
        assert_eq!(range_len(9, 5), 0);
    }
}
