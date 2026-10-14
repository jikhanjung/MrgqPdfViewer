package com.mrgq.pdfviewer.pair

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mrgq.pdfviewer.follow.RollingTurns.Spread

/**
 * 짝 메시지 (P22) — 한 줄에 JSON 하나(TCP, [PairLink]). 합주 메시지([com.mrgq.pdfviewer.CollaborationProtocol])와 따로라 합주 버전과 무관하다.
 *
 * - `hello` — 연결하면 서로 한 번: 보내는 기기의 자리 · 짝 판([VERSION]) · 이름
 * - `spread` — 주 기기 → 짝 기기: 지금 악보(이름 · sha256 · 쪽 수)와 펼침, [seq] 는 늦게 온 옛 펼침을 버리는 번호
 * - `input` — 짝 기기 → 주 기기: 넘김(다음 · 이전), `sync` = 지금 펼침을 다시 보내 달라(악보 화면에 돌아왔다)
 * - `ping` — 살아 있음(끊김 알기)
 *
 * 모르는 필드는 무시, 모르는 종류 · 깨진 줄은 null — 나중 판이 필드를 더해도 옛 판이 죽지 않는다
 */
object PairProtocol {
    /** 짝 메시지가 바뀌면 올린다 — 다르면 짝을 맺지 않고 업데이트 안내 */
    const val VERSION = 1

    sealed class Message {
        data class Hello(val side: PairSide, val version: Int, val name: String) : Message()
        data class SpreadUpdate(
            val file: String,
            val sha256: String?,
            val pageCount: Int,
            val spread: Spread,
            val seq: Long,
        ) : Message()
        data class Input(val action: Action) : Message()
        object Ping : Message()
    }

    enum class Action(val wire: String) { NEXT("next"), PREVIOUS("prev"), SYNC("sync") }

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
                addProperty("left", message.spread.left)
                message.spread.right?.let { addProperty("right", it) }
                addProperty("seq", message.seq)
            }
            is Message.Input -> {
                addProperty("type", "input")
                addProperty("action", message.action.wire)
            }
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
                    val left = json.int("left") ?: return null
                    val right = json.int("right")
                    if (left !in 0 until pages || (right != null && right !in 0 until pages)) return null
                    Message.SpreadUpdate(json.str("file").orEmpty(), json.str("sha256"), pages, Spread(left, right), json.long("seq") ?: 0)
                }
                "input" -> Message.Input(Action.entries.firstOrNull { it.wire == json.str("action") } ?: return null)
                "ping" -> Message.Ping
                else -> null
            }
        }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
    private fun JsonObject.long(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }
}
