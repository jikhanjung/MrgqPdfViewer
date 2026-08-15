package com.mrgq.pdfviewer

/**
 * 합주에서 이 기기가 맡은 역할.
 *
 * - [CONDUCTOR] 지휘자 — 페이지·파일 변경을 브로드캐스트한다
 * - [PERFORMER] 연주자 — 지휘자의 신호를 받아 따라간다
 * - [NONE] 단독 사용
 *
 * (2026-08-15: 원래 `CollaborationMessage.kt` 안에 있었다. 그 파일의 나머지 —
 * 와이어 포맷을 별도로 정의한 `CollaborationMessage` 데이터 클래스와 빌더 일체 — 는
 * 어디서도 참조되지 않는 dead code 여서 삭제하고, 살아 있는 이 enum 만 옮겼다.
 * 실제 와이어 포맷은 [CollaborationProtocol] 이 단일 출처다.)
 */
enum class CollaborationMode {
    NONE,
    CONDUCTOR,
    PERFORMER
}
