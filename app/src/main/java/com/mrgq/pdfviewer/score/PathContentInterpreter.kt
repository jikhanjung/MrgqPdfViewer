package com.mrgq.pdfviewer.score

import kotlin.math.max
import kotlin.math.min

/**
 * PDF 콘텐츠 스트림 바이트를 직접 읽어, 칠하거나 끝낸 경로마다 바운딩 박스를 넘긴다 — 악보 분석 전용 경량 해석기.
 *
 * ## 왜 PdfBox 엔진을 쓰지 않나
 *
 * PdfBox-Android 의 `PDFGraphicsStreamEngine` 은 Moldau 총보(13쪽, 압축 해제 3.1MB, 토큰 43만 개)
 * 한 번 분석에 **900MB 를 할당**했다 — 토큰화 630MB(토큰당 약 1.5KB) + 곡선·직선 연산자 260MB.
 * 최대 힙은 30MB 대였지만 이 할당 폭주가 GC 60여 회와 힙 조각화를 일으켜 API 21 에서 OOM 이 났다 (#049).
 *
 * 이 해석기는 **필요한 연산자만** 처리하고, 숫자는 원시 배열에, 그래픽 상태는 재사용하는 배열에 둔다.
 * 할당은 결과 박스와 Form XObject 호출 정도다. PdfBox 는 문서 열기·스트림 압축 해제·XObject 조회에만 쓴다.
 *
 * ## 동작 (PdfBox 엔진과 같게 — `PathInterpreterEquivalenceTest` 가 Moldau 전 페이지로 대조)
 *
 * - CTM: `q`/`Q` 스택, `cm` 은 새 행렬 × 현재 행렬
 * - 경로 점: `m`·`l` 은 그 점, `c`·`v`·`y` 는 **끝점만** + 곡선 표시, `re` 는 네 모서리
 * - 칠(`f F f* B B* b b* S s`)과 `n`(클립 뒤 끝내기) 에서 박스를 넘긴다. `W`·`h` 는 아무것도 안 한다
 * - `Do`: Form XObject 면 행렬을 곱해 그 내용을 실행한다 (깊이 제한). 이미지는 무시
 * - 텍스트·색·선 속성 등 나머지 연산자, 문자열·배열·딕셔너리 안의 값, 주석, 인라인 이미지 데이터는 건너뛴다
 * - 피연산자가 모자라는 연산자는 건너뛴다
 *
 * Android·PdfBox 에 의존하지 않는다 — JVM 단위 테스트 대상.
 *
 * @param originX 페이지 원점 (CropBox 좌하단). 넘기는 박스에서 뺀다
 */
class PathContentInterpreter(
    private val originX: Float = 0f,
    private val originY: Float = 0f,
    private val sink: (PathBox) -> Unit,
) {

    fun interface XObjectResolver {
        /** [name] 이 Form XObject 면 그 내용, 이미지이거나 없으면 null. */
        fun form(name: String): FormXObject?
    }

    /** @param matrix PDF 행렬 [a b c d e f] */
    class FormXObject(val content: ByteArray, val matrix: FloatArray, val resolver: XObjectResolver?)

    private val operands = FloatArray(MAX_OPERANDS)
    private var operandCount = 0
    private var nameStart = -1
    private var nameEnd = -1

    private val ctm = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
    private val savedStates = ArrayList<FloatArray>()
    private var stateDepth = 0
    /** Form XObject 안에서는 바깥 상태까지 꺼내지 못하게 한다 (PdfBox 도 폼마다 스택을 따로 둔다). */
    private var stateFloor = 0

    private var minX = 0f
    private var minY = 0f
    private var maxX = 0f
    private var maxY = 0f
    private var hasPoints = false
    private var curved = false

    fun run(content: ByteArray, resolver: XObjectResolver? = null) {
        interpret(content, resolver, formDepth = 0)
    }

    private fun interpret(content: ByteArray, resolver: XObjectResolver?, formDepth: Int) {
        val n = content.size
        var i = 0
        var arrayDepth = 0
        var dictDepth = 0
        while (i < n) {
            val b = content[i].toInt() and 0xFF
            when {
                isWhitespace(b) -> i++
                b == PERCENT -> while (i < n && content[i].toInt() != LF && content[i].toInt() != CR) i++
                b == LPAREN -> i = skipLiteralString(content, i)
                b == LT -> if (i + 1 < n && content[i + 1].toInt() == LT) {
                    dictDepth++
                    i += 2
                } else {
                    i = skipHexString(content, i)
                }
                b == GT -> if (i + 1 < n && content[i + 1].toInt() == GT) {
                    dictDepth = max(0, dictDepth - 1)
                    i += 2
                } else {
                    i++
                }
                b == LBRACKET -> { arrayDepth++; i++ }
                b == RBRACKET -> { arrayDepth = max(0, arrayDepth - 1); i++ }
                b == LBRACE || b == RBRACE || b == RPAREN -> i++
                b == SLASH -> {
                    val end = regularEnd(content, i + 1)
                    if (arrayDepth == 0 && dictDepth == 0) {
                        nameStart = i + 1
                        nameEnd = end
                    }
                    i = end
                }
                else -> {
                    val end = max(regularEnd(content, i), i + 1)
                    i = if (arrayDepth > 0 || dictDepth > 0) {
                        end
                    } else if (isNumberStart(b)) {
                        pushNumber(content, i, end)
                        end
                    } else {
                        operator(content, i, end, resolver, formDepth)
                    }
                }
            }
        }
    }

    /** 연산자를 실행하고 다음 읽을 위치를 돌려준다 (인라인 이미지는 데이터 뒤로 건너뛴다). */
    private fun operator(content: ByteArray, start: Int, end: Int, resolver: XObjectResolver?, formDepth: Int): Int {
        var next = end
        val c0 = content[start].toInt()
        when (end - start) {
            1 -> when (c0) {
                'q'.code -> save()
                'Q'.code -> restore()
                'm'.code, 'l'.code -> if (operandCount >= 2) addPoint(operand(2), operand(1))
                'c'.code -> if (operandCount >= 6) {
                    curved = true
                    addPoint(operand(2), operand(1))
                }
                'v'.code, 'y'.code -> if (operandCount >= 4) {
                    curved = true
                    addPoint(operand(2), operand(1))
                }
                'f'.code, 'F'.code, 'B'.code, 'b'.code, 'S'.code, 's'.code, 'n'.code -> finishPath()
            }
            2 -> {
                val c1 = content[start + 1].toInt()
                when {
                    c0 == 'c'.code && c1 == 'm'.code -> if (operandCount >= 6) concat(
                        operand(6), operand(5), operand(4), operand(3), operand(2), operand(1)
                    )
                    c0 == 'r'.code && c1 == 'e'.code -> if (operandCount >= 4) rectangle(operand(4), operand(3), operand(2), operand(1))
                    c1 == '*'.code && (c0 == 'f'.code || c0 == 'B'.code || c0 == 'b'.code) -> finishPath()
                    c0 == 'D'.code && c1 == 'o'.code -> drawXObject(content, resolver, formDepth)
                    c0 == 'B'.code && c1 == 'I'.code -> next = skipInlineImage(content, end)
                }
            }
        }
        operandCount = 0
        nameStart = -1
        return next
    }

    /** 마지막에서 [fromEnd] 번째 피연산자 (1 = 맨 마지막). */
    private fun operand(fromEnd: Int) = operands[operandCount - fromEnd]

    private fun pushNumber(content: ByteArray, start: Int, end: Int) {
        var j = start
        var negative = false
        val sign = content[j].toInt()
        if (sign == '-'.code || sign == '+'.code) {
            negative = sign == '-'.code
            j++
        }
        var value = 0.0
        var digits = false
        while (j < end && content[j].toInt() in DIGIT_0..DIGIT_9) {
            value = value * 10 + (content[j] - DIGIT_0)
            digits = true
            j++
        }
        if (j < end && content[j].toInt() == '.'.code) {
            j++
            var scale = 0.1
            while (j < end && content[j].toInt() in DIGIT_0..DIGIT_9) {
                value += (content[j] - DIGIT_0) * scale
                scale *= 0.1
                digits = true
                j++
            }
        }
        if (!digits) return // "-" 같은 깨진 토큰은 무시
        val v = (if (negative) -value else value).toFloat()
        if (operandCount == MAX_OPERANDS) {
            // 넘치면 가장 오래된 것을 버린다 — 연산자는 마지막 N 개만 쓴다
            System.arraycopy(operands, 1, operands, 0, MAX_OPERANDS - 1)
            operandCount--
        }
        operands[operandCount++] = v
    }

    private fun addPoint(x: Float, y: Float) {
        val px = ctm[0] * x + ctm[2] * y + ctm[4] - originX
        val py = ctm[1] * x + ctm[3] * y + ctm[5] - originY
        if (!hasPoints) {
            minX = px; maxX = px; minY = py; maxY = py
            hasPoints = true
        } else {
            minX = min(minX, px); maxX = max(maxX, px)
            minY = min(minY, py); maxY = max(maxY, py)
        }
    }

    private fun rectangle(x: Float, y: Float, w: Float, h: Float) {
        addPoint(x, y)
        addPoint(x + w, y)
        addPoint(x + w, y + h)
        addPoint(x, y + h)
    }

    private fun finishPath() {
        if (hasPoints) sink(PathBox(minX, minY, maxX, maxY, curved))
        hasPoints = false
        curved = false
    }

    /** CTM ← [a b c d e f] × CTM */
    private fun concat(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
        val a2 = ctm[0]; val b2 = ctm[1]; val c2 = ctm[2]; val d2 = ctm[3]; val e2 = ctm[4]; val f2 = ctm[5]
        ctm[0] = a * a2 + b * c2
        ctm[1] = a * b2 + b * d2
        ctm[2] = c * a2 + d * c2
        ctm[3] = c * b2 + d * d2
        ctm[4] = e * a2 + f * c2 + e2
        ctm[5] = e * b2 + f * d2 + f2
    }

    private fun save() {
        if (stateDepth == savedStates.size) savedStates.add(FloatArray(6))
        ctm.copyInto(savedStates[stateDepth])
        stateDepth++
    }

    private fun restore() {
        if (stateDepth > stateFloor) {
            stateDepth--
            savedStates[stateDepth].copyInto(ctm)
        }
    }

    private fun drawXObject(content: ByteArray, resolver: XObjectResolver?, formDepth: Int) {
        if (resolver == null || nameStart < 0 || formDepth >= MAX_FORM_DEPTH) return
        val form = resolver.form(decodeName(content, nameStart, nameEnd)) ?: return

        val outerFloor = stateFloor
        save()
        stateFloor = stateDepth
        val m = form.matrix
        concat(m[0], m[1], m[2], m[3], m[4], m[5])
        interpret(form.content, form.resolver ?: resolver, formDepth + 1)
        stateDepth = stateFloor
        stateFloor = outerFloor
        restore()
    }

    private companion object {
        const val MAX_OPERANDS = 32
        const val MAX_FORM_DEPTH = 8

        const val LF = 10
        const val CR = 13
        const val PERCENT = '%'.code
        const val LPAREN = '('.code
        const val RPAREN = ')'.code
        const val LT = '<'.code
        const val GT = '>'.code
        const val LBRACKET = '['.code
        const val RBRACKET = ']'.code
        const val LBRACE = '{'.code
        const val RBRACE = '}'.code
        const val SLASH = '/'.code
        const val BACKSLASH = '\\'.code
        const val DIGIT_0 = '0'.code
        const val DIGIT_9 = '9'.code

        fun isWhitespace(b: Int) = b == 32 || b == 10 || b == 13 || b == 9 || b == 12 || b == 0

        fun isDelimiter(b: Int) =
            b == LPAREN || b == RPAREN || b == LT || b == GT || b == LBRACKET || b == RBRACKET ||
                b == LBRACE || b == RBRACE || b == SLASH || b == PERCENT

        fun isNumberStart(b: Int) = b in DIGIT_0..DIGIT_9 || b == '-'.code || b == '+'.code || b == '.'.code

        fun regularEnd(content: ByteArray, from: Int): Int {
            var j = from
            while (j < content.size) {
                val c = content[j].toInt() and 0xFF
                if (isWhitespace(c) || isDelimiter(c)) break
                j++
            }
            return j
        }

        /** `( … )` — 괄호 중첩과 백슬래시 이스케이프를 따른다. */
        fun skipLiteralString(content: ByteArray, start: Int): Int {
            var depth = 0
            var j = start
            while (j < content.size) {
                when (content[j].toInt()) {
                    BACKSLASH -> j += 2
                    LPAREN -> { depth++; j++ }
                    RPAREN -> {
                        depth--
                        j++
                        if (depth == 0) return j
                    }
                    else -> j++
                }
            }
            return content.size
        }

        /** `< … >` */
        fun skipHexString(content: ByteArray, start: Int): Int {
            var j = start + 1
            while (j < content.size && content[j].toInt() != GT) j++
            return min(content.size, j + 1)
        }

        /** `BI … ID <데이터> EI` — ID 뒤 공백 하나부터, 공백으로 둘러싸인 EI 까지 건너뛴다. */
        fun skipInlineImage(content: ByteArray, from: Int): Int {
            val n = content.size
            var j = from
            var dataStart = -1
            while (j + 1 < n) {
                if (content[j].toInt() == 'I'.code && content[j + 1].toInt() == 'D'.code &&
                    (j == 0 || isWhitespace(content[j - 1].toInt() and 0xFF) || isDelimiter(content[j - 1].toInt() and 0xFF)) &&
                    (j + 2 >= n || isWhitespace(content[j + 2].toInt() and 0xFF))
                ) {
                    dataStart = j + 3
                    break
                }
                j++
            }
            if (dataStart < 0) return n
            var k = dataStart
            while (k + 1 < n) {
                if (content[k].toInt() == 'E'.code && content[k + 1].toInt() == 'I'.code &&
                    k > 0 && isWhitespace(content[k - 1].toInt() and 0xFF) &&
                    (k + 2 >= n || isWhitespace(content[k + 2].toInt() and 0xFF) || isDelimiter(content[k + 2].toInt() and 0xFF))
                ) {
                    return k + 2
                }
                k++
            }
            return n
        }

        /** 이름의 `#xx` 이스케이프를 푼다 (`/Fm#201` → "Fm 1"). */
        fun decodeName(content: ByteArray, start: Int, end: Int): String {
            val sb = StringBuilder(end - start)
            var j = start
            while (j < end) {
                val c = content[j].toInt() and 0xFF
                if (c == '#'.code && j + 2 < end) {
                    val hex = String(content, j + 1, 2, Charsets.ISO_8859_1).toIntOrNull(16)
                    if (hex != null) {
                        sb.append(hex.toChar())
                        j += 3
                        continue
                    }
                }
                sb.append(c.toChar())
                j++
            }
            return sb.toString()
        }
    }
}
