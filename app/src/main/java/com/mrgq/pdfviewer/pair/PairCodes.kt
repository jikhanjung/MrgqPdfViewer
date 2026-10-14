package com.mrgq.pdfviewer.pair

import kotlin.random.Random

/**
 * 짝 맞추기 번호 (사용자 요청 2026-10-14) — 주 기기는 두 자리 번호를 화면에 띄우고 찾기 알림(`pair_announce.code`)에 싣는다.
 * 보조 기기는 번호 [CHOICES] 개 중 주 기기 화면의 것을 고른다 — 같은 Wi-Fi 에 기다리는 기기가 여럿이거나 같은 모델이어도 헷갈리지 않게.
 * 보안이 아니라 고르기 돕기다(번호는 알림에 그대로 실린다)
 */
object PairCodes {
    const val CHOICES = 3
    private val RANGE = 10..99

    fun newCode(random: Random = Random.Default): String = RANGE.random(random).toString()

    /** 미끼 번호 — [taken] 에 없는 두 자리 */
    internal fun decoy(random: Random, taken: Collection<String>): String {
        while (true) {
            val code = newCode(random)
            if (code !in taken) return code
        }
    }
}

/**
 * 보조 기기가 보여 줄 번호들 — 찾은 주 기기의 번호(옛 판처럼 번호가 없으면 기기 이름)와 미끼를 섞어 늘 [size] 개 이상.
 * 처음 찾은 번호로 칸을 만들고, 나중에 찾은 번호는 미끼 하나를 대신한다(이미 보이는 칸은 자리가 바뀌지 않는다). 미끼가 없으면 칸을 늘린다
 */
class PairCodeChoices(private val random: Random = Random.Default, private val size: Int = PairCodes.CHOICES) {
    private val slots = mutableListOf<String>()
    private val real = mutableSetOf<String>()

    val options: List<String> get() = slots.toList()

    fun isReal(label: String): Boolean = label in real

    /** 찾은 주 기기의 [label] 을 넣는다 — 보이는 칸이 바뀌었으면 true */
    fun add(label: String): Boolean {
        if (!real.add(label)) return false
        if (label in slots) return false // 미끼와 같은 번호였다 — 이제 진짜
        if (slots.isEmpty()) {
            repeat(size - 1) { slots += PairCodes.decoy(random, slots + label) }
            slots.add(random.nextInt(size), label)
            return true
        }
        val decoys = slots.indices.filter { slots[it] !in real }
        if (decoys.isEmpty()) slots += label else slots[decoys[random.nextInt(decoys.size)]] = label
        return true
    }
}
