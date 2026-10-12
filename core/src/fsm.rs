//! Máquina de estados explícita do ciclo de vida de um download.
//!
//! Fluxo principal:
//! `Queued → Connecting → Downloading → Verifying → Extracting → Completed`
//! com desvios para `Paused` (usuário/rede), `Failed` (erro) e `Canceled`.
//!
//! A tabela `can_transition` é a fonte da verdade e é coberta por testes. O
//! engine APLICA qualquer estado solicitado (o fluxo real tem corridas —
//! pausa durante verificação, retry após falha) mas loga desvios da tabela,
//! de modo que uma transição impossível não passe despercebida.

use serde::{Deserialize, Serialize};

/// Estados de um download. `Extracting` cobre o pós-download (extração e
/// instalação coordenadas com o Kotlin — o conteúdo já está íntegro em disco).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum State {
    Queued,
    Connecting,
    Downloading,
    Paused,
    Verifying,
    Extracting,
    Completed,
    Failed,
    Canceled,
}

impl State {
    pub fn as_str(&self) -> &'static str {
        match self {
            State::Queued => "Queued",
            State::Connecting => "Connecting",
            State::Downloading => "Downloading",
            State::Paused => "Paused",
            State::Verifying => "Verifying",
            State::Extracting => "Extracting",
            State::Completed => "Completed",
            State::Failed => "Failed",
            State::Canceled => "Canceled",
        }
    }

    /// Aceita a string usada no JNI/snapshot (e no Kotlin `DlState`).
    pub fn from_str(s: &str) -> Option<State> {
        Some(match s {
            "Queued" => State::Queued,
            "Connecting" => State::Connecting,
            "Downloading" => State::Downloading,
            "Paused" => State::Paused,
            "Verifying" => State::Verifying,
            "Extracting" => State::Extracting,
            "Completed" => State::Completed,
            "Failed" => State::Failed,
            "Canceled" => State::Canceled,
            _ => return None,
        })
    }

    /// Terminal: não sai do estado sem recriar a tarefa (re-enfileirar).
    pub fn is_terminal(self) -> bool {
        matches!(self, State::Completed | State::Failed | State::Canceled)
    }

    /// Ativo: ocupa slot/fila (Paused segura a tarefa mas a mantém).
    pub fn is_active(self) -> bool {
        !self.is_terminal() && self != State::Paused
    }
}

/// Tabela de transições legais. `retry after failure` e `resume after pause`
/// voltam por `Queued` — o mesmo caminho do primeiro enfileiramento.
pub fn can_transition(from: State, to: State) -> bool {
    use State::*;
    if from == to {
        return true; // re-set idempotente ( Connecting→Connecting em retry )
    }
    matches!(
        (from, to),
        // agendamento
        (Queued, Connecting)
            | (Queued, Paused)
            | (Queued, Canceled)
            | (Queued, Failed)
            // conexão
            | (Connecting, Downloading)
            | (Connecting, Paused)
            | (Connecting, Canceled)
            | (Connecting, Failed)
            | (Connecting, Queued) // slot devolvido / espera de rede
            // transferência
            | (Downloading, Paused)
            | (Downloading, Verifying)
            | (Downloading, Canceled)
            | (Downloading, Failed)
            | (Downloading, Connecting) // nova tentativa de span
            // pausa do usuário
            | (Paused, Queued) // resume
            | (Paused, Canceled)
            | (Paused, Failed)
            // verificação pós-download
            | (Verifying, Extracting)
            | (Verifying, Completed) // sem extração configurada
            | (Verifying, Failed)
            | (Verifying, Canceled)
            | (Verifying, Connecting) // panic de verificação retryable
            // extração/instalação
            | (Extracting, Completed)
            | (Extracting, Failed)
            | (Extracting, Canceled)
            // re-enfileirar via UI (retry)
            | (Failed, Queued)
            | (Canceled, Queued)
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fluxo_feliz_completo() {
        let steps = [
            State::Queued,
            State::Connecting,
            State::Downloading,
            State::Verifying,
            State::Extracting,
            State::Completed,
        ];
        for w in steps.windows(2) {
            assert!(can_transition(w[0], w[1]), "{} → {}", w[0].as_str(), w[1].as_str());
        }
    }

    #[test]
    fn fluxo_sem_extracao() {
        assert!(can_transition(State::Verifying, State::Completed));
    }

    #[test]
    fn desvios_por_pausa_retry_e_falha() {
        assert!(can_transition(State::Downloading, State::Paused));
        assert!(can_transition(State::Paused, State::Queued));
        assert!(can_transition(State::Queued, State::Connecting));
        assert!(can_transition(State::Downloading, State::Failed));
        assert!(can_transition(State::Failed, State::Queued));
        assert!(can_transition(State::Downloading, State::Canceled));
        assert!(can_transition(State::Downloading, State::Connecting)); // retry de span
        assert!(can_transition(State::Verifying, State::Connecting)); // panic retryable
    }

    #[test]
    fn transicoes_ilegais() {
        assert!(!can_transition(State::Completed, State::Downloading));
        assert!(!can_transition(State::Completed, State::Failed));
        assert!(!can_transition(State::Failed, State::Completed));
        assert!(!can_transition(State::Queued, State::Completed)); // precisa baixar
        assert!(!can_transition(State::Downloading, State::Extracting)); // precisa verificar
        assert!(!can_transition(State::Extracting, State::Downloading));
        assert!(!can_transition(State::Canceled, State::Downloading));
        assert!(!can_transition(State::Paused, State::Downloading));
    }

    #[test]
    fn terminal_e_ativo() {
        assert!(State::Completed.is_terminal());
        assert!(State::Failed.is_terminal());
        assert!(State::Canceled.is_terminal());
        assert!(!State::Paused.is_terminal());
        for s in [State::Queued, State::Connecting, State::Downloading, State::Verifying, State::Extracting] {
            assert!(s.is_active(), "{} deveria ser ativo", s.as_str());
        }
        assert!(!State::Paused.is_active());
        assert!(!State::Completed.is_active());
    }

    #[test]
    fn strings_bate_com_kotlin() {
        // O Kotlin (DlState) usa exatamente estas strings — mudar aqui quebra a UI.
        for (s, txt) in [
            (State::Queued, "Queued"),
            (State::Connecting, "Connecting"),
            (State::Downloading, "Downloading"),
            (State::Paused, "Paused"),
            (State::Verifying, "Verifying"),
            (State::Extracting, "Extracting"),
            (State::Completed, "Completed"),
            (State::Failed, "Failed"),
            (State::Canceled, "Canceled"),
        ] {
            assert_eq!(s.as_str(), txt);
            assert_eq!(State::from_str(txt), Some(s));
        }
        assert_eq!(State::from_str("não-existe"), None);
    }
}
