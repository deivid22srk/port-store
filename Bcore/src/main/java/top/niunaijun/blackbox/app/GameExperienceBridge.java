package top.niunaijun.blackbox.app;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Ponte mínima entre o motor (processos virtuais ":pN") e a experiência de
 * jogo do app hospedeiro (Port Store).
 *
 * O app hospedeiro registra um [Hook] cedo no ciclo de vida do processo
 * virtual (Application.onCreate). O delegate de Instrumentation usa a ponte
 * para:
 *
 *  - entregar teclas/toques da activity do jogo ANTES do jogo (dispatcher de
 *    Window.Callback), permitindo que o hospedeiro consuma somente o botão
 *    Home/Guide do gamepad e o gesto do menu rápido — todo o resto chega ao
 *    jogo exatamente como antes, com uma única chamada de método no caminho
 *    (sem fila, sem thread extra, sem latência perceptível);
 *  - notificar retomada/pausa das activities do jogo (ciclo de vida real);
 *  - segurar eventos genéricos (analógico) enquanto o menu rápido está aberto.
 *
 * A ponte NÃO adiciona dependência alguma ao Bcore: a interface vive aqui e
 * a implementação (UI clássica, strings, recursos) vive no app hospedeiro.
 */
public final class GameExperienceBridge {

    private GameExperienceBridge() {
    }

    /** Implementado pelo app hospedeiro; registrado uma vez por processo virtual. */
    public interface Hook {

        /**
         * Chamado antes do jogo receber a tecla. Retorna true para CONSUMIR
         * (o jogo não vê o evento) e false para delegar normalmente.
         */
        boolean onGameKeyEvent(Activity gameActivity, KeyEvent event);

        /**
         * Chamado antes do jogo receber o toque. Retorna true para CONSUMIR
         * (o jogo não vê o evento — usado quando o gesto do menu rápido dispara)
         * e false para delegar normalmente. Observação é barata: quando não há
         * gesto em andamento o custo é uma verificação por evento.
         */
        boolean onGameTouchEvent(Activity gameActivity, MotionEvent event);

        /** Uma activity do jogo ficou visível (onResume). */
        void onGameResumed(Activity gameActivity);

        /** Uma activity do jogo perdeu a frente (onPause). */
        void onGamePaused(Activity gameActivity);

        /**
         * Eventos genéricos (analógico, gatilhos analógicos, D-pad como
         * MotionEvent). Consumido pelo menu rápido aberto para o analógico
         * não vazar para o jogo; delega em qualquer outro caso.
         */
        boolean onGameGenericMotionEvent(Activity gameActivity, MotionEvent event);
    }

    /** Registro estático: processos virtuais têm um único hook por processo. */
    public static volatile Hook hook;
}
