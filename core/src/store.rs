//! Persistência transacional do motor (SQLite embutido via rusqlite).
//!
//! É o "store" da arquitetura alvo: tarefas, offsets de partes, headers de
//! sessão e hash esperado — garantindo recuperação pós-crash. O arquivo
//! `.state` JSON continua sendo a moeda de retomada em disco (compatível com
//! instalações antigas e com a limpeza do Kotlin); o SQLite é o catálogo
//! durável que permite reconstruir jobs inteiros (incl. sessão de captura)
//! depois de morte do processo.
//!
//! Privacidade: a tabela `tasks` guarda COOKIES de sessão. Nenhum método
//! desta página os registra em log; consultas de debug nunca devem fazer
//! `SELECT *` sem seleção de colunas.

use crate::error::DownloadError;
use crate::fsm::State;
use rusqlite::Connection;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

/// Linha de tarefa (espelha a tabela `tasks` + partes pendentes carregadas).
#[derive(Debug, Clone, Default)]
pub struct TaskRow {
    pub id: String,
    pub url: String,
    pub dest: String,
    pub state: String,
    pub total: u64,
    pub downloaded: u64,
    pub accept_ranges: bool,
    pub priority: i32,
    pub expected_sha256: Option<String>,
    pub expected_md5: Option<String>,
    pub is_apk: bool,
    pub estimated_size: u64,
    pub user_agent: String,
    pub cookies: Option<String>,
    pub referer: Option<String>,
    /// JSON `[[k,v],...]` (headers extra da captura).
    pub extra_headers: Option<String>,
    /// 'native' | 'browser' | '' (vazio = não é captura).
    pub transport: String,
    pub etag: Option<String>,
    pub last_modified: Option<String>,
    pub error: Option<String>,
    pub created_at: i64,
    pub updated_at: i64,
    /// Partes pendentes (carregadas em `get`/`recoverable`).
    pub pending: Vec<(u64, u64)>,
}

impl TaskRow {
    /// JobOptions reconstruído (recuperação pós-crash).
    pub fn options(&self) -> crate::engine::JobOptions {
        crate::engine::JobOptions {
            expected_sha256: self.expected_sha256.clone(),
            expected_md5: self.expected_md5.clone(),
            is_apk: self.is_apk,
            estimated_size: self.estimated_size,
            priority: self.priority,
            speed_limit_bps: 0, // limite por job não sobrevive ao processo (config global)
        }
    }

    /// Sessão de captura reconstruída (None para jobs nativos).
    pub fn session(&self) -> Option<crate::webview_bridge::CaptureSession> {
        if self.transport.is_empty() {
            return None;
        }
        let extra: Vec<(String, String)> = self
            .extra_headers
            .as_deref()
            .and_then(|j| serde_json::from_str(j).ok())
            .unwrap_or_default();
        Some(crate::webview_bridge::CaptureSession {
            user_agent: if self.user_agent.is_empty() {
                crate::UA_FALLBACK.to_string()
            } else {
                self.user_agent.clone()
            },
            cookies: self.cookies.clone().filter(|c| !c.is_empty()),
            referer: self.referer.clone().filter(|r| !r.is_empty()),
            extra_headers: extra,
            transport: if self.transport == "native" {
                crate::webview_bridge::Transport::Native
            } else {
                crate::webview_bridge::Transport::Browser
            },
        })
    }

    pub fn dest_path(&self) -> PathBuf {
        PathBuf::from(&self.dest)
    }

    pub fn state(&self) -> Option<State> {
        State::from_str(&self.state)
    }
}

/// Versão do schema (tabela `meta`). Migrações futuras: bump + passos.
const SCHEMA_VERSION: &str = "1";

const SCHEMA_SQL: &str = r#"
CREATE TABLE IF NOT EXISTS tasks (
    id              TEXT PRIMARY KEY,
    url             TEXT NOT NULL,
    dest            TEXT NOT NULL,
    state           TEXT NOT NULL DEFAULT 'Queued',
    total           INTEGER NOT NULL DEFAULT 0,
    downloaded      INTEGER NOT NULL DEFAULT 0,
    accept_ranges   INTEGER NOT NULL DEFAULT 0,
    priority        INTEGER NOT NULL DEFAULT 0,
    expected_sha256 TEXT,
    expected_md5    TEXT,
    is_apk          INTEGER NOT NULL DEFAULT 0,
    estimated_size  INTEGER NOT NULL DEFAULT 0,
    user_agent      TEXT NOT NULL DEFAULT '',
    cookies         TEXT,
    referer         TEXT,
    extra_headers   TEXT,
    transport       TEXT NOT NULL DEFAULT '',
    etag            TEXT,
    last_modified   TEXT,
    error           TEXT,
    created_at      INTEGER NOT NULL DEFAULT 0,
    updated_at      INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS parts (
    task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    start   INTEGER NOT NULL,
    end     INTEGER NOT NULL,
    PRIMARY KEY (task_id, start)
);
CREATE INDEX IF NOT EXISTS idx_tasks_state ON tasks(state);
"#;

pub struct Store {
    conn: Mutex<Connection>,
}

fn se(v: rusqlite::Error) -> DownloadError {
    DownloadError::Storage(v.to_string())
}

impl Store {
    /// Abre (ou cria) o banco em `dir/engine.db` e aplica migrações.
    pub fn open(dir: &Path) -> Result<Store, DownloadError> {
        std::fs::create_dir_all(dir).map_err(DownloadError::from)?;
        let db = dir.join("engine.db");
        let conn = Connection::open(&db).map_err(se)?;
        Self::init(conn)
    }

    /// Banco em memória (testes).
    pub fn open_memory() -> Result<Store, DownloadError> {
        Self::init(Connection::open_in_memory().map_err(se)?)
    }

    fn init(conn: Connection) -> Result<Store, DownloadError> {
        conn.pragma_update(None, "journal_mode", "WAL").map_err(se)?;
        conn.pragma_update(None, "synchronous", "NORMAL").map_err(se)?;
        conn.busy_timeout(std::time::Duration::from_secs(5)).map_err(se)?;
        conn.execute_batch(SCHEMA_SQL).map_err(se)?;
        // Migração: por enquanto só grava a versão; bumps futuros executam
        // passos condicionais aqui (comparando a versão anterior).
        let cur: Option<String> = conn
            .query_row("SELECT value FROM meta WHERE key='schema_version'", [], |r| r.get(0))
            .ok();
        match cur {
            Some(v) if v == SCHEMA_VERSION => {}
            None => {
                conn.execute_batch("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);")
                    .map_err(se)?;
                conn.execute(
                    "INSERT OR REPLACE INTO meta(key, value) VALUES ('schema_version', ?1)",
                    [SCHEMA_VERSION],
                )
                .map_err(se)?;
            }
            Some(v) => {
                return Err(DownloadError::Storage(format!(
                    "schema_version {v} desconhecida (binário antigo?)"
                )))
            }
        }
        Ok(Store { conn: Mutex::new(conn) })
    }

    fn row_from(conn: &Connection, id: &str) -> Result<Option<TaskRow>, DownloadError> {
        let mut stmt = conn
            .prepare(
                "SELECT id,url,dest,state,total,downloaded,accept_ranges,priority,\
                 expected_sha256,expected_md5,is_apk,estimated_size,user_agent,cookies,\
                 referer,extra_headers,transport,etag,last_modified,error,created_at,updated_at \
                 FROM tasks WHERE id=?1",
            )
            .map_err(se)?;
        let mut rows = stmt.query([id]).map_err(se)?;
        match rows.next().map_err(se)? {
            None => Ok(None),
            Some(r) => {
                let mut row = row_from_db(r)?;
                let mut pstmt = conn
                    .prepare("SELECT start, end FROM parts WHERE task_id=?1 ORDER BY start")
                    .map_err(se)?;
                let mut prs = pstmt.query([id]).map_err(se)?;
                while let Some(p) = prs.next().map_err(se)? {
                    let s: i64 = p.get(0).map_err(se)?;
                    let e: i64 = p.get(0 + 1).map_err(se)?;
                    row.pending.push((s.max(0) as u64, e.max(0) as u64));
                }
                Ok(Some(row))
            }
        }
    }

    /// Insere ou substitui a tarefa inteira (criação/recriação de job).
    pub fn upsert_task(&self, row: &TaskRow, pending: &[(u64, u64)]) -> Result<(), DownloadError> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut tx = conn.unchecked_transaction().map_err(se)?;
        tx.execute(
            "INSERT OR REPLACE INTO tasks\
             (id,url,dest,state,total,downloaded,accept_ranges,priority,\
              expected_sha256,expected_md5,is_apk,estimated_size,user_agent,cookies,\
              referer,extra_headers,transport,etag,last_modified,error,created_at,updated_at) \
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18,?19,?20,?21,?22)",
            rusqlite::params![
                row.id,
                row.url,
                row.dest,
                row.state,
                row.total as i64,
                row.downloaded as i64,
                row.accept_ranges as i64,
                row.priority,
                row.expected_sha256,
                row.expected_md5,
                row.is_apk as i64,
                row.estimated_size as i64,
                row.user_agent,
                row.cookies,
                row.referer,
                row.extra_headers,
                row.transport,
                row.etag,
                row.last_modified,
                row.error,
                row.created_at,
                row.updated_at,
            ],
        )
        .map_err(se)?;
        tx.execute("DELETE FROM parts WHERE task_id=?1", [&row.id]).map_err(se)?;
        for (s, e) in pending {
            tx.execute(
                "INSERT OR REPLACE INTO parts(task_id, start, end) VALUES (?1, ?2, ?3)",
                rusqlite::params![row.id, *s as i64, *e as i64],
            )
            .map_err(se)?;
        }
        tx.commit().map_err(se)
    }

    /// Progresso + offsets (throttle 3 s — mesmo ciclo do `.state`).
    pub fn save_progress(
        &self,
        id: &str,
        downloaded: u64,
        total: u64,
        etag: Option<&str>,
        last_modified: Option<&str>,
        pending: &[(u64, u64)],
    ) -> Result<(), DownloadError> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut tx = conn.unchecked_transaction().map_err(se)?;
        tx.execute(
            "UPDATE tasks SET downloaded=?2, total=?3, etag=?4, last_modified=?5, updated_at=?6 WHERE id=?1",
            rusqlite::params![
                id,
                downloaded as i64,
                total as i64,
                etag,
                last_modified,
                crate::download::now_secs() as i64
            ],
        )
        .map_err(se)?;
        tx.execute("DELETE FROM parts WHERE task_id=?1", [id]).map_err(se)?;
        for (s, e) in pending {
            tx.execute(
                "INSERT OR REPLACE INTO parts(task_id, start, end) VALUES (?1, ?2, ?3)",
                rusqlite::params![id, *s as i64, *e as i64],
            )
            .map_err(se)?;
        }
        tx.commit().map_err(se)
    }

    pub fn set_state(&self, id: &str, state: State, error: Option<&str>) -> Result<(), DownloadError> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute(
            "UPDATE tasks SET state=?2, error=?3, updated_at=?4 WHERE id=?1",
            rusqlite::params![id, state.as_str(), error, crate::download::now_secs() as i64],
        )
        .map_err(se)?;
        Ok(())
    }

    pub fn set_priority(&self, id: &str, priority: i32) -> Result<(), DownloadError> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute(
            "UPDATE tasks SET priority=?2, updated_at=?3 WHERE id=?1",
            rusqlite::params![id, priority, crate::download::now_secs() as i64],
        )
        .map_err(se)?;
        Ok(())
    }

    pub fn remove(&self, id: &str) -> Result<(), DownloadError> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute("DELETE FROM tasks WHERE id=?1", [id]).map_err(se)?;
        Ok(())
    }

    pub fn get(&self, id: &str) -> Result<Option<TaskRow>, DownloadError> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        Self::row_from(&conn, id)
    }

    /// Tarefas NÃO terminais (recuperação pós-crash — o engine decide o que
    /// re-anima: Paused volta pro mapa sem spawn; ativos re-executam).
    pub fn recoverable(&self) -> Result<Vec<TaskRow>, DownloadError> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut stmt = conn
            .prepare(
                "SELECT id FROM tasks WHERE state NOT IN ('Completed','Failed','Canceled') ORDER BY priority DESC, created_at ASC",
            )
            .map_err(se)?;
        let ids: Vec<String> = stmt
            .query_map([], |r| r.get(0))
            .map_err(se)?
            .filter_map(|r| r.ok())
            .collect();
        drop(stmt);
        let mut out = Vec::with_capacity(ids.len());
        for id in ids {
            if let Some(row) = Self::row_from(&conn, &id)? {
                out.push(row);
            }
        }
        Ok(out)
    }
}

fn row_from_db(r: &rusqlite::Row) -> Result<TaskRow, DownloadError> {
    Ok(TaskRow {
        id: r.get(0).map_err(se)?,
        url: r.get(1).map_err(se)?,
        dest: r.get(2).map_err(se)?,
        state: r.get(3).map_err(se)?,
        total: r.get::<_, i64>(4).map_err(se)?.max(0) as u64,
        downloaded: r.get::<_, i64>(5).map_err(se)?.max(0) as u64,
        accept_ranges: r.get::<_, i64>(6).map_err(se)? != 0,
        priority: r.get(7).map_err(se)?,
        expected_sha256: r.get(8).map_err(se)?,
        expected_md5: r.get(9).map_err(se)?,
        is_apk: r.get::<_, i64>(10).map_err(se)? != 0,
        estimated_size: r.get::<_, i64>(11).map_err(se)?.max(0) as u64,
        user_agent: r.get(12).map_err(se)?,
        cookies: r.get(13).map_err(se)?,
        referer: r.get(14).map_err(se)?,
        extra_headers: r.get(15).map_err(se)?,
        transport: r.get(16).map_err(se)?,
        etag: r.get(17).map_err(se)?,
        last_modified: r.get(18).map_err(se)?,
        error: r.get(19).map_err(se)?,
        created_at: r.get(20).map_err(se)?,
        updated_at: r.get(21).map_err(se)?,
        pending: Vec::new(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(id: &str) -> (TaskRow, Vec<(u64, u64)>) {
        let row = TaskRow {
            id: id.into(),
            url: "https://cdn.example.com/a.bin".into(),
            dest: "/tmp/a.bin".into(),
            state: "Downloading".into(),
            total: 1000,
            downloaded: 400,
            accept_ranges: true,
            priority: 5,
            expected_sha256: Some("ab".repeat(32)),
            expected_md5: None,
            is_apk: false,
            estimated_size: 1000,
            user_agent: "UA/1.0".into(),
            cookies: Some("a=b; c=d".into()),
            referer: Some("https://page/".into()),
            extra_headers: Some(r#"[["X-A","1"],["X-B","2"]]"#.into()),
            transport: "browser".into(),
            etag: Some("\"v1\"".into()),
            last_modified: Some("yesterday".into()),
            error: None,
            created_at: 111,
            updated_at: 222,
            pending: Vec::new(),
        };
        (row, vec![(400, 700), (701, 999)])
    }

    #[test]
    fn roundtrip_completo() {
        let st = Store::open_memory().unwrap();
        let (row, pending) = sample("job1");
        st.upsert_task(&row, &pending).unwrap();

        let back = st.get("job1").unwrap().unwrap();
        assert_eq!(back.url, row.url);
        assert_eq!(back.downloaded, 400);
        assert_eq!(back.priority, 5);
        assert!(back.accept_ranges);
        assert_eq!(back.cookies.as_deref(), Some("a=b; c=d"));
        assert_eq!(back.pending, vec![(400, 700), (701, 999)]);

        let sess = back.session().unwrap();
        assert_eq!(sess.transport, crate::webview_bridge::Transport::Browser);
        assert_eq!(sess.cookies.as_deref(), Some("a=b; c=d"));
        assert_eq!(sess.extra_headers, vec![("X-A".into(), "1".into()), ("X-B".into(), "2".into())]);
        assert_eq!(back.options().expected_sha256.as_deref(), Some("ab".repeat(32).as_str()));
    }

    #[test]
    fn progresso_e_partes_sao_transacionais() {
        let st = Store::open_memory().unwrap();
        let (row, _) = sample("job2");
        st.upsert_task(&row, &[]).unwrap();
        st.save_progress("job2", 900, 1000, Some("\"v2\""), None, &[(900, 999)])
            .unwrap();
        let back = st.get("job2").unwrap().unwrap();
        assert_eq!(back.downloaded, 900);
        assert_eq!(back.etag.as_deref(), Some("\"v2\""));
        assert_eq!(back.pending, vec![(900, 999)]);
    }

    #[test]
    fn recoveravel_exclui_terminais() {
        let st = Store::open_memory().unwrap();
        let (mut row, _) = sample("ativo");
        row.state = "Downloading".into();
        st.upsert_task(&row, &[]).unwrap();
        let (mut done, _) = sample("pronto");
        done.state = "Completed".into();
        st.upsert_task(&done, &[]).unwrap();

        let rec = st.recoverable().unwrap();
        assert_eq!(rec.len(), 1);
        assert_eq!(rec[0].id, "ativo");

        // estado terminal por set_state sai da recuperação
        st.set_state("ativo", State::Failed, Some("boom")).unwrap();
        assert!(st.recoverable().unwrap().is_empty());
        let back = st.get("ativo").unwrap().unwrap();
        assert_eq!(back.error.as_deref(), Some("boom"));
    }

    #[test]
    fn priority_ordena_recuperacao() {
        let st = Store::open_memory().unwrap();
        let (mut low, _) = sample("low");
        low.priority = 0;
        st.upsert_task(&low, &[]).unwrap();
        let (mut high, _) = sample("high");
        high.id = "high".into();
        high.priority = 9;
        st.upsert_task(&high, &[]).unwrap();
        let rec = st.recoverable().unwrap();
        assert_eq!(rec[0].id, "high");
    }

    #[test]
    fn remove_e_schema_estavel() {
        let st = Store::open_memory().unwrap();
        let (row, _) = sample("x");
        st.upsert_task(&row, &[]).unwrap();
        st.remove("x").unwrap();
        assert!(st.get("x").unwrap().is_none());
        assert!(st.remove("x").is_ok(), "remover inexistente é ok");

        // abrir duas vezes (migração idempotente)
        let dir = std::env::temp_dir().join(format!("ps_store_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let a = Store::open(&dir).unwrap();
        let (row, _) = sample("persistente");
        a.upsert_task(&row, &[]).unwrap();
        drop(a);
        let b = Store::open(&dir).unwrap();
        assert!(b.get("persistente").unwrap().is_some());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn job_nativo_sem_sessao() {
        let st = Store::open_memory().unwrap();
        let (mut row, _) = sample("nat");
        row.transport = String::new();
        st.upsert_task(&row, &[]).unwrap();
        let back = st.get("nat").unwrap().unwrap();
        assert!(back.session().is_none());
    }
}
