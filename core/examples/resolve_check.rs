//! Verificação manual do resolvedor contra o provedor real (não roda no CI).
//! Uso: cargo run --release --example resolve_check -- https://buzzheavier.com/<id>

#[tokio::main]
async fn main() {
    let url = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "https://buzzheavier.com/cqt08005094h".to_string());
    println!("resolvendo: {url}");
    match portstore_core::buzzheavier::resolve_direct_link(&url, None).await {
        Ok(direct) => println!("OK: {direct}"),
        Err(e) => println!("ERRO kind={} msg={}", e.kind(), e.message()),
    }
}
