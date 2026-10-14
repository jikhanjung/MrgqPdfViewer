package com.mrgq.pdfviewer

import com.mrgq.pdfviewer.follow.RollingTurns.Spread
import com.mrgq.pdfviewer.pair.PairCodeChoices
import com.mrgq.pdfviewer.pair.PairCodes
import com.mrgq.pdfviewer.pair.PairDiscovery
import com.mrgq.pdfviewer.pair.PairLink
import com.mrgq.pdfviewer.pair.PairProtocol
import com.mrgq.pdfviewer.pair.PairProtocol.Message
import com.mrgq.pdfviewer.pair.PairSide
import com.mrgq.pdfviewer.pair.PairSpread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** 두 대로 펼쳐 보기 (P22) — 펼침 계산 · 짝 메시지 · 짝 연결(루프백) */
class PairTest {

    // ── 펼침 ──

    @Test
    fun 자리마다_펼침의_한_쪽_홀수_끝의_오른쪽은_빈_화면() {
        assertEquals(0, PairSpread.pageOf(Spread(0, 1), PairSide.LEFT))
        assertEquals(1, PairSpread.pageOf(Spread(0, 1), PairSide.RIGHT))
        assertNull(PairSpread.pageOf(Spread(4, null), PairSide.RIGHT))
    }

    @Test
    fun 넘기면_TV_두_쪽처럼_두_쪽씩() {
        assertEquals(Spread(2, 3), PairSpread.next(Spread(0, 1), 5))
        assertEquals(Spread(4, null), PairSpread.next(Spread(2, 3), 5))
        assertNull(PairSpread.next(Spread(4, null), 5))
        assertEquals(Spread(2, 3), PairSpread.previous(Spread(4, null), 5))
        assertNull(PairSpread.previous(Spread(0, 1), 5))
        // 차례 넘김 펼침(3 | 2)에서도 짝 단위로
        assertEquals(Spread(4, 5), PairSpread.next(Spread(2, 1), 6))
        assertEquals(Spread(0, 1), PairSpread.previous(Spread(2, 1), 6))
    }

    @Test
    fun 그_쪽이_든_펼침_지금_펼침에_있으면_그대로() {
        assertEquals(Spread(2, 3), PairSpread.toShow(3, null, 6))
        assertEquals(Spread(2, 1), PairSpread.toShow(1, Spread(2, 1), 6))
        assertEquals(Spread(4, 5), PairSpread.toShow(5, Spread(2, 1), 6))
    }

    // ── 메시지 ──

    @Test
    fun 메시지_왕복() {
        val messages = listOf(
            Message.Hello(PairSide.RIGHT, PairProtocol.VERSION, "TB371FC"),
            Message.SpreadUpdate("곡.pdf", "ab12", 7, Spread(6, null), 42L),
            Message.SpreadUpdate("곡.pdf", null, 7, Spread(2, 3), 43L),
            Message.Input(PairProtocol.Action.NEXT),
            Message.Input(PairProtocol.Action.SYNC),
            Message.Focus(measure = 57, cursor = false, system = 57, systemStrong = false, waiting = null),
            Message.Focus(measure = null, cursor = false, system = null, systemStrong = true, waiting = 12),
            Message.Bye,
            Message.Ping,
            Message.SpreadUpdate("곡.pdf", "ab12", 7, Spread(2, 1), 44L, clipTop = 5, clipBottom = 3),
            Message.Input(PairProtocol.Action.START),
            Message.Input(PairProtocol.Action.LISTEN),
            Message.Input(PairProtocol.Action.STOP),
            Message.Focus(measure = 3, cursor = false, system = null, systemStrong = true, waiting = null, playing = PairProtocol.PLAYING_MIC),
            Message.Notice("이 악보는 마디를 못 읽었어요"),
        )
        for (m in messages) assertEquals(m, PairProtocol.decode(PairProtocol.encode(m)))
    }

    @Test
    fun 펼침의_쪽은_메시지에서_1부터() {
        val wire = PairProtocol.encode(Message.SpreadUpdate("곡.pdf", null, 5, Spread(4, null), 1L))
        assertTrue(wire, wire.contains("\"left\":5") && !wire.contains("\"right\""))
        assertEquals(Spread(0, 1), (PairProtocol.decode("""{"type":"spread","pages":2,"left":1,"right":2,"seq":1}""") as Message.SpreadUpdate).spread)
    }

    @Test
    fun 깨진_줄_모르는_종류_쪽_밖은_버린다() {
        assertNull(PairProtocol.decode("{"))
        assertNull(PairProtocol.decode("""{"type":"later"}"""))
        assertNull(PairProtocol.decode("""{"type":"spread","pages":3,"left":4}"""))
        assertNull(PairProtocol.decode("""{"type":"spread","pages":3,"left":0}""")) // 1부터
        assertNull(PairProtocol.decode("""{"type":"hello","side":"up"}"""))
        // 모르는 필드는 무시
        assertEquals(Message.Input(PairProtocol.Action.PREVIOUS), PairProtocol.decode("""{"type":"input","action":"prev","x":1}"""))
    }

    @Test
    fun 찾기_알림() {
        val host = PairDiscovery.parse("""{"type":"pair_announce","name":"Pad","side":"left","port":9093,"version":1}""", "192.168.0.5")
        assertEquals(PairDiscovery.Host("Pad", "192.168.0.5", 9093, PairSide.LEFT, 1), host)
        assertNull(PairDiscovery.parse("""{"type":"conductor_announcement"}""", "1.2.3.4"))
        val coded = PairDiscovery.parse("""{"type":"pair_announce","name":"Pad","side":"right","port":9093,"version":3,"code":"37"}""", "192.168.0.6")
        assertEquals("37", coded?.code)
    }

    // ── 짝 맞추기 번호 ──

    @Test
    fun 번호는_두_자리() {
        val random = Random(1)
        repeat(200) { assertTrue(PairCodes.newCode(random).toInt() in 10..99) }
    }

    @Test
    fun 찾은_번호_하나와_미끼_둘_서로_다름() {
        repeat(50) { seed ->
            val choices = PairCodeChoices(Random(seed))
            assertTrue(choices.add("37"))
            val options = choices.options
            assertEquals(3, options.size)
            assertEquals(3, options.toSet().size)
            assertTrue("37" in options)
            assertTrue(choices.isReal("37"))
            assertEquals(1, options.count { choices.isReal(it) })
        }
    }

    @Test
    fun 나중에_찾은_번호는_미끼를_대신하고_보이는_칸은_그대로() {
        repeat(50) { seed ->
            val choices = PairCodeChoices(Random(seed))
            choices.add("37")
            val first = choices.options
            assertFalse(choices.add("37")) // 같은 기기를 또 들음
            val other = (10..99).map { it.toString() }.first { it !in first }
            assertTrue(choices.add(other))
            val second = choices.options
            assertEquals(3, second.size)
            assertEquals(first.indexOf("37"), second.indexOf("37"))
            assertTrue(other in second)
            // 넷째 기기 — 미끼가 없으면 칸을 늘린다
            choices.add(second.first { !choices.isReal(it) }) // 미끼와 같은 번호를 찾음 → 칸 그대로
            assertEquals(3, choices.options.size)
            choices.add("Pad") // 번호 없는 옛 판은 이름으로
            assertEquals(4, choices.options.size)
            assertTrue(choices.options.all { choices.isReal(it) })
        }
    }

    // ── 연결 ──

    private class Recorder : PairLink.Listener {
        val connected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val messages = LinkedBlockingQueue<Message>()
        override fun onConnected() = connected.countDown()
        override fun onMessage(message: Message) { messages += message }
        override fun onDisconnected() = disconnected.countDown()
    }

    @Test
    fun 루프백_짝_연결_주고받기_끊김() {
        val host = PairLink()
        val hostEvents = Recorder().also { host.listener = it }
        assertTrue(host.listen(0))
        val guest = PairLink()
        val guestEvents = Recorder().also { guest.listener = it }
        try {
            assertTrue(guest.connect("127.0.0.1", host.localPort))
            assertTrue(hostEvents.connected.await(3, TimeUnit.SECONDS))
            assertTrue(guestEvents.connected.await(3, TimeUnit.SECONDS))

            val spread = Message.SpreadUpdate("곡.pdf", "ab", 4, Spread(2, 3), 1L)
            assertTrue(host.send(spread))
            assertEquals(spread, guestEvents.messages.poll(3, TimeUnit.SECONDS))
            assertTrue(guest.send(Message.Input(PairProtocol.Action.NEXT)))
            assertEquals(Message.Input(PairProtocol.Action.NEXT), hostEvents.messages.poll(3, TimeUnit.SECONDS))

            // 짝 기기가 닫으면 주 기기가 끊김을 안다(듣기는 그대로)
            guest.close()
            assertTrue(hostEvents.disconnected.await(3, TimeUnit.SECONDS))
            assertTrue(!host.isConnected)
        } finally {
            guest.close()
            host.close()
        }
    }
}
