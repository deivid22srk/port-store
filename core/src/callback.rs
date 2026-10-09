//! Callbacks Rust → Kotlin via JNI (attach_current_thread + GlobalRef).

use jni::objects::{GlobalRef, JObject, JValue};
use jni::JavaVM;
use log::warn;

pub trait EventSink: Send + Sync {
    fn on_progress(&self, id: &str, downloaded: u64, total: u64, speed_bps: u64, eta_secs: u64);
    fn on_state(&self, id: &str, state: &str, error: Option<&str>);
}

/// No-op para testes.
pub struct NoopSink;

impl EventSink for NoopSink {
    fn on_progress(&self, _id: &str, _d: u64, _t: u64, _s: u64, _e: u64) {}
    fn on_state(&self, _id: &str, _st: &str, _err: Option<&str>) {}
}

/// Implementação JNI: guarda JavaVM + GlobalRef do callback Kotlin.
pub struct JniCallback {
    vm: JavaVM,
    obj: GlobalRef,
}

impl JniCallback {
    pub fn new(vm: JavaVM, obj: GlobalRef) -> Self {
        JniCallback { vm, obj }
    }
}

impl JniCallback {
    fn with_env(&self, f: impl FnOnce(&mut jni::JNIEnv, &JObject) -> jni::errors::Result<()>) {
        let guard = match self.vm.attach_current_thread() {
            Ok(g) => g,
            Err(e) => {
                warn!("JNI attach falhou: {e}");
                return;
            }
        };
        let env = &mut *guard;
        let obj = self.obj.as_obj();
        if let Err(e) = f(env, obj) {
            if matches!(e, jni::errors::Error::JavaException) {
                let _ = env.exception_clear();
            }
            warn!("callback JNI falhou: {e}");
        }
    }
}

impl EventSink for JniCallback {
    fn on_progress(&self, id: &str, downloaded: u64, total: u64, speed_bps: u64, eta_secs: u64) {
        self.with_env(|env, obj| {
            let jid = env.new_string(id)?;
            env.call_method(
                obj,
                "onProgress",
                "(Ljava/lang/String;JJJJ)V",
                &[
                    JValue::Object(&jid),
                    JValue::Long(downloaded as i64),
                    JValue::Long(total as i64),
                    JValue::Long(speed_bps as i64),
                    JValue::Long(eta_secs as i64),
                ],
            )?;
            Ok(())
        });
    }

    fn on_state(&self, id: &str, state: &str, error: Option<&str>) {
        self.with_env(|env, obj| {
            let jid = env.new_string(id)?;
            let jstate = env.new_string(state)?;
            let jerr = match error {
                Some(e) => {
                    let s = env.new_string(e)?;
                    Some(s)
                }
                None => None,
            };
            let err_ref: Option<&JObject> = jerr.as_ref().map(|s| s.as_ref());
            let err_value = match err_ref {
                Some(r) => JValue::Object(r),
                None => JValue::Object(&JObject::null()),
            };
            env.call_method(
                obj,
                "onStateChanged",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                &[JValue::Object(&jid), JValue::Object(&jstate), err_value],
            )?;
            Ok(())
        });
    }
}
