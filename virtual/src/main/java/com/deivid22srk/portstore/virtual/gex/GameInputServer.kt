package com.deivid22srk.portstore.virtual.gex

import android.app.Activity
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import top.niunaijun.blackbox.utils.Slog
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Servidor de input da experiência de jogo EMBEDADA (jogo renderizado numa
 * VirtualDisplay do hospedeiro).
 *
 * Janela em display virtual não recebe input do sistema — o Game Launcher
 * (hospedeiro) lê toque/gamepad na UI dele e entrega aqui por um socket
 * local (mesmo uid, sem permissão nova). Cada evento é reconstruído como
 * MotionEvent/KeyEvent e despachado DIRETO na activity do jogo na thread
 * principal — o caminho é idêntico ao de um toque real depois do DecorView,
 * sem tocar no Window.Callback (o dispatcher continua intocado para o modo
 * de fallback em tela cheia).
 *
 * Protocolo (DataOutputStream, big-endian):
 *  - hospedeiro -> jogo: MSG_MOTION (toque/analógico), MSG_KEY (teclas);
 *  - jogo -> hospedeiro: MSG_HELLO (confirma embedding + displayId).
 *
 * Nome do socket: determinístico por pacote do jogo
 * ("portstore_gex_<vpkg>") no namespace abstract — o hospedeiro precisa
 * reconectar num relançamento sem saber se o processo do jogo sobreviveu.
 * Um único cliente por vez: a conexão mais recente substitui a anterior.
 */
object GameInputServer {

    private const val TAG = "GexInputServer"

    private const val MSG_MOTION = 1
    private const val MSG_KEY = 2
    private const val MSG_HELLO = 3

    /** kind do MSG_MOTION. */
    private const val KIND_TOUCH = 0
    private const val KIND_GENERIC = 1

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var serverThread: Thread? = null

    @Volatile
    private var activeSocketName: String? = null

    private val connection = AtomicReference<ServerConnection?>(null)

    private class ServerConnection(
        val socket: LocalSocket,
        val input: DataInputStream,
        val output: DataOutputStream,
    ) {
        fun closeQuietly() {
            runCatching { socket.close() }
        }
    }

    /**
     * Liga o servidor no [name] (namespace abstract). Idempotente: nome igual
     * com servidor vivo não reinicia; nome diferente fecha o antigo e abre o
     * novo (relançamento do jogo com o processo ainda de pé).
     */
    @Synchronized
    fun start(name: String, displayId: Int) {
        if (activeSocketName == name && serverThread?.isAlive == true) return
        connection.getAndSet(null)?.closeQuietly()
        activeSocketName = name
        val thread = Thread({ runServer(name, displayId) }, "gex-input-server")
        thread.isDaemon = true
        serverThread = thread
        thread.start()
        Slog.i(TAG, "gex input server on '$name' (display $displayId)")
    }

    private fun runServer(name: String, displayId: Int) {
        while (!Thread.currentThread().isInterrupted) {
            var server: LocalServerSocket? = null
            try {
                server = LocalServerSocket(name)
                while (!Thread.currentThread().isInterrupted) {
                    val socket = server.accept() ?: break
                    // Cliente mais novo ganha: encerra a conexão anterior.
                    val previous = connection.getAndSet(null)
                    previous?.closeQuietly()
                    val conn = ServerConnection(
                        socket,
                        DataInputStream(socket.inputStream.buffered()),
                        DataOutputStream(socket.outputStream.buffered()),
                    )
                    connection.set(conn)
                    // Confirma embedding: o hospedeiro só sai do "aguardando
                    // vídeo" quando receber este HELLO.
                    conn.output.writeInt(MSG_HELLO)
                    conn.output.writeInt(displayId)
                    conn.output.flush()
                    try {
                        readLoop(conn)
                    } finally {
                        // Saiu (EOF/erro): libera a conexão corrente.
                        connection.getAndSet(null)?.closeQuietly()
                    }
                }
            } catch (_: EOFException) {
                // Hospedeiro desconectou (saiu da tela do jogo): volta ao accept.
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                Slog.w(TAG, "gex input server: ${t.javaClass.simpleName}: ${t.message}")
                // Erro de bind (nome ocupado) ou I/O grave: pequena pausa para
                // não girar em falso.
                try {
                    Thread.sleep(150)
                } catch (_: InterruptedException) {
                    break
                }
            } finally {
                runCatching { server?.close() }
            }
        }
    }

    private fun readLoop(conn: ServerConnection) {
        while (true) {
            when (conn.input.readInt()) {
                MSG_MOTION -> readMotion(conn.input)
                MSG_KEY -> readKey(conn.input)
                else -> throw IOException("tipo de mensagem desconhecido")
            }
        }
    }

    private fun readMotion(input: DataInputStream) {
        val downTime = input.readLong()
        val eventTime = input.readLong()
        val action = input.readInt()
        val metaState = input.readInt()
        val buttonState = input.readInt()
        val source = input.readInt()
        val deviceId = input.readInt()
        val flags = input.readInt()
        val kind = input.readInt()
        val count = input.readInt()
        val properties = Array(count) {
            MotionEvent.PointerProperties().apply {
                id = input.readInt()
                toolType = input.readInt()
            }
        }
        val coords = Array(count) {
            MotionEvent.PointerCoords().apply {
                repeat(input.readInt()) {
                    setAxisValue(input.readInt(), input.readFloat())
                }
            }
        }
        mainHandler.post {
            val activity = GexEmbedding.currentGameActivity() ?: return@post
            runCatching {
                val event = MotionEvent.obtain(
                    downTime, eventTime, action, count, properties, coords,
                    metaState, buttonState, 0f, 0f, deviceId, 0, source, flags,
                )
                if (kind == KIND_TOUCH) {
                    activity.dispatchTouchEvent(event)
                } else {
                    activity.dispatchGenericMotionEvent(event)
                }
                event.recycle()
            }.onFailure { Slog.w(TAG, "motion dispatch: ${it.message}") }
        }
    }

    private fun readKey(input: DataInputStream) {
        val downTime = input.readLong()
        val eventTime = input.readLong()
        val action = input.readInt()
        val keyCode = input.readInt()
        val repeat = input.readInt()
        val metaState = input.readInt()
        val source = input.readInt()
        val deviceId = input.readInt()
        val scanCode = input.readInt()
        val flags = input.readInt()
        mainHandler.post {
            val activity = GexEmbedding.currentGameActivity() ?: return@post
            runCatching {
                val event = KeyEvent(
                    downTime, eventTime, action, keyCode, repeat, metaState,
                    deviceId, scanCode, flags, source,
                )
                activity.dispatchKeyEvent(event)
            }.onFailure { Slog.w(TAG, "key dispatch: ${it.message}") }
        }
    }

    /** Referência de activity entregue pelo [GexEmbedding] (criação da activity). */
    internal fun targetActivity(): Activity? = GexEmbedding.currentGameActivity()
}
