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

    /** 펜 획 — [points] 는 x, y, x, y … (pt). [widthPt] 굵기도 pt 라 확대가 달라도 악보 대비 같은 굵기 */
    data class Ink(
        override val id: String,
        override val page: Int,
        override val color: Int,
        val widthPt: Float,
        val points: List<Float>,
        override val staff: Int? = null,
    ) : ScoreNote()
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
