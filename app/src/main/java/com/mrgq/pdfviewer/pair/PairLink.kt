package com.mrgq.pdfviewer.pair

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 짝 연결 (P22) — TCP 위에 한 줄 JSON 하나([PairProtocol]). 우리 앱끼리만 통하니 WebSocket 이 필요 없다.
 * 주 기기는 [listen](짝은 하나 — 새 연결이 오면 옛 연결을 닫는다), 짝 기기는 [connect].
 * 연결하면 [PING_MS] 마다 `ping` 을 보내고, [TIMEOUT_MS] 동안 아무것도 못 받으면 끊긴 것으로 본다.
 *
 * 알림([Listener])은 연결의 스레드에서 온다 — 화면은 [PairSession] 이 주 스레드로 옮긴다. Android 에 의존하지 않는다(루프백 단위 테스트)
 */
class PairLink(private val log: (String) -> Unit = {}) {

    interface Listener {
        fun onConnected()
        fun onMessage(message: PairProtocol.Message)
        fun onDisconnected()
    }

    @Volatile var listener: Listener? = null

    private val lock = Any()
    private var server: ServerSocket? = null
    private var conn: Conn? = null
    @Volatile private var closed = false

    val isConnected: Boolean get() = synchronized(lock) { conn != null }

    /** 듣는 포트 ([port] 0 이면 아무 빈 포트 — 시험용) */
    val localPort: Int get() = server?.localPort ?: -1

    /** 주 기기: 짝을 기다린다. 이미 듣고 있으면 그대로 */
    fun listen(port: Int = PORT): Boolean {
        if (server != null) return true
        val socket = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }
            .onFailure { log("listen $port failed: ${it.message}") }.getOrNull() ?: return false
        server = socket
        Thread({
            while (!closed && !socket.isClosed) {
                val s = runCatching { socket.accept() }.getOrNull() ?: break
                log("partner connected: ${s.inetAddress?.hostAddress}")
                adopt(s)
            }
        }, "PairAccept").start()
        return true
    }

    /** 짝 기기: 주 기기에 붙는다 (막는 호출 — 주 스레드가 아닌 곳에서) */
    fun connect(host: String, port: Int = PORT, timeoutMs: Int = 3000): Boolean {
        if (closed) return false
        val s = Socket()
        return runCatching { s.connect(InetSocketAddress(host, port), timeoutMs) }
            .onFailure { log("connect $host:$port failed: ${it.message}"); runCatching { s.close() } }
            .map { adopt(s); true }.getOrDefault(false)
    }

    fun send(message: PairProtocol.Message): Boolean = synchronized(lock) { conn }?.send(PairProtocol.encode(message)) ?: false

    /** 지금 연결만 끊는다(듣기는 그대로) */
    fun dropConnection() {
        synchronized(lock) { conn }?.close()
    }

    /** 모두 닫는다 — 다시 쓰지 않는다 */
    fun close() {
        closed = true
        runCatching { server?.close() }
        server = null
        dropConnection()
    }

    private fun adopt(socket: Socket) {
        val c = runCatching { Conn(socket) }.getOrElse { runCatching { socket.close() }; return }
        val old = synchronized(lock) { conn.also { conn = c } }
        old?.close()
        if (closed) return c.close()
        listener?.onConnected() // 읽기보다 먼저 — 받은 메시지가 연결 알림을 앞지르지 않게
        c.start()
    }

    private inner class Conn(private val socket: Socket) {
        private val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
        @Volatile private var alive = true

        fun start() {
            socket.soTimeout = TIMEOUT_MS
            socket.tcpNoDelay = true
            Thread(::read, "PairRead").start()
            Thread({
                while (alive) {
                    runCatching { Thread.sleep(PING_MS) }
                    if (alive && !send(PairProtocol.encode(PairProtocol.Message.Ping))) close()
                }
            }, "PairPing").start()
        }

        private fun read() {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (alive) {
                    val line = reader.readLine() ?: break
                    val message = PairProtocol.decode(line) ?: continue
                    if (message !is PairProtocol.Message.Ping) listener?.onMessage(message)
                }
            } catch (e: SocketTimeoutException) {
                log("no reply for ${TIMEOUT_MS}ms")
            } catch (e: Exception) {
                if (alive) log("read ended: ${e.message}")
            }
            close()
        }

        fun send(line: String): Boolean = synchronized(this) {
            if (!alive) return false
            runCatching { writer.write(line); writer.write("\n"); writer.flush() }.isSuccess
        }

        fun close() {
            if (!alive) return
            alive = false
            runCatching { socket.close() }
            // 지금 연결일 때만 알린다 — 새 연결로 바뀐 뒤 옛 연결이 닫히는 것은 끊김이 아니다
            val current = synchronized(lock) { (conn === this).also { if (it) conn = null } }
            if (current) listener?.onDisconnected()
        }
    }

    companion object {
        const val PORT = 9093
        const val PING_MS = 2000L
        const val TIMEOUT_MS = 7000
    }
}
