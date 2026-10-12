//! Ponte JNI: funções nativas chamadas pelo Kotlin (NativeDownloader object).
//!
//! Nomes exatos esperados pela convenção JNI:
//!   Java_com_deivid22srk_portstore_core_NativeDownloader_<método>

use crate::callback::{EventSink, JniCallback};
use crate::engine::{Config, Engine, JobOptions};

use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;

fn jstr_to_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|js| js.into())
        .unwrap_or_default()
}

fn engine_or_none() -> Option<Arc<Engine>> {
    crate::engine().cloned()
}

#[no_mangle]
pub extern "system" fn Java_com_deivid22srk_portstore_core_NativeDownloader_nativeInit(
    mut env: JNIEnv,
    _thiz: JObject,
    config: JString,
    callback: JObject,
) {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<(), String> {
        crate::init_logger();
        crate::init_crypto();
        log::info!("nativeInit");

        let vm = env.get_java_vm().map_err(|e| format!("java vm: {e}"))?;
        let global = env
            .new_global_ref(callback)
            .map_err(|e| format!("global ref: {e}"))?;
        let cfg_json = jstr_to_string(&mut env, &config);
        let config: Config = serde_json::from_str(&cfg_json).unwrap_or_default();

        if crate::engine().is_none() {
            let sink: Arc<dyn EventSink> = Arc::new(JniCallback::new(vm, global));
            let engine = Engine::new(config, sink).map_err(|e| format!("client: {e}"))?;
            let _ = crate::set_engine(engine.clone());
            crate::engine::spawn_progress_notifier(engine.clone());
            crate::engine::spawn_state_persister(engine);
        }
        Ok(())
    }));

    match result {
        Ok(Ok(())) => {}
        Ok(Err(e)) => log::error!("nativeInit: {e}"),
        Err(_) => log::error!("nativeInit: panic capturado"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_deivid22srk_portstore_core_NativeDownloader_nativeEnqueue(
    mut env: JNIEnv,
    _thiz: JObject,
    id: JString,
    url: JString,
    dest_path: JString,
    options_json: JString,
) -> jboolean {
    let result = catch_unwind(AssertUnwindSafe(|| -> bool {
        let engine = match engine_or_none() {
            Some(e) => e,
            None => return false,
        };
        let id = jstr_to_string(&mut env, &id);
        let url = jstr_to_string(&mut env, &url);
        let dest = jstr_to_string(&mut env, &dest_path);
        let opts_json = jstr_to_string(&mut env, &options_json);
        if id.is_empty() || url.is_empty() || dest.is_empty() {
            return false;
        }
        let options: JobOptions = serde_json::from_str(&opts_json).unwrap_or_default();
        log::info!("enqueue {id}");
        engine.enqueue(&id, &url, &dest, options)
    }));
    match result {
        Ok(v) => {
            if v {
                1
            } else {
                0
            }
        }
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_deivid22srk_portstore_core_NativeDownloader_nativeEnqueueCaptured(
    mut env: JNIEnv,
    _thiz: JObject,
    id: JString,
    dest_path: JString,
    capture_json: JString,
) -> jboolean {
    // Enfileira um download CAPTURADO da WebView com a sessão completa
    // (cookies/UA/Referer/headers extra). `capture_json` segue o contrato
    // `CapturedDownload` (camelCase — ver `webview_bridge.rs`).
    // 1 = enfileirado (ou já ativo); 0 = motor ausente OU captura inválida.
    // Detalhe do erro (quando captura inválida) vai para o log — a UI mostra
    // mensagem amigável (nunca o JSON rejeitado, que pode conter cookies).
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<(), String> {
        let engine = match engine_or_none() {
            Some(e) => e,
            None => return Err("motor indisponível".into()),
        };
        let id = jstr_to_string(&mut env, &id);
        let dest = jstr_to_string(&mut env, &dest_path);
        let json = jstr_to_string(&mut env, &capture_json);
        if id.is_empty() || dest.is_empty() || json.is_empty() || json.len() > 256 * 1024 {
            return Err("argumentos de captura inválidos".into());
        }
        let capture: crate::webview_bridge::CapturedDownload =
            serde_json::from_str(&json).map_err(|e| format!("json da captura: {e}"))?;
        engine
            .enqueue_captured(&id, &dest, capture)
            .map_err(|e| e.technical())
    }));
    match result {
        Ok(Ok(())) => 1,
        Ok(Err(e)) => {
            log::warn!("nativeEnqueueCaptured rejeitado: {e}");
            0
        }
        Err(_) => {
            log::error!("nativeEnqueueCaptured: panic capturado");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_deivid22srk_portstore_core_NativeDownloader_nativePause(
    mut env: JNIEnv,
    _thiz: JObject,
    id: JString,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Some(engine) = engine_or_none() {
            let id = jstr_to_string(&mut env, &id);
            engine.pause(&id);
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_deivid22srk_portstore_core_NativeDownloader_nativeResume(
    mut env: JNIEnv,
    _thiz: JObject,
    id: JString,
) -> jboolean {
    let result = catch_unwind(AssertUnwindSafe(|| -> bool {
        if let Some(engine) = engine_or_none() {
            let id = jstr_to_string(&mut env, &id);
            engine.resume(&id)
        } else {
            false
        }
    }));
    match result {
        Ok(true) => 1,
        _ => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_deivid22srk_portstore_core_NativeDownloader_nativeCancel(
    mut env: JNIEnv,
    _thiz: JObject,
    id: JString,
    delete_file: jboolean,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Some(engine) = engine_or_none() {
            let id = jstr_to_string(&mut env, &id);
            engine.cancel(&id, delete_file != 0);
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_deivid22srk_portstore_core_NativeDownloader_nativeSetConfig(
    mut env: JNIEnv,
    _thiz: JObject,
    config: JString,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Some(engine) = engine_or_none() {
            let cfg_json = jstr_to_string(&mut env, &config);
            let cfg: Config = serde_json::from_str(&cfg_json).unwrap_or_default();
            engine.update_config(cfg);
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_deivid22srk_portstore_core_NativeDownloader_nativeSetNetworkState(
    _env: JNIEnv,
    _thiz: JObject,
    connected: jboolean,
    unmetered: jboolean,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Some(engine) = engine_or_none() {
            engine.set_network_state(connected != 0, unmetered != 0);
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_deivid22srk_portstore_core_NativeDownloader_nativeGetSnapshot(
    mut env: JNIEnv,
    _thiz: JObject,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> String {
        match engine_or_none() {
            Some(engine) => engine.snapshot_json(),
            None => "[]".to_string(),
        }
    }));
    let json = result.unwrap_or_else(|_| "[]".to_string());
    match env.new_string(&json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
