package com.mrgq.pdfviewer.scoremate

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** 세트리스트 곡 하나 — [position] 은 1부터 곡 순서 */
data class SetlistItem(val scoreId: Long, val position: Int, val notes: String)

/** 서버 세트리스트 (`GET /sync/setlists/`, 서버 devlog 061 · 062). 연결된 TV 가 고른 곡목만 온다(서버 0.7.0 기본) */
data class Setlist(val id: Long, val title: String, val ensembleName: String?, val items: List<SetlistItem>)

/** 세트리스트 순서의 곡 — [path] 가 null 이면 이 TV 에 아직 없는 곡(받는 중 · 숨김) */
data class SetlistEntry(val item: SetlistItem, val path: String?)

/**
 * TV 에서 세트리스트 보기 (#064). 동기화할 때 악보 다음에 통째로 받아 저장해 두고(오프라인에서도 보이게), 파일 목록에서 고르면
 * 곡 순서대로 보인다 — 악보 화면의 이전/다음도 그 순서. 곡은 서버 악보 id 로 가리키므로 받아 둔 악보(`server_scores`)와 잇는다.
 *
 * Android 에 의존하지 않는다 — JVM 단위 테스트 대상.
 */
object ScoreMateSetlists {

    /** 서버 응답 · 저장한 값 해석. 깨진 세트리스트 · 항목은 건너뛴다 */
    fun parse(body: String?): List<Setlist> {
        if (body.isNullOrBlank()) return emptyList()
        val root = try {
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: RuntimeException) {
            null
        } ?: return emptyList()
        val array = root.get("setlists")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            try {
                val o = element.asJsonObject
                val items = o.get("items")?.takeIf { it.isJsonArray }?.asJsonArray.orEmpty().mapNotNull { itemElement ->
                    try {
                        val i = itemElement.asJsonObject
                        SetlistItem(
                            scoreId = i.get("score_id").asLong,
                            position = i.get("position").asInt,
                            notes = i.str("notes").orEmpty(),
                        )
                    } catch (e: RuntimeException) {
                        null
                    }
                }.sortedBy { it.position }
                Setlist(
                    id = o.get("id").asLong,
                    title = o.str("title")?.takeIf { it.isNotBlank() } ?: "세트리스트",
                    ensembleName = o.get("ensemble")?.takeIf { it.isJsonObject }?.asJsonObject?.str("name"),
                    items = items,
                )
            } catch (e: RuntimeException) {
                null
            }
        }
    }

    /** 곡 순서대로 이 TV 의 파일과 잇는다 — 숨긴 악보 · 파일이 없는 악보는 [SetlistEntry.path] 가 null */
    fun entries(setlist: Setlist, synced: List<SyncedScore>): List<SetlistEntry> {
        val byId = synced.filter { !it.hidden }.associateBy { it.serverId }
        return setlist.items.map { item ->
            SetlistEntry(item, byId[item.scoreId]?.filePath?.takeIf { File(it).isFile })
        }
    }

    private fun com.google.gson.JsonArray?.orEmpty(): List<com.google.gson.JsonElement> = this?.toList() ?: emptyList()

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
