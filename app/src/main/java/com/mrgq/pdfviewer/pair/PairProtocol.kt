package com.mrgq.pdfviewer.pair

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mrgq.pdfviewer.follow.RollingTurns.Spread

/**
 * 짝 메시지 (P22) — 한 줄에 JSON 하나(TCP, [PairLink]). 합주 메시지([com.mrgq.pdfviewer.CollaborationProtocol])와 따로라 합주 버전과 무관하다.
 *
 * - `hello` — 연결하면 서로 한 번: 보내는 기기의 자리 · 짝 판([VERSION]) · 이름
 * - `spread` — 주 기기 → 짝 기기: 지금 악보(이름 · sha256 · 쪽 수)와 펼침, [seq] 는 늦게 온 옛 펼침을 버리는 번호.
 *   **쪽은 메시지에서 1부터**(합주 · 화면과 같게) — 앱 안의 [Spread] 는 순번(0부터)이라 여기서만 옮긴다
 * - `input` — 짝 기기 → 주 기기: 넘김(다음 · 이전), `sync` = 지금 펼침을 다시 보내 달라(악보 화면에 돌아왔다)
 * - `focus` — 주 기기 → 짝 기기: 지금 마디(노란 표시 · 고르는 커서)와 연한 시스템 표시 — 짝 기기 쪽에 있으면 그린다(3단계)
 * - `notice` — 주 기기 → 짝 기기: 짝 기기가 보낸 명령(연주 · 듣기)을 못 했을 때의 이유(화면 글)
 * - `bye` — 짝 풀기(받은 쪽도 푼다 — 다시 붙지 않게)
 * - `ping` — 살아 있음(끊김 알기)
 *
 * 모르는 필드는 무시, 모르는 종류 · 깨진 줄은 null — 나중 판이 필드를 더해도 옛 판이 죽지 않는다
 */
object PairProtocol {
    /** 짝 메시지가 바뀌면 올린다 — 다르면 짝을 맺지 않고 업데이트 안내 */
    /** 3 = 쪽 번호를 1부터로(v0.9.0-beta.2) */
    const val VERSION = 3

    sealed class Message {
        data class Hello(val side: PairSide, val version: Int, val name: String) : Message()
        data class SpreadUpdate(
            val file: String,
            val sha256: String?,
            val pageCount: Int,
            val spread: Spread,
            val seq: Long,
            /** 주 기기의 위 · 아래 자르기(%) — 짝 기기가 같게 그린다. 없으면 짝 기기 것 그대로 */
            val clipTop: Int? = null,
            val clipBottom: Int? = null,
        ) : Message()
        data class Input(val action: Action) : Message()

        /**
         * 주 기기 화면의 마디 표시 — [measure] 노란 마디(또는 [cursor] 면 고르는 커서 모양), [system] 연한 시스템 표시의 마디,
         * [systemStrong] = 연주자 화면처럼 조금 진하게, [waiting] = 듣기 시작을 기다리는 마디(그 마디만 연하게). 모두 마디 번호
         */
        data class Focus(
            val measure: Int?,
            val cursor: Boolean,
            val system: Int?,
            val systemStrong: Boolean,
            val waiting: Int?,
            /** 주 기기가 하는 것 — [PLAYING_METRONOME] · [PLAYING_MIC] · [PLAYING_SELECTING], 없으면 멈춤. 짝 기기의 ▶ 메뉴가 쓴다 */
            val playing: String? = null,
        ) : Message()
        data class Notice(val text: String) : Message()
        object Bye : Message()
        object Ping : Message()
    }

    /** `start` · `listen` · `stop` = 짝 기기에서 연주 · 듣기 시작 · 정지 — 주 기기가 한다 */
    enum class Action(val wire: String) {
        NEXT("next"), PREVIOUS("prev"), SYNC("sync"), START("start"), LISTEN("listen"), STOP("stop"),
    }

    const val PLAYING_METRONOME = "metronome"
    const val PLAYING_MIC = "mic"
    const val PLAYING_SELECTING = "selecting"

    fun encode(message: Message): String = JsonObject().apply {
        when (message) {
            is Message.Hello -> {
                addProperty("type", "hello")
                addProperty("side", message.side.wire)
                addProperty("version", message.version)
                addProperty("name", message.name)
            }
            is Message.SpreadUpdate -> {
                addProperty("type", "spread")
                addProperty("file", message.file)
                message.sha256?.let { addProperty("sha256", it) }
                addProperty("pages", message.pageCount)
                addProperty("left", message.spread.left + 1)
                message.spread.right?.let { addProperty("right", it + 1) }
                addProperty("seq", message.seq)
                message.clipTop?.let { addProperty("clip_top", it) }
                message.clipBottom?.let { addProperty("clip_bottom", it) }
            }
            is Message.Input -> {
                addProperty("type", "input")
                addProperty("action", message.action.wire)
            }
            is Message.Focus -> {
                addProperty("type", "focus")
                message.measure?.let { addProperty("measure", it) }
                addProperty("cursor", message.cursor)
                message.system?.let { addProperty("system", it) }
                addProperty("system_strong", message.systemStrong)
                message.waiting?.let { addProperty("waiting", it) }
                message.playing?.let { addProperty("playing", it) }
            }
            is Message.Notice -> {
                addProperty("type", "notice")
                addProperty("text", message.text)
            }
            Message.Bye -> addProperty("type", "bye")
            Message.Ping -> addProperty("type", "ping")
        }
    }.toString()

    fun decode(line: String): Message? {
        val json = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return null
        return runCatching {
            when (json.str("type")) {
                "hello" -> Message.Hello(
                    side = PairSide.of(json.str("side")) ?: return null,
                    version = json.int("version") ?: 0,
                    name = json.str("name").orEmpty(),
                )
                "spread" -> {
                    val pages = json.int("pages") ?: return null
                    // 메시지는 1부터 → 순번
                    val left = (json.int("left") ?: return null) - 1
                    val right = json.int("right")?.minus(1)
                    if (left !in 0 until pages || (right != null && right !in 0 until pages)) return null
                    Message.SpreadUpdate(
                        json.str("file").orEmpty(), json.str("sha256"), pages, Spread(left, right), json.long("seq") ?: 0,
                        clipTop = json.int("clip_top")?.coerceIn(0, 50), clipBottom = json.int("clip_bottom")?.coerceIn(0, 50),
                    )
                }
                "input" -> Message.Input(Action.entries.firstOrNull { it.wire == json.str("action") } ?: return null)
                "focus" -> Message.Focus(
                    measure = json.int("measure"),
                    cursor = json.bool("cursor"),
                    system = json.int("system"),
                    systemStrong = json.bool("system_strong"),
                    waiting = json.int("waiting"),
                    playing = json.str("playing"),
                )
                "notice" -> Message.Notice(json.str("text") ?: return null)
                "bye" -> Message.Bye
                "ping" -> Message.Ping
                else -> null
            }
        }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
    private fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
    private fun JsonObject.long(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }
}
