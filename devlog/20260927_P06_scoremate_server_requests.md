# P06 — ScoreMate 서버에 요청할 것 (TV 클라이언트 C1 · C2 구현에서 나온 것)

작성일: 2026-09-27
대상: ScoreMateServer (`backend/`) · 근거: 서버 `origin/main` 8890dae 의 코드와 devlog 059 · 060 · 061
관련: [`P05`](20260926_P05_scoremate_client_plan.md) · [`058`](20260927_058_scoremate_device_link.md)(C1) · [`059`](20260927_059_scoremate_score_sync.md)(C2)

TV 쪽은 아래 항목이 없어도 동작한다(우회해 두었다). 서버에서 고치면 우회가 필요 없어지거나 사용자가 덜 헷갈린다.

---

## 1. 🔴 앙상블 이름을 바꾸면 그 앙상블 악보의 `updated_at` 을 올려 주세요

**문제**: TV 는 받은 악보를 `PDFs/ScoreMate/<앙상블 이름>/` 폴더에 둔다. 동기화는 악보의 `updated_at`(과 들어온 시각)이 커서 뒤인 것만 보내는데,
앙상블 이름을 바꿔도(`EnsembleViewSet.perform_update` → `serializer.save()`) 악보의 `updated_at` 은 그대로다.
그래서 **TV 는 그 앙상블 악보가 하나라도 바뀌기 전까지 옛 이름 폴더를 계속 쓴다.** 앙상블 삭제(`services.delete_ensemble`)는 이미
`ensemble.scores.update(updated_at=timezone.now())` 로 올리고 있다 — 같은 처리가 이름 바꾸기에도 필요하다.

**제안** (`ensembles/views.py`):

```python
def perform_update(self, serializer):
    services.require_manager(serializer.instance, self.request.user, 'Only owners and leaders can edit the ensemble.')
    old_name = serializer.instance.name
    ensemble = serializer.save()
    if ensemble.name != old_name:
        # TV 가 폴더 이름을 따라가게 — 동기화 기준 시각을 올린다 (delete_ensemble 과 같은 방식)
        ensemble.scores.update(updated_at=timezone.now())
```

테스트: 앙상블 이름 변경 뒤 커서로 동기화하면 그 앙상블 악보가 `scores` 에 다시 오고 `ensemble.name` 이 새 이름.

TV 쪽 우회(C2): 폴더 이름을 앙상블 id 마다 하나로 모아, 이번에 받은 악보의 이름을 같은 앙상블의 다른 악보에도 쓴다 — 폴더가 둘로 갈라지지는 않지만,
그 앙상블 악보가 하나도 오지 않으면 옛 이름에 머문다.

## 2. 🟡 (선택) 같은 앙상블에 제목 · 파트가 같은 악보를 올리면 웹에서 알려 주세요

TV 는 같은 폴더에 같은 이름(`<제목> (<파트>).pdf`)이 둘 이상이면 모두 ` [#<서버 id>]` 를 붙인다(P05 §6 규칙, 모든 TV 가 같은 결과).
동작에는 문제가 없지만 목록에 `아리랑 [#12].pdf` 같은 이름이 보인다. 올릴 때 "같은 제목 · 파트의 악보가 이미 있습니다 — 새 판으로 올릴까요?" 를
물어 주면 대부분 새 판(판 2)으로 올라가 이름이 겹치지 않는다. 강제(유니크 제약)까지는 필요 없다.

## 3. ✅ 확인만 — 변경 필요 없음

- **`version.sha256 == null`(처리 중) 악보가 다음 동기화에 다시 오는가** (P05 §4 의 열린 질문): 온다. `SyncScoreSerializer` 설명대로
  쪽수 · 해시가 채워지면 `updated_at` 이 바뀐다. TV 는 이번엔 건너뛰고 커서는 넘긴다
- **`download_url` 이 프록시 뒤에서 `http://` 가 되는가**: 운영 설정에 `SECURE_PROXY_SSL_HEADER` 가 있어 괜찮다. TV 는 어차피 URL 의 **경로만** 쓰고
  호스트는 설정한 서버 주소로 붙인다
- **받기 302 → 서명 URL**: TV 는 리다이렉트를 직접 따라가며 서명 URL 에는 Authorization 을 싣지 않는다(S3 계열 거부 방지). 상대 경로 Location 도 처리
- **TV 가 자기 토큰으로 `DELETE /api/v1/devices/{id}/`**: `get_queryset` 이 사용자 기준이라 된다 — "연결 해제" 에 쓴다
- **refresh 회전 · 180일 유지** (`DeviceAwareTokenRefreshSerializer`): TV 는 갱신을 한 번에 하나만 하고(Mutex) 새 refresh 를 바로 저장한다

## 4. 앞으로 (C3 · C4 에서 필요해질 수 있는 것 — 지금은 요청 아님)

- TV 에서 지운(숨긴) 서버 악보는 TV 만 안다. 웹 "TV" 화면에 "이 TV 에서 숨긴 악보" 를 보이려면 TV 가 알려야 한다 — 필요해지면 따로 API 제안
- 분석 공유(C4): `version.analyses` 가 이미 동기화 응답에 있어 TV 쪽 준비만 하면 된다
