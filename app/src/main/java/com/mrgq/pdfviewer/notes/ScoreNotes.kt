package com.mrgq.pdfviewer.notes

/**
 * 악보 메모 하나 (P11) — **원본 PDF 의 쪽 번호 + 그 쪽의 pt**(왼 위 원점, `PdfRenderer` 크기)에 붙는다.
 * 마디 박스(`ScoreMeasure`)와 같은 좌표라 클리핑 · 두 쪽 · 휴대폰 조각이 바뀌어도 악보의 같은 자리에 있다.
 */
sealed class ScoreNote {
    /**
     * 무작위 문자열 — 여러 기기(태블릿 · 휴대폰, 지휘자 둘)가 같은 메모 문서를 고쳐 서버에서 합칠 때 겹치지 않게
     * (ScoreMateServer 076 의 요청, 2026-10-07). [ScoreNotes.nextId]
     */
    abstract val id: String
    abstract val page: Int
    abstract val color: Int
    /**
     * 붙는 보표 — 시스템 안 순번(`ScoreStaff.staffIndex`, 파트보 `partStaff` 비트와 같은 번호). 쓸 때 [NoteStaff] 로 정한다.
     * 파트보에서 이 메모를 그 파트 화면으로 옮기는 데 쓴다(P11 §3.6). 악보 분석이 없으면 null
     */
    abstract val staff: Int?
    /** 쓴 사람 표시 이름 — 지휘자 메모에서 서버가 새 메모에 찍는다(앱이 보낸 것은 서버가 덮어씀). 개인 메모 · 동기화 전은 null */
    abstract val author: String?

    /**
     * 펜 그림 — **한 번에 그린 획 여럿이 메모 하나**(사용자 요청: 선을 여러 개 긋고 확인을 누르면 전체가 한 오브젝트).
     * [strokes] 의 각 획은 x, y, x, y … (pt). [widthPt] 굵기도 pt 라 확대가 달라도 악보 대비 같은 굵기. 색 · 굵기는 묶음 전체에 하나
     */
    data class Ink(
        override val id: String,
        override val page: Int,
        override val color: Int,
        val widthPt: Float,
        val strokes: List<List<Float>>,
        override val staff: Int? = null,
        override val author: String? = null,
    ) : ScoreNote()

    /**
     * 글자 (2단계) — [x], [y] 는 글자 상자의 **왼 위**(pt), [sizePt] 는 글자 크기(pt). 여러 줄은 `\n`.
     * 줄 배치는 기기 · 웹이 같게 그리도록 정해 둔다([TextLayout]): 첫 줄 기준선 = y + 0.8 × size, 줄 간격 1.2 × size
     */
    data class Text(
        override val id: String,
        override val page: Int,
        override val color: Int,
        val sizePt: Float,
        val x: Float,
        val y: Float,
        val text: String,
        override val staff: Int? = null,
        override val author: String? = null,
    ) : ScoreNote()

    /** (dx, dy) pt 만큼 옮긴 **새 메모** — 메모는 불변이라 새 id ([id]), 붙는 보표도 새로 ([staff]) */
    fun movedBy(dx: Float, dy: Float, id: String, staff: Int?): ScoreNote = when (this) {
        is Ink -> copy(id = id, strokes = strokes.map { s -> s.mapIndexed { i, v -> if (i % 2 == 0) v + dx else v + dy } }, staff = staff, author = null)
        is Text -> copy(id = id, x = x + dx, y = y + dy, staff = staff, author = null)
    }
}

/** 글자 메모의 줄 배치 (pt) — 앱 · 서버 웹 겹쳐 보기가 같게 쓴다 */
object TextLayout {
    const val BASELINE = 0.8f
    const val LINE_HEIGHT = 1.2f

    fun lines(text: String): List<String> = text.split('\n')

    /** [i] 번째 줄의 기준선 y */
    fun baseline(note: ScoreNote.Text, i: Int): Float = note.y + note.sizePt * (BASELINE + LINE_HEIGHT * i)

    /** 상자 높이 */
    fun height(note: ScoreNote.Text): Float = note.sizePt * LINE_HEIGHT * lines(note.text).size
}

/**
 * 한 악보 파일의 메모 — 더하기 · 지우기와 되돌리기/다시. 되돌리기는 이 파일을 연 동안만 (파일을 바꾸면 새로).
 * 저장은 바뀔 때마다 [notes] 를 통째로 곁 파일에 ([ScoreNotesFile]).
 */
class ScoreNotes(initial: List<ScoreNote> = emptyList()) {

    /** 한 번의 동작 — 지우개로 한 번 문지른 것은 여러 개를 함께 지운다 (되돌리기도 한 번) */
    private data class Op(val added: List<ScoreNote>, val removed: List<ScoreNote>) {
        fun inverse() = Op(removed, added)
    }

    private val list = initial.toMutableList()
    private val undoStack = ArrayDeque<Op>()
    private val redoStack = ArrayDeque<Op>()

    val notes: List<ScoreNote> get() = list
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun onPage(page: Int): List<ScoreNote> = list.filter { it.page == page }

    /** 새 메모 id — 무작위 16자 (64비트). 이 문서에 이미 있으면 다시 */
    fun nextId(): String {
        while (true) {
            val id = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            if (list.none { it.id == id }) return id
        }
    }

    fun add(note: ScoreNote) = push(Op(listOf(note), emptyList()))

    /** 고치기 · 옮기기 — [old] 를 지우고 [new](새 id)를 더한다. 되돌리기 한 번 */
    fun replace(old: ScoreNote, new: ScoreNote): Boolean {
        if (list.none { it.id == old.id }) return false
        push(Op(listOf(new), listOf(old)))
        return true
    }

    fun byId(id: String): ScoreNote? = list.firstOrNull { it.id == id }

    /** 여러 개를 한 번에. 없는 것은 무시, 하나도 없으면 false */
    fun remove(notes: Collection<ScoreNote>): Boolean {
        val present = notes.filter { n -> list.any { it.id == n.id } }.distinctBy { it.id }
        if (present.isEmpty()) return false
        push(Op(emptyList(), present))
        return true
    }

    fun undo(): Boolean {
        val op = undoStack.removeLastOrNull() ?: return false
        apply(op.inverse())
        redoStack.addLast(op)
        return true
    }

    fun redo(): Boolean {
        val op = redoStack.removeLastOrNull() ?: return false
        apply(op)
        undoStack.addLast(op)
        return true
    }

    private fun push(op: Op) {
        apply(op)
        undoStack.addLast(op)
        redoStack.clear()
    }

    private fun apply(op: Op) {
        val gone = op.removed.map { it.id }.toSet()
        list.removeAll { it.id in gone }
        list += op.added
    }
}
