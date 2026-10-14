package com.mrgq.pdfviewer.pair

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 두 대로 펼쳐 보기의 짝 (P22) — 앱 전체에 하나. 화면을 떠나도(목록으로 가도) 짝은 남는다.
 *
 * - **주 기기**([Role.MAIN]) = 짝을 기다린 기기. 펼침을 정하고 짝 기기에 알린다
 * - **짝 기기**([Role.PARTNER]) = 찾아서 붙은 기기. 자리는 주 기기의 반대, 끊기면 [RETRY_MS] 마다 다시 붙는다
 *
 * 알림([Listener])은 주 스레드로 — 악보 화면이 붙어 있을 때만 받는다
 */
object PairSession {
    private const val TAG = "PairSession"
    private const val RETRY_MS = 2000L

    enum class Role { MAIN, PARTNER }

    interface Listener {
        fun onPairConnected(peerName: String)
        fun onPairDisconnected()
        fun onPairSpread(update: PairProtocol.Message.SpreadUpdate)
        fun onPairInput(action: PairProtocol.Action)
        /** 짝의 짝 판([PairProtocol.VERSION])이 다르다 — 짝을 풀었다 */
        fun onPairVersionMismatch()
    }

    private val main = Handler(Looper.getMainLooper())
    private val log: (String) -> Unit = { Log.i(TAG, it) }
    private var link: PairLink? = null
    private val discovery = PairDiscovery(log)

    var role: Role? = null
        private set
    var side: PairSide? = null
        private set
    /** 상대가 hello 를 보내 짝이 맺어졌다 */
    @Volatile var connected = false
        private set
    var peerName: String? = null
        private set
    private var host: PairDiscovery.Host? = null

    var listener: Listener? = null

    val isActive: Boolean get() = role != null

    private val deviceName: String get() = Build.MODEL ?: "Android"

    /** 주 기기: [mySide] 자리로 짝을 기다린다 */
    fun startHosting(mySide: PairSide): Boolean {
        stop()
        val l = newLink()
        if (!l.listen()) {
            l.close()
            link = null
            return false
        }
        role = Role.MAIN
        side = mySide
        discovery.startAnnouncing(deviceName, mySide)
        return true
    }

    /** 짝 기기: 기다리는 주 기기를 찾는다 — 알림은 주 스레드로 */
    fun find(onFound: (PairDiscovery.Host) -> Unit, onDone: () -> Unit) {
        discovery.find(onFound = { h -> main.post { onFound(h) } }, onDone = { main.post(onDone) })
    }

    fun stopFinding() = discovery.stopFinding()

    /** 짝 기기: 찾은 [target] 에 붙는다 — 자리는 그 반대. 끊기면 다시 붙는다 */
    fun join(target: PairDiscovery.Host) {
        stop()
        role = Role.PARTNER
        side = target.side.other
        host = target
        val l = newLink()
        Thread({
            while (link === l && role == Role.PARTNER) {
                if (!l.isConnected) l.connect(target.address, target.port)
                runCatching { Thread.sleep(RETRY_MS) }
            }
        }, "PairJoin").start()
    }

    /** 짝을 푼다 (두 기기 모두 혼자 보기로) */
    fun stop() {
        discovery.stopAll()
        val l = link
        link = null
        val wasConnected = connected
        role = null
        side = null
        host = null
        connected = false
        peerName = null
        l?.close()
        if (wasConnected) main.post { listener?.onPairDisconnected() }
    }

    fun send(message: PairProtocol.Message) {
        val l = link ?: return
        if (!connected) return
        Thread({ l.send(message) }, "PairSend").start()
    }

    private fun newLink(): PairLink = PairLink(log).also { l ->
        link = l
        l.listener = object : PairLink.Listener {
            override fun onConnected() {
                val mySide = side ?: return
                l.send(PairProtocol.Message.Hello(mySide, PairProtocol.VERSION, deviceName))
            }

            override fun onMessage(message: PairProtocol.Message) {
                main.post {
                    if (link !== l) return@post
                    when (message) {
                        is PairProtocol.Message.Hello -> onHello(message)
                        is PairProtocol.Message.SpreadUpdate -> if (role == Role.PARTNER) listener?.onPairSpread(message)
                        is PairProtocol.Message.Input -> if (role == Role.MAIN) listener?.onPairInput(message.action)
                        PairProtocol.Message.Ping -> Unit
                    }
                }
            }

            override fun onDisconnected() {
                main.post {
                    if (link !== l || !connected) return@post
                    connected = false
                    log("disconnected")
                    // 주 기기는 다시 기다린다 — 짝 기기가 앱을 다시 켜면 찾아서 붙는다
                    if (role == Role.MAIN) side?.let { discovery.startAnnouncing(deviceName, it) }
                    listener?.onPairDisconnected()
                }
            }
        }
    }

    private fun onHello(hello: PairProtocol.Message.Hello) {
        if (hello.version != PairProtocol.VERSION) {
            log("version mismatch: ${hello.version} ≠ ${PairProtocol.VERSION}")
            stop()
            listener?.onPairVersionMismatch()
            return
        }
        // 자리는 주 기기가 정한다 — 짝 기기는 늘 반대 자리
        if (role == Role.PARTNER) side = hello.side.other
        peerName = hello.name
        val first = !connected
        connected = true
        if (role == Role.MAIN) discovery.stopAnnouncing()
        log("paired: ${hello.name} (${role}, ${side})")
        if (first) listener?.onPairConnected(hello.name)
    }
}
