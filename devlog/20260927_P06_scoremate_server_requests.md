# P06 — ScoreMate 서버에 요청할 것 (TV 클라이언트 C1 · C2 에서 나온 것)

작성일: 2026-09-27
대상: ScoreMateServer `backend/` — 근거 코드는 `origin/main` **8890dae** 기준
TV 쪽 문서: [`P05`](20260926_P05_scoremate_client_plan.md)(계획) · [`058`](20260927_058_scoremate_device_link.md)(C1 기기 연결) · [`059`](20260927_059_scoremate_score_sync.md)(C2 악보 동기화)

| # | 요청 | 우선 | TV 영향 |
|---|---|---|---|
| 1 | 앙상블 이름을 바꾸면 그 앙상블 악보의 `updated_at` 을 올린다 | 🔴 필요 | 안 고치면 TV 폴더 이름이 옛 이름에 머문다 |
| 2 | 같은 앙상블에 제목 · 파트가 같은 악보를 올릴 때 웹에서 알린다 | 🟡 선택 | 안 고쳐도 동작 — 파일 이름에 `[#id]` 가 붙을 뿐 |
| — | TV 가 의존하는 응답 필드 (§3) | 참고 | 바꿀 때 미리 알려 주세요 |

TV 는 두 항목 없이도 동작하도록 우회해 두었다. 서버 작업은 TV 배포와 순서가 상관없다.

---

## 1. 🔴 앙상블 이름 변경 → 그 앙상블 악보의 `updated_at` 올리기

### 배경
TV 는 받은 악보를 `PDFs/ScoreMate/<앙상블 이름>/<제목>[ (파트)].pdf` 에 둔다(P05 §6). 폴더 이름은 동기화 응답의 `scores[i].ensemble.name` 에서 온다.
동기화는 **기준 시각 = max(악보 `updated_at`, 내가 그 앙상블에 들어온 시각)** 이 커서 뒤인 악보만 보낸다(`scores/sync.py`).

### 문제
앙상블 이름을 바꿔도 악보의 `updated_at` 은 그대로다 → 이미 동기화한 TV 에는 그 앙상블 악보가 다시 오지 않는다 →
**TV 는 그 앙상블의 악보가 하나라도 바뀌기 전까지 옛 이름 폴더를 계속 쓴다.**
앙상블 **삭제**는 이미 이 문제를 처리한다(`ensembles/services.py` `delete_ensemble`: `ensemble.scores.update(updated_at=timezone.now())`).

### 재현
1. TV 가 앙상블 "Guitar Ensemble" 악보를 동기화한다 (커서 저장)
2. 웹에서 앙상블 이름을 "Guitar Quartet" 로 바꾼다
3. TV 가 저장한 커서로 `GET /api/v1/sync/scores/?cursor=…` → `scores` 가 빈 목록 (기대: 그 앙상블 악보 전부, `ensemble.name` = 새 이름)

### 원인 — 이름을 바꾸는 길이 둘
| 경로 | 코드 |
|---|---|
| API `PATCH /api/v1/ensembles/{id}/` | `ensembles/views.py` `EnsembleViewSet.perform_update` → `serializer.save()` |
| 웹 `POST /ensembles/{pk}/edit/` | `web/views.py` `ensemble_edit` → `EnsembleForm(...).save()` |

두 곳 다 고쳐야 한다 — 한 곳만 고치면 다른 길로 바꾼 이름은 TV 에 가지 않는다. 서비스 함수 하나로 모으는 것을 제안한다.

### 제안
`ensembles/services.py`:

```python
def touch_scores_if_renamed(ensemble, old_name):
    """이름이 바뀌었으면 그 앙상블 악보의 동기화 기준 시각을 올린다 — TV 가 폴더 이름을 따라가게 (delete_ensemble 과 같은 방식)"""
    if ensemble.name != old_name:
        ensemble.scores.update(updated_at=timezone.now())
```

`ensembles/views.py`:

```python
def perform_update(self, serializer):
    services.require_manager(serializer.instance, self.request.user, 'Only owners and leaders can edit the ensemble.')
    old_name = serializer.instance.name
    ensemble = serializer.save()
    services.touch_scores_if_renamed(ensemble, old_name)
```

`web/views.py` `ensemble_edit`:

```python
    old_name = ensemble.name
    form = EnsembleForm(request.POST, instance=ensemble)
    if form.is_valid():
        ensemble = form.save()
        ensemble_services.touch_scores_if_renamed(ensemble, old_name)
```

> `ModelForm` 은 `is_valid()` 때 instance 를 바꾸므로 `old_name` 은 **폼을 만들기 전에** 읽어야 한다. (`save()` 뒤 비교는 늘 같다)

설명(`description`)만 바꾼 경우에는 올리지 않는다 — TV 가 쓰지 않는 필드라 불필요한 재전송이다.

### 테스트 (제안 — `tests/test_sync.py` 의 `SyncTestBase` 를 이어 씀)

```python
class EnsembleRenameSyncTest(SyncTestBase):

    def leader_client(self):
        client = APIClient()
        client.force_authenticate(user=self.leader)
        return client

    def test_rename_via_api_resends_that_ensembles_scores(self):
        self.score(self.leader, 'Moldau', self.ensemble)
        self.score(self.me, 'Mine')                      # 다른 악보는 다시 오지 않는다
        first = self.sync()

        response = self.leader_client().patch(f'/api/v1/ensembles/{self.ensemble.pk}/',
                                              {'name': 'Guitar Quartet'}, format='json')
        self.assertEqual(response.status_code, 200)

        second = self.sync(first['cursor'])
        self.assertEqual(self.titles(second), ['Moldau'])
        self.assertEqual(second['scores'][0]['ensemble']['name'], 'Guitar Quartet')

    def test_rename_via_web_resends_too(self):
        self.score(self.leader, 'Moldau', self.ensemble)
        first = self.sync()

        web = Client()
        web.force_login(self.leader)
        web.post(reverse('web:ensemble_edit', args=[self.ensemble.pk]),
                 {'name': 'Guitar Quartet', 'description': ''})   # EnsembleForm 필드에 맞출 것

        self.assertEqual(self.titles(self.sync(first['cursor'])), ['Moldau'])

    def test_description_only_does_not_resend(self):
        self.score(self.leader, 'Moldau', self.ensemble)
        first = self.sync()
        self.leader_client().patch(f'/api/v1/ensembles/{self.ensemble.pk}/', {'description': 'Tue'}, format='json')
        self.assertEqual(self.sync(first['cursor'])['scores'], [])
```

(`from django.test import Client` 추가. 확인 방법: `touch_scores_if_renamed` 호출을 빼면 앞의 두 테스트가 실패해야 한다)

### 완료 기준
- API · 웹 어느 쪽으로 이름을 바꿔도 다음 동기화에 그 앙상블 악보가 새 `ensemble.name` 으로 온다
- 설명만 바꾸면 오지 않는다

### TV 쪽 (참고)
- 지금의 우회: 폴더 이름을 앙상블 id 마다 하나로 모으고 이번에 받은 이름을 우선한다 — 폴더가 둘로 갈라지지는 않지만, 그 앙상블 악보가 오지 않으면 옛 이름에 머문다
- 서버가 고쳐지면 TV 는 그대로 새 폴더로 옮긴다 (파일 레코드 경로를 먼저 바꿔 파일별 설정 유지). TV 코드 변경은 필요 없다
- 재전송 비용: 악보 수만큼 메타데이터만 온다. 파일은 sha256 이 같아 받지 않는다

---

## 2. 🟡 (선택) 같은 제목 · 파트 악보를 올릴 때 알리기

### 배경
TV 는 같은 폴더에 같은 이름(`<제목> (<파트>).pdf`)이 둘 이상이면 **모두** ` [#<서버 id>]` 를 붙인다 — 어느 TV 에서나 같은 결과가 나오는 규칙.
동작 문제는 없지만 목록에 `아리랑 [#12].pdf` 같은 이름이 보인다. 대부분은 같은 곡의 수정판을 새 악보로 올린 경우일 것이다.

### 제안
웹 업로드(`web/views.py` `score_upload`, 앙상블이 정해진 경우)에서 같은 앙상블에 제목 · 파트가 같은 악보가 이미 있으면
"같은 제목 · 파트의 악보가 있습니다 — **새 판으로 올릴까요?**"(→ `version_upload`) 를 묻는다. 막지는 않는다(유니크 제약은 필요 없다).
비교는 TV 와 같게 대소문자 무시 · 앞뒤 공백 무시면 충분하다.

---

## 3. TV 가 의존하는 응답 필드 (바꿀 때 미리 알려 주세요)

| API | TV 가 쓰는 것 |
|---|---|
| `POST /api/v1/device/code` | `device_code` · `user_code` · `verification_uri` · `verification_uri_complete` · `expires_in` · `interval` |
| `POST /api/v1/device/token` | 400 `error` 값 5가지(`authorization_pending` · `slow_down` · `access_denied` · `expired_token` · `invalid_grant`), 429 는 slow_down 으로 본다. 200 의 `access_token` · `refresh_token` · `device_id` |
| `POST /api/v1/auth/token/refresh/` | `{access, refresh}`. 무효 refresh 는 **400/401 → TV 는 해제로 보고 토큰을 지운다** (5xx · 네트워크 오류는 지우지 않음) |
| 모든 인증 API | 401 → 한 번 갱신 후 재시도. 그래도 401 이면 해제로 본다 |
| `GET /api/v1/devices/me/` · `POST …/me/heartbeat/` | `id` · `name` · `last_seen_at` |
| (서버 0.7.0 에 추가 — TV 가 아직 쓰지 않음) `GET /api/v1/devices/me/` | `sync_mode`(`setlists` · `all`) · `sync_setlists`(세트리스트 id 목록) — **읽기 전용**, 고르기는 웹에서만. §7 |
| `DELETE /api/v1/devices/{id}/` | 204 (TV 가 자기 토큰으로 해제). 404 도 해제된 것으로 본다 |
| `GET /api/v1/sync/scores/?cursor=` | `cursor` · `has_more` · `ids`(**모든 쪽에 전체 목록**) · `scores[i]` 의 `id` · `title` · `composer` · `part_name` · `ensemble.{id,name}` · `version.{number, sha256, size_bytes}` · `download_url`. 400 본문에 `"cursor"` 가 있으면 커서를 버린다 |
| `GET {download_url}` | 3xx + `Location`(절대 · 상대 모두). TV 는 리다이렉트를 직접 따라가며 **서명 URL 에는 Authorization 을 싣지 않는다**. `download_url` 은 **경로만** 쓰고 호스트는 TV 에 설정한 서버 |

`ids` 에 없는 악보는 **묻지 않고 바로 정리한다**(2026-09-27 사용자 결정 — 서버 0.7.0 의 세트리스트 동기화에서 곡목을 바꾸면 한꺼번에 빠지는 것이 정상). 파일은 지우고 파일별 설정은 남겨, 다시 곡목에 들어오면 돌아온다. (처음엔 "비거나 절반 넘게 사라지면 묻기" 안전장치가 있었다)

## 4. ✅ 확인만 — 변경 필요 없음

- **처리 중(`version.sha256 == null`) 악보가 다음 동기화에 다시 오는가**: 온다 — 쪽수 · 해시가 채워지면 `updated_at` 이 바뀐다(`SyncScoreSerializer` 설명). TV 는 건너뛰고 커서는 넘긴다
- **프록시 뒤 `download_url` 스킴**: 운영 설정에 `SECURE_PROXY_SSL_HEADER` 가 있다. TV 는 어차피 경로만 쓴다
- **TV 자기 해제**: `DeviceViewSet.get_queryset` 이 사용자 기준이라 기기 토큰으로 자기 기기를 지울 수 있다
- **refresh 회전 · 180일 유지**(`DeviceAwareTokenRefreshSerializer`): TV 는 갱신을 한 번에 하나만(Mutex) 하고 새 refresh 를 바로 저장한다

## 5. 앞으로 (요청 아님 — C3 · C4 에서 필요해지면 따로)

- TV 에서 지운(숨긴) 서버 악보는 TV 만 안다. 웹 "TV" 화면에 보이려면 TV 가 알리는 API 가 필요하다
- 분석 공유(C4): 동기화 응답의 `version.analyses` 와 `GET/PUT /scores/{id}/analysis/` 로 충분해 보인다 — TV 구현 때 다시 확인

## 6. ✅ 서버 처리 결과 (ScoreMateServer 0.6.2 · 0.6.3, 2026-09-27 운영 배포)

| # | 처리 | 서버 커밋 · 확인 |
|---|---|---|
| 1 | `ensembles/services.update_ensemble` 하나로 모음 — API `PATCH` · 웹 `ensemble_edit` 둘 다. 이름이 바뀌면 그 앙상블 악보 `updated_at` 을 올린다, 설명만이면 안 올린다 | `8b93cca`. 테스트 4개(제안 3개 + 멤버 · 빈 이름) — 재전송 줄을 빼면 2개 실패 확인. **운영**: 이름 변경 → 커서 동기화에 `('Moldau', 'P06 After')`, 설명만 → 0개 |
| 2 | 웹 업로드: 같은 곳(그 앙상블 · 내 개인 악보)에 제목 · 파트가 같은(대소문자 · 앞뒤 공백 무시) 악보가 있으면 먼저 묻는다 — **새 판으로 올리기(권장)** / 새 악보로 따로. 막지는 않는다 | `8b93cca`. 테스트 4개 |
| §3 | TV 가 의존하는 필드 — 바꾼 것 없음 | — |

함께 고친 서버 버그(TV 영향 없음, 참고): 캐시가 gunicorn 워커마다 따로여서 API 업로드 예약(`upload-url` → `upload-confirm`)이
다른 워커에서 사라지고 요청 제한이 느슨했다 → 워커 공유 파일 캐시(0.6.3, `7d21251`). 운영에서 API 업로드 10/10 확인.
TV 는 동기화 · 받기만 해서 영향이 없었다.

## 7. 서버 0.7.0 — TV 마다 받을 것 (2026-09-27, 요청 아님 · 알림)

서버 devlog `062_TV마다_세트리스트_동기화.md`.
- 기기마다 **고른 세트리스트의 곡만**(새 기기 기본) 또는 **모든 악보**. 고르는 곳은 웹뿐 — TV 화면 · 세트리스트의 "보낼 TV" · 연결 확인 화면
- 기기가 `setlists` 면 `sync/scores` 의 `scores` · `ids` 와 `sync/setlists` 가 **고른 세트리스트(의 곡)로 좁혀진다**.
  곡목에 곡을 넣거나 곡목을 새로 고르면 옛 악보라도 다음 동기화에 온다. 빼거나 해제하면 `ids` 에서 빠진다
- **TV 코드 변경 필요 없음** — 지금처럼 `ids` 에 없는 악보를 정리하면 된다. `all` → `setlists` 로 바꾸면 한꺼번에 많이 빠져
  §3 끝의 안전장치(절반 넘게 사라지면 묻기)가 한 번 뜰 수 있다 — 의도한 동작 → **TV 에서 안전장치를 뺐다**(묻지 않고 정리, 설정은 남김)
- 이 변경 전에 연결된 기기(운영 "4K Google TV Stick")는 마이그레이션에서 `all` 로 두었다 — 받던 것이 그대로다
- (선택) TV 화면에 "받는 것: 세트리스트 N개 / 모든 악보"를 보이려면 `GET /api/v1/devices/me/` 의 `sync_mode` · `sync_setlists`

## 8. 서버 0.7.1 · 0.7.2 — 웹 화면만 (2026-09-27, 알림 · TV 영향 없음)

서버 devlog `063_웹_고르기화면_정리_및_버전표시.md`. API · 동기화 규칙 · 마이그레이션 변경 없음.
- **0.7.1**: 웹에서 TV 가 받을 것을 고르는 화면 정리 — 기기마다 카드 하나(정보 · 이름 바꾸기 · 받는 것 · 해제), 해제한 기기는 아래 따로.
  세트리스트 화면의 "보낼 TV" · "곡 넣기"와 TV 연결 확인 화면도 체크 목록으로. 흩어지던 체크박스 · 라디오(텍스트 칸 스타일을 받던 것) 고침
- **0.7.2**: 웹 상단 "ScoreMate" 옆에 서버 버전(`v0.7.2`) 표시 — 문제를 알릴 때 서버 버전을 화면에서 바로 확인할 수 있다
- TV 쪽 할 일 없음. §3 의 필드 · §7 의 동작은 그대로

## 변경 이력
- 2026-09-27: 처음 작성 (C2 구현 중 발견). 같은 날 서버 작업용으로 보강 — 이름 변경 경로가 API · 웹 둘인 것, 제안 코드 · 테스트 · 완료 기준, TV 가 의존하는 필드 표
- 2026-09-27: 서버 처리 결과(§6) — 0.6.2 · 0.6.3 운영 배포
- 2026-09-27: §3 표 · §7 — 서버 0.7.0 기기별 동기화 범위(세트리스트 / 모든 악보) 알림
- 2026-09-27: TV 가 `ids` 정리 안전장치를 뺐다 — 묻지 않고 정리, 파일별 설정은 남김 (§3 · §7)
- 2026-09-27: §8 — 서버 0.7.1 · 0.7.2 (웹 화면 정리 · 버전 표시, TV 영향 없음)
