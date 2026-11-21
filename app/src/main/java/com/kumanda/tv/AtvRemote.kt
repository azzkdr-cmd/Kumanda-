package com.kumanda.tv

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Android TV Remote v2 protokolu (Google TV uygulamasinin kullandigi protokol).
 * 6467: eslestirme, 6466: kumanda.
 */
class AtvRemote(context: Context, private val listener: Listener) {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, NEEDS_PAIRING, UNREACHABLE }

    interface Listener {
        fun onState(state: State)
        fun onPairingCodeRequested()
        fun onPairingResult(ok: Boolean, error: String?)
    }

    private val identity = Identity(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger()
    private val lock = Any()

    private var socket: SSLSocket? = null
    private var output: OutputStream? = null
    private var pairSocket: SSLSocket? = null

    @Volatile
    var state = State.DISCONNECTED
        private set

    private fun post(gen: Int, state: State) {
        main.post {
            if (gen == generation.get()) {
                this.state = state
                listener.onState(state)
            }
        }
    }

    private fun open(host: String, port: Int): SSLSocket {
        val s = identity.sslContext.socketFactory.createSocket() as SSLSocket
        try {
            s.connect(InetSocketAddress(host, port), 5000)
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
        return s
    }

    // ---------------------------------------------------------------- kumanda

    fun connect(host: String) {
        disconnect()
        val gen = generation.get()
        state = State.CONNECTING
        listener.onState(state)
        thread(name = "atv-remote") {
            var tcpConnected = false
            var gotMessage = false
            var s: SSLSocket? = null
            try {
                s = open(host, REMOTE_PORT)
                tcpConnected = true
                s.soTimeout = 20000
                s.startHandshake()
                synchronized(lock) {
                    if (gen != generation.get()) {
                        s.close()
                        return@thread
                    }
                    socket = s
                    output = s.outputStream
                }
                val input = s.inputStream
                while (true) {
                    val msg = Proto.parse(Proto.readFrame(input))
                    gotMessage = true
                    when {
                        msg.containsKey(1) -> { // remote_configure
                            write(
                                ProtoWriter().msg(
                                    1, ProtoWriter().int(1, FEATURES).msg(
                                        2, ProtoWriter()
                                            .int(3, 1)
                                            .string(4, "1")
                                            .string(5, "atvremote")
                                            .string(6, "1.0.0")
                                    )
                                )
                            )
                        }
                        msg.containsKey(2) -> { // remote_set_active
                            write(ProtoWriter().msg(2, ProtoWriter().int(1, FEATURES)))
                            post(gen, State.CONNECTED)
                        }
                        msg.containsKey(8) -> { // ping
                            val ping = Proto.parse(msg[8]!![0] as ByteArray)
                            val v = (ping[1]?.firstOrNull() as? Long)?.toInt() ?: 0
                            write(ProtoWriter().msg(9, ProtoWriter().int(1, v)))
                        }
                    }
                }
            } catch (e: Exception) {
                runCatching { s?.close() }
                if (gen != generation.get()) return@thread
                synchronized(lock) {
                    socket = null
                    output = null
                }
                val st = when {
                    !tcpConnected -> State.UNREACHABLE
                    gotMessage || e is SocketTimeoutException -> State.DISCONNECTED
                    // TLS kuruldu/kurulamadi ama TV hic mesaj gondermeden kapatti: sertifikamizi tanimiyor
                    else -> State.NEEDS_PAIRING
                }
                post(gen, st)
            }
        }
    }

    private fun write(w: ProtoWriter) {
        val frame = w.frame()
        synchronized(lock) {
            val o = output ?: return
            o.write(frame)
            o.flush()
        }
    }

    private fun send(w: ProtoWriter) {
        sender.execute {
            try {
                write(w)
            } catch (e: Exception) {
                synchronized(lock) { runCatching { socket?.close() } }
            }
        }
    }

    /** direction: 1 = basili tut, 2 = birak, 3 = kisa basis */
    fun sendKey(keyCode: Int, direction: Int = 3) {
        send(ProtoWriter().msg(10, ProtoWriter().int(1, keyCode).int(2, direction)))
    }

    fun launch(link: String) {
        send(ProtoWriter().msg(90, ProtoWriter().string(1, link)))
    }

    fun disconnect() {
        generation.incrementAndGet()
        var a: SSLSocket? = null
        var b: SSLSocket? = null
        synchronized(lock) {
            a = socket
            b = pairSocket
            socket = null
            output = null
            pairSocket = null
        }
        // Kapatma ag islemi yapabilir; ana is parcaciginda yapma.
        if (a != null || b != null) thread {
            runCatching { a?.close() }
            runCatching { b?.close() }
        }
        state = State.DISCONNECTED
    }

    // ------------------------------------------------------------ eslestirme

    private fun pairMsg(field: Int, inner: ProtoWriter) =
        ProtoWriter().int(1, 2).int(2, 200).msg(field, inner).frame()

    private fun expectOk(s: SSLSocket) {
        val msg = Proto.parse(Proto.readFrame(s.inputStream))
        val status = (msg[2]?.firstOrNull() as? Long)?.toInt() ?: 0
        if (status != 200) throw IllegalStateException("TV reddetti (durum $status)")
    }

    fun startPairing(host: String) {
        disconnect()
        val gen = generation.get()
        thread(name = "atv-pair") {
            var s: SSLSocket? = null
            try {
                s = open(host, PAIRING_PORT)
                s.soTimeout = 10000
                s.startHandshake()
                synchronized(lock) {
                    if (gen != generation.get()) {
                        s.close()
                        return@thread
                    }
                    pairSocket = s
                }
                val out = s.outputStream
                val clientName = Build.MODEL ?: "Kumanda"
                out.write(pairMsg(10, ProtoWriter().string(1, "atvremote").string(2, clientName)))
                out.flush()
                expectOk(s)
                val encoding = ProtoWriter().int(1, 3).int(2, 6) // HEXADECIMAL, 6 karakter
                out.write(pairMsg(20, ProtoWriter().msg(1, encoding).int(3, 1)))
                out.flush()
                expectOk(s)
                out.write(pairMsg(30, ProtoWriter().msg(1, encoding).int(2, 1)))
                out.flush()
                expectOk(s)
                main.post { if (gen == generation.get()) listener.onPairingCodeRequested() }
            } catch (e: Exception) {
                runCatching { s?.close() }
                main.post {
                    if (gen == generation.get()) {
                        listener.onPairingResult(false, "TV'ye ulaşılamadı: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    fun finishPairing(code: String) {
        val gen = generation.get()
        val s = synchronized(lock) { pairSocket }
        if (s == null) {
            listener.onPairingResult(false, "Eşleştirme oturumu kapandı")
            return
        }
        thread(name = "atv-pair2") {
            var ok = false
            var error: String? = null
            try {
                val clean = code.trim().uppercase()
                if (clean.length != 6 || !clean.all { it in "0123456789ABCDEF" }) {
                    throw IllegalArgumentException("Kod 6 karakter olmalı (0-9, A-F)")
                }
                val codeBytes = ByteArray(3) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                val client = identity.certificate.publicKey as RSAPublicKey
                val server = s.session.peerCertificates[0].publicKey as? RSAPublicKey
                    ?: throw IllegalStateException("TV sertifikası desteklenmiyor")
                val md = MessageDigest.getInstance("SHA-256")
                md.update(unsigned(client.modulus))
                md.update(unsigned(client.publicExponent))
                md.update(unsigned(server.modulus))
                md.update(unsigned(server.publicExponent))
                md.update(codeBytes, 1, 2)
                val secret = md.digest()
                if (secret[0] != codeBytes[0]) throw IllegalArgumentException("Kod yanlış")
                s.outputStream.write(pairMsg(40, ProtoWriter().bytes(1, secret)))
                s.outputStream.flush()
                expectOk(s)
                ok = true
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            runCatching { s.close() }
            synchronized(lock) { if (pairSocket === s) pairSocket = null }
            main.post { if (gen == generation.get()) listener.onPairingResult(ok, error) }
        }
    }

    private fun unsigned(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    companion object {
        const val REMOTE_PORT = 6466
        const val PAIRING_PORT = 6467
        private const val FEATURES = 622
    }
}
