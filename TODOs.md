# TODOs

> 바로 다음에 할 작업. 상세 설계/기록은 `devlog/` 참조.
> 최종 갱신: 2026-06-14

## 🔥 바로 다음 (Phase 0 마무리)

- [ ] **Windows Android Studio 빌드 확인** — Phase 0 동기 페이지 넘김 커밋(`ec26ac7`) 컴파일 검증
  - `./gradlew assembleDebug`
  - `setOnPageChangeReceived` / `broadcastPageChange` 시그니처 변경이 6개 파일에 걸쳐 일관한지 (컴파일 에러 시 보고)
- [ ] **2기기 실측** — 설정 → 협업 모드 → `동기 페이지 넘김 ON` 후 지휘자 넘김 동시성 확인
  - 두 화면 카메라 동시촬영으로 편차 측정 (목표 ≤ ~100ms)
  - `예약 시간(lead)` 튜닝 (기본 2000ms → 1000~2500 범위)
  - 기기 "자동 날짜/시간" ON 전제 확인 (OFF 면 편차 커짐 → 접근법 B 검토)
- [ ] **기존 경로 무변화 회귀 확인** — 동기 OFF / 단독 모드에서 기존 즉시 넘김 동일 동작
- [ ] v1 한계 점검: ① lead 대기 중 빠른 연속 넘김(같은 페이지 타깃) ② `suppressBroadcastUntil` 2초 창 (캐시 미스 느린 렌더)
  - 참고: `devlog/20260614_038_phase0_sync_page_turn_implementation.md`

## 🎯 그 다음 (Phase 1 — 자동 넘김 + 메트로놈)

- [ ] **마디 ↔ MusicXML 정렬 맵** — Sibelius에서 MusicXML 1개 export → `MusicXML 마디 ↔ PDF bbox` 매핑 JSON 생성 (반복/빈마디 정렬 로직 포함)
- [ ] **좌표 변환 유틸** — PDF 포인트 → 화면 픽셀 (렌더러의 scale/offset/클리핑/두 페이지 모드 동일 적용)
- [ ] **템포 클록 + 오버레이 View** — 현재 마디 하이라이트 + 비트 플래시(메트로놈)
  - 합주: 지휘자가 페이지 넘김은 Phase 0 예약 신호로, 연주자는 메트로놈/하이라이트만 로컬
  - 단독: 로컬 클록이 자동 넘김 + 메트로놈 모두 구동
  - 참고: `devlog/20260613_P02_score_sync_autoturn_plan.md`

## 📋 이후 (P02 로드맵)

- [ ] Phase 2 — 태블릿 플레이버 분리 (매니페스트 LAUNCHER/터치, RECORD_AUDIO)
- [ ] Phase 3 — 마이크 온셋/템포 검출 (Python PoC → TarsosDSP)
- [ ] Phase 4 — 단성 음고 스코어 팔로잉 → (장기) 합주
- [ ] (선택) Phase 0 접근법 B — 실측 편차 크면 NTP 오프셋 측정 도입 (`devlog/P03` 부록 B)

## 🧪 분석 파이프라인 보강 (data/, 선택)

- [ ] OMR 음길이(빔/깃발) 검출 정확도 ↑ (현재 마디 검산 ~50% → 90%+)
- [ ] 임시표(#♭♮) 인식 → 반음계 정확도 개선
- [ ] 다른 PDF(보표 수 가변)로 `segment_score.py` 일반화 검증
