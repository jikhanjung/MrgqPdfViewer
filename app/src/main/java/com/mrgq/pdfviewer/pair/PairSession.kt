package com.mrgq.pdfviewer.pair

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 두 대로 펼쳐 보기의 짝 (P22) — 앱 전체에 하나. 화면을 떠나도(목록으로 가도) 짝은 남는다.
 *
 * - **주 기기**([Role.MAIN]) = 짝을 기다린 기기. 펼침을 정하고 짝 기기에 알린다
 * - **짝 기기**([Role.PARTNER]) = 찾아서 붙은 기기. 자리는 주 기기의 반대, 끊기면 [RETRY_MS] 마다 다시 붙는다
 *   (몇 번 못 붙으면 같은 이름의 주 기기를 다시 찾는다 — 주 기기가 다시 켜져 주소가 바뀌었을 수 있다)
 *
 * **짝은 저장한다**(P22 §4): 앱이 죽었다 켜져도 [init] 이 주 기기는 다시 기다리고 짝 기기는 다시 붙는다. 짝 풀기([stop])만 지운다 —
 * 그때 상대에게 `bye` 를 보내 상대도 푼다.
 *
 * 주 기기는 마지막으로 보낸 펼침 · 마디 표시를 기억해, 악보 화면이 없을 때도(목록 등) 다시 붙거나 `sync` 가 오면 그것을 다시 보낸다.
 * 알림([Listener])은 주 스레드로 — 앞에 있는 화면(악보 · 목록)이 받는다
 */
object PairSession {
    private const val TAG = "PairSession"
    private const val RETRY_MS = 2000L
    /** 이만큼 못 붙으면 주 기기를 이름으로 다시 찾는다 */
    private const val REDISCOVER_AFTER = 4
    private const val PREFS = "pair"
    /** 주 기기의 악보 받기(HTTP) 포트 — 짝 기기에 없는 악보를 받는다. 합주 파일 서버(8090)와 따로 */
    const val FILE_PORT = 9095

    enum class Role { MAIN, PARTNER }

    /** 앞에 있는 화면이 받는다 — 필요한 것만 구현 */
    interface Listener {
        /** 악보 화면 — 펼침을 스스로 정해 보낸다. 아니면(목록 등) 주 기기는 기억한 펼침을 다시 보낸다 */
        val isViewer: Boolean get() = false
        fun onPairConnected(peerName: String) {}
        fun onPairDisconnected() {}
        fun onPairSpread(update: PairProtocol.Message.SpreadUpdate) {}
        fun onPairFocus(focus: PairProtocol.Message.Focus) {}
        fun onPairInput(action: PairProtocol.Action) {}
        /** 주 기기가 짝 기기의 명령을 못 했다 — 이유 글 */
        fun onPairNotice(text: String) {}
        /** 상대가 짝을 풀었다 — 이 기기도 풀었다 */
        fun onPairEnded() {}
        /** 짝의 짝 판([PairProtocol.VERSION])이 다르다 — 짝을 풀었다 */
        fun onPairVersionMismatch() {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val log: (String) -> Unit = { Log.i(TAG, it) }
    private var link: PairLink? = null
    private val discovery = PairDiscovery(log)
    private var prefs: SharedPreferences? = null

    var role: Role? = null
        private set
    var side: PairSide? = null
        private set
    /** 상대가 hello 를 보내 짝이 맺어졌다 */
    @Volatile var connected = false
        private set
    var peerName: String? = null
        private set
    /** 주 기기: 화면에 띄우고 찾기 알림에 싣는 번호 — 기다리기를 시작할 때마다 새로 (보조 기기가 번호로 고른다) */
    var code: String? = null
        private set
    /** 짝 기기: 지금 붙는 주 기기 주소 — 악보 받기에 */
    @Volatile var hostAddress: String? = null
        private set
    /** 주 기기: 지금 악보를 짝 기기가 받을 수 있게 내놓는 HTTP 서버 — 짝을 기다리는 동안 */
    private var fileServer: com.mrgq.pdfviewer.FileServerManager? = null

    var listener: Listener? = null

    /** 주 기기: 마지막으로 보낸 펼침 · 마디 표시 — 화면이 없을 때 다시 보낸다 */
    private var lastSpread: PairProtocol.Message.SpreadUpdate? = null
    private var lastFocus: PairProtocol.Message.Focus? = null

    val isActive: Boolean get() = role != null

    private val deviceName: String get() = Build.MODEL ?: "Android"

    /** 앱 시작 때 — 저장된 짝이 있으면 다시 (주 기기는 기다리기, 짝 기기는 붙기) */
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val savedSide = PairSide.of(p.getString("side", null)) ?: return
        when (p.getString("role", null)) {
            Role.MAIN.name -> startHosting(savedSide)
            Role.PARTNER.name -> {
                val address = p.getString("host_address", null) ?: return
                join(
                    PairDiscovery.Host(
                        name = p.getString("host_name", null).orEmpty(),
                        address = address,
                        port = p.getInt("host_port", PairLink.PORT),
                        side = savedSide.other,
                        version = PairProtocol.VERSION,
                    ),
                )
            }
        }
        log("restored: $role $side")
    }

    /** 주 기기: [mySide] 자리로 짝을 기다린다 */
    fun startHosting(mySide: PairSide): Boolean {
        close()
        val l = newLink()
        if (!l.listen()) {
            l.close()
            link = null
            return false
        }
        role = Role.MAIN
        side = mySide
        code = PairCodes.newCode()
        discovery.startAnnouncing(deviceName, mySide, code)
        fileServer = runCatching { com.mrgq.pdfviewer.FileServerManager(FILE_PORT).also { it.start() } }
            .onFailure { log("file server $FILE_PORT failed: ${it.message}") }.getOrNull()
        prefs?.edit()?.clear()?.putString("role", Role.MAIN.name)?.putString("side", mySide.wire)?.apply()
        return true
    }

    /** 짝 기기: 기다리는 주 기기를 찾는다 — 알림은 주 스레드로 */
    fun find(onFound: (PairDiscovery.Host) -> Unit, onDone: () -> Unit) {
        discovery.find(onFound = { h -> main.post { onFound(h) } }, onDone = { main.post(onDone) })
    }

    fun stopFinding() = discovery.stopFinding()

    /** 짝 기기: 찾은 [target] 에 붙는다 — 자리는 그 반대. 끊기면 다시 붙는다 */
    fun join(target: PairDiscovery.Host) {
        close()
        role = Role.PARTNER
        side = target.side.other
        hostAddress = target.address
        val l = newLink()
        prefs?.edit()?.clear()?.putString("role", Role.PARTNER.name)?.putString("side", target.side.other.wire)
            ?.putString("host_name", target.name)?.putString("host_address", target.address)?.putInt("host_port", target.port)?.apply()
        Thread({
            var host = target
            var failures = 0
            while (link === l && role == Role.PARTNER) {
                if (!l.isConnected) {
                    if (l.connect(host.address, host.port)) {
                        failures = 0
                    } else if (++failures >= REDISCOVER_AFTER && host.name.isNotEmpty()) {
                        failures = 0
                        discovery.findHost(host.name)?.takeIf { it.address != host.address }?.let { found ->
                            log("host moved: ${host.address} → ${found.address}")
                            host = found
                            hostAddress = found.address
                            prefs?.edit()?.putString("host_address", found.address)?.putInt("host_port", found.port)?.apply()
                        }
                    }
                }
                runCatching { Thread.sleep(RETRY_MS) }
            }
        }, "PairJoin").start()
    }

    /** 짝을 푼다 — 상대에게도 알려(`bye`) 두 기기 모두 혼자 보기. 저장한 짝도 지운다 */
    fun stop() {
        val l = link
        if (l != null && connected) Thread({ l.send(PairProtocol.Message.Bye) }, "PairBye").start()
        prefs?.edit()?.clear()?.apply()
        // bye 가 나갈 틈을 조금 준다
        main.postDelayed({ if (link === l) close() else l?.close() }, if (connected) 300L else 0L)
        role = null
        side = null
        connected = false
        peerName = null
        code = null
    }

    /** 연결 · 듣기 · 알림을 닫는다(저장한 짝은 그대로) */
    private fun close() {
        discovery.stopAll()
        val l = link
        link = null
        val wasConnected = connected
        role = null
        side = null
        connected = false
        peerName = null
        code = null
        lastSpread = null
        lastFocus = null
        hostAddress = null
        fileServer?.let { server -> Thread({ runCatching { server.stop() } }, "PairFileStop").start() }
        fileServer = null
        l?.close()
        if (wasConnected) main.post { listener?.onPairDisconnected() }
    }

    /** 주 기기: 짝 기기가 받을 수 있게 지금 악보 하나만 내놓는다(원본 PDF — 파트 PDF 는 기기마다 만든다) */
    fun offerFile(name: String, path: String) {
        val server = fileServer ?: return
        server.clearFiles()
        server.addFile(name, path)
    }

    /** 짝 기기: 주 기기의 악보 받기 주소 (`/download/<이름>`) */
    fun fileServerUrl(): String? = hostAddress?.takeIf { role == Role.PARTNER }?.let { "http://$it:$FILE_PORT" }

    fun send(message: PairProtocol.Message) {
        if (role == Role.MAIN) {
            if (message is PairProtocol.Message.SpreadUpdate) lastSpread = message
            if (message is PairProtocol.Message.Focus) lastFocus = message
        }
        val l = link ?: return
        if (!connected) return
        Thread({ l.send(message) }, "PairSend").start()
    }

    /** 주 기기: 기억한 펼침 · 마디 표시를 다시 보낸다 (화면이 없을 때 — 있으면 화면이 지금 것을 보낸다) */
    private fun resendLast() {
        lastSpread?.let { send(it) }
        lastFocus?.let { send(it) }
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
                        is PairProtocol.Message.Focus -> if (role == Role.PARTNER) listener?.onPairFocus(message)
                        is PairProtocol.Message.Notice -> if (role == Role.PARTNER) listener?.onPairNotice(message.text)
                        is PairProtocol.Message.Input -> if (role == Role.MAIN) {
                            val viewer = listener?.takeIf { it.isViewer }
                            if (viewer != null) viewer.onPairInput(message.action)
                            else if (message.action == PairProtocol.Action.SYNC) resendLast()
                        }
                        PairProtocol.Message.Bye -> {
                            log("partner ended the pair")
                            stop()
                            listener?.onPairEnded()
                        }
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
                    if (role == Role.MAIN) side?.let { discovery.startAnnouncing(deviceName, it, code) }
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
        log("paired: ${hello.name} ($role, $side)")
        if (!first) return
        val l = listener
        l?.onPairConnected(hello.name)
        if (l?.isViewer != true && role == Role.MAIN) resendLast()
    }
}
