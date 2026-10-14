package com.mrgq.pdfviewer.pair

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * 짝 찾기 (P22) — 짝을 기다리는 주 기기가 UDP 로 [ANNOUNCE_MS] 마다 알리고, 짝 기기가 [PORT] 에서 듣는다.
 * 합주 지휘자 찾기(`ConductorDiscovery`, 9091)와 포트 · 메시지가 따로다. Android 에 의존하지 않는다
 */
class PairDiscovery(private val log: (String) -> Unit = {}) {

    /** 짝을 기다리는 기기 — [side] 는 그 기기의 자리(붙는 기기는 반대 자리), [code] 는 그 화면의 번호(옛 판은 없음) */
    data class Host(val name: String, val address: String, val port: Int, val side: PairSide, val version: Int, val code: String? = null)

    @Volatile private var announcing = false
    @Volatile private var finding: DatagramSocket? = null

    /** 주 기기: 짝을 기다리는 동안 알린다 — [code] 는 화면에 띄운 번호(보조 기기가 고른다) */
    fun startAnnouncing(name: String, side: PairSide, code: String? = null, port: Int = PairLink.PORT) {
        stopAnnouncing()
        announcing = true
        val data = JsonObject().apply {
            addProperty("type", TYPE)
            addProperty("name", name)
            addProperty("side", side.wire)
            addProperty("port", port)
            addProperty("version", PairProtocol.VERSION)
            if (code != null) addProperty("code", code)
        }.toString().toByteArray(Charsets.UTF_8)
        Thread({
            val socket = runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull() ?: return@Thread
            try {
                while (announcing) {
                    for (address in broadcastAddresses()) {
                        runCatching { socket.send(DatagramPacket(data, data.size, InetAddress.getByName(address), PORT)) }
                    }
                    runCatching { Thread.sleep(ANNOUNCE_MS) }
                }
            } finally {
                socket.close()
            }
        }, "PairAnnounce").start()
    }

    fun stopAnnouncing() {
        announcing = false
    }

    /** 짝 기기: [timeoutMs] 동안 듣고 찾은 주 기기마다 [onFound](같은 기기는 한 번), 끝나면 [onDone] — 모두 듣는 스레드에서 */
    fun find(timeoutMs: Long = FIND_MS, onFound: (Host) -> Unit, onDone: () -> Unit) {
        stopFinding()
        Thread({
            val socket = runCatching {
                DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 500; bind(InetSocketAddress(PORT)) }
            }.onFailure { log("find: bind $PORT failed: ${it.message}") }.getOrNull()
            if (socket == null) return@Thread onDone()
            finding = socket
            val seen = mutableSetOf<String>()
            val until = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(1024)
            while (finding === socket && System.currentTimeMillis() < until) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                val host = parse(String(packet.data, 0, packet.length, Charsets.UTF_8), packet.address.hostAddress ?: continue) ?: continue
                if (seen.add("${host.address}:${host.port}")) onFound(host)
            }
            socket.close()
            if (finding === socket) finding = null
            onDone()
        }, "PairFind").start()
    }

    /**
     * 짝 기기가 다시 붙지 못할 때: [name] 인 주 기기를 [timeoutMs] 동안 찾아 돌려준다(막는 호출 — 주 스레드가 아닌 곳에서).
     * 주 기기가 다시 켜져 주소가 바뀌었을 수 있다
     */
    fun findHost(name: String, timeoutMs: Long = 4000L): Host? {
        val socket = runCatching {
            DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 500; bind(InetSocketAddress(PORT)) }
        }.getOrNull() ?: return null
        try {
            val until = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(1024)
            while (System.currentTimeMillis() < until) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val host = parse(String(packet.data, 0, packet.length, Charsets.UTF_8), packet.address.hostAddress ?: continue) ?: continue
                if (host.name == name) return host
            }
            return null
        } catch (e: Exception) {
            return null
        } finally {
            socket.close()
        }
    }

    fun stopFinding() {
        finding?.close()
        finding = null
    }

    fun stopAll() {
        stopAnnouncing()
        stopFinding()
    }

    private fun broadcastAddresses(): List<String> {
        val result = linkedSetOf<String>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (nif.isLoopback || !nif.isUp) continue
                nif.interfaceAddresses.mapNotNullTo(result) { it.broadcast?.hostAddress }
            }
        }
        result += "255.255.255.255"
        return result.toList()
    }

    companion object {
        const val PORT = 9094
        const val ANNOUNCE_MS = 1500L
        const val FIND_MS = 15_000L
        private const val TYPE = "pair_announce"

        /** 알림 한 줄 → 주 기기 ([address] = 보낸 곳) */
        fun parse(text: String, address: String): Host? = runCatching {
            val json = JsonParser.parseString(text).asJsonObject
            if (json.get("type")?.asString != TYPE) return null
            Host(
                name = json.get("name")?.asString.orEmpty(),
                address = address,
                port = json.get("port")?.asInt ?: PairLink.PORT,
                side = PairSide.of(json.get("side")?.asString) ?: return null,
                version = json.get("version")?.asInt ?: 0,
                code = json.get("code")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }
}
