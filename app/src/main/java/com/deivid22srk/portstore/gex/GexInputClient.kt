package com.deivid22srk.portstore.gex

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Cliente de input da experiência de jogo embedada (lado do hospedeiro).
 *
 * Conecta ao socket do processo do jogo (":pN", servidor em
 * `virtual/gex/GameInputServer`) e entrega eventos de toque/gamepad. O
 * servidor responde com um HELLO confirmando o embedding — o sinal de que o
 * jogo está de fato renderizando na VirtualDisplay do console.
 *
 * Reconexão: tenta a cada [RETRY_MS] até [budgetMs] esgotar (o servidor
 * sobe quando a activity do jogo é criada — centenas de ms depois do
 * launch). Todas as escritas são baratas (um record pequeno por evento) e
 * a leitura fica em thread própria.
 */
class GexInputClient(
    private val socketName: String,
    private val onHello: (displayId: Int) -> Unit,
    private val onGiveUp: () -> Unit,
) {

    private companion object {
        const val TAG = "GexInputClient"
        const val MSG_MOTION = 1
        const val MSG_KEY = 2
        const val MSG_HELLO = 3
        const val KIND_TOUCH = 0
        const val KIND_GENERIC = 1
        const val RETRY_MS = 250L

        /** Eixos serializados por ponteiro (valores de MotionEvent.AXIS_*). */
        val SERIALIZED_AXES = intArrayOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            MotionEvent.AXIS_PRESSURE,
            MotionEvent.AXIS_SIZE,
            MotionEvent.AXIS_TOUCH_MAJOR,
            MotionEvent.AXIS_TOUCH_MINOR,
            MotionEvent.AXIS_TOOL_MAJOR,
            MotionEvent.AXIS_TOOL_MINOR,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y,
            MotionEvent.AXIS_GAS,
            MotionEvent.AXIS_BRAKE,
        )
    }

    private val socket = AtomicReference<LocalSocket?>(null)
    private val output = AtomicReference<DataOutputStream?>(null)
    private val closed = AtomicBoolean(false)
    private var helloSeen = false

    /**
     * Tenta conectar dentro do orçamento [budgetMs]. Chamar em thread de
     * fundo (IO). Ao conectar, inicia a leitura (HELLO) e libera as escritas.
     */
    fun connect(budgetMs: Long) {
        val deadline = System.currentTimeMillis() + budgetMs
        while (!closed.get() && System.currentTimeMillis() < deadline) {
            try {
                val s = LocalSocket()
                s.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                val out = DataOutputStream(BufferedOutputStream(s.outputStream, 32 * 1024))
                socket.set(s)
                output.set(out)
                readLoop(DataInputStream(s.inputStream.buffered()))
                return
            } catch (_: IOException) {
                // Servidor ainda não subiu: tenta de novo até o orçamento acabar.
                runCatching { socket.getAndSet(null)?.close() }
                output.set(null)
                if (closed.get()) return
                try {
                    Thread.sleep(RETRY_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
        if (!closed.get() && !helloSeen) {
            onGiveUp()
        }
    }

    private fun readLoop(input: DataInputStream) {
        try {
            while (!closed.get()) {
                when (input.readInt()) {
                    MSG_HELLO -> {
                        val displayId = input.readInt()
                        if (!helloSeen) {
                            helloSeen = true
                            onHello(displayId)
                        }
                    }

                    else -> throw IOException("mensagem inesperada do processo do jogo")
                }
            }
        } catch (_: EOFException) {
            // Processo do jogo fechou a conexão — sessão caiu.
            if (!closed.get()) onGiveUp()
        } catch (_: IOException) {
            if (!closed.get() && helloSeen) onGiveUp()
        }
    }

    /** Envia um toque (kind=touch) ou evento genérico/analógico (kind=generic). */
    fun sendMotion(event: MotionEvent, scaleX: Float, scaleY: Float, generic: Boolean) {
        val out = output.get() ?: return
        try {
            synchronized(this) {
                out.writeInt(MSG_MOTION)
                out.writeLong(event.downTime)
                out.writeLong(event.eventTime)
                out.writeInt(event.action)
                out.writeInt(event.metaState)
                out.writeInt(event.buttonState)
                out.writeInt(event.source)
                out.writeInt(event.deviceId)
                out.writeInt(event.flags)
                out.writeInt(if (generic) KIND_GENERIC else KIND_TOUCH)
                val n = event.pointerCount
                out.writeInt(n)
                for (i in 0 until n) {
                    val props = event.getPointerProperties(i)
                    out.writeInt(props.id)
                    out.writeInt(props.toolType)
                    val coords = event.getPointerCoords(i)
                    out.writeInt(SERIALIZED_AXES.size)
                    for (axis in SERIALIZED_AXES) {
                        var value = coords.getAxisValue(axis)
                        if (axis == MotionEvent.AXIS_X) value *= scaleX
                        if (axis == MotionEvent.AXIS_Y) value *= scaleY
                        out.writeInt(axis)
                        out.writeFloat(value)
                    }
                }
                out.flush()
            }
        } catch (_: IOException) {
            // Conexão caiu: o loop de leitura cuida do estado.
        }
    }

    /** Envia uma tecla (gamepad/controle) para o jogo. */
    fun sendKey(event: KeyEvent) {
        val out = output.get() ?: return
        try {
            synchronized(this) {
                out.writeInt(MSG_KEY)
                out.writeLong(event.downTime)
                out.writeLong(event.eventTime)
                out.writeInt(event.action)
                out.writeInt(event.keyCode)
                out.writeInt(event.repeatCount)
                out.writeInt(event.metaState)
                out.writeInt(event.source)
                out.writeInt(event.deviceId)
                out.writeInt(event.scanCode)
                out.writeInt(event.flags)
                out.flush()
            }
        } catch (_: IOException) {
            // Conexão caiu: o loop de leitura cuida do estado.
        }
    }

    fun close() {
        if (closed.getAndSet(true)) return
        runCatching { socket.getAndSet(null)?.close() }
        output.set(null)
    }

    val isClosed: Boolean get() = closed.get()
}
