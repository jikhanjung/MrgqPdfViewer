# 합주 프로토콜 (지휘자 ↔ 연주자)

> 기준: 앱 v0.9.0-beta.1 · **합주 버전 0.4.0** (2026-10-14). 와이어 포맷의 단일 출처는 코드
> [`CollaborationProtocol.kt`](../../app/src/main/java/com/mrgq/pdfviewer/CollaborationProtocol.kt)이고,
> 라운드트립 테스트는 `CollaborationProtocolTest` · `EnsembleSyncTest`가 맡는다. 이 문서는 그것을 사람이 읽게 옮긴 것이다 —
> 다르면 코드가 맞다. 다른 앱(ScoreMate 앱 등)이 같은 합주에 들어오려면 이 문서대로 말하면 된다.
>
> 두 대로 펼쳐 보기(짝)는 다른 연결이다 — [`Pair_Protocol.md`](Pair_Protocol.md).

## 1. 전송

| 무엇 | 어떻게 |
|---|---|
| 찾기 | UDP 브로드캐스트, 포트 **9091** — 지휘자가 2초마다 알리고, 연주자가 최대 12초 듣는다 (§5) |
| 메시지 | **WebSocket(평문 `ws://`), 포트 9090** — 지휘자가 서버, 연주자가 클라이언트(`ws://<지휘자 IP>:9090`). WSS 는 v0.1.8 에 시도했다 롤백(인증서 호환) |
| 파일 받기 | HTTP, 포트 **8090** — 지휘자의 파일 서버 `GET /download/<URL 인코딩한 파일 이름>` (연주자에게 악보가 없을 때) |

- 메시지는 텍스트 프레임 하나에 JSON 객체 하나. 종류는 **`action`** 필드.
- **모르는 `action` · 모르는 필드는 무시한다.** 필드가 없거나 타입이 틀리면 안전한 기본값으로 — 한 필드가 이상하다고 메시지 전체를 버리면 합주 중에 쪽이 안 넘어간다.
- 시각은 두 가지:
  - `timestamp` · `turn_at` — 벽시계 밀리초(`System.currentTimeMillis()`)
  - 합주 메트로놈(`t0` · `server_ns` · `anchor_ns`) — 각 기기의 **단조 시계 나노초**(`System.nanoTime()`). 기기끼리 직접 비교하지 않고 §4.1 의 시계 차이로 옮긴다
- 쪽 번호는 **1부터, 원본(총보) PDF 기준**. 파트 보기를 하는 연주자는 받은 원본 쪽을 자기 파트 PDF 쪽으로 옮긴다(P07 4단계). 지휘자는 늘 총보.
- 마디 번호는 PDF 악보 분석의 **마디 번호** — 같은 PDF 면 기기마다 같다.

## 2. 연결 · 버전

### 2.1 `client_connect` (연주자 → 지휘자, 연결 직후)

```json
{
  "action": "client_connect",
  "device_id": "<연주자 기기 ID>",
  "device_name": "<기기 이름>",
  "app_version": "0.9.0-beta.1",
  "ensemble_version": "0.4.0",
  "timestamp": 1760400000000
}
```

### 2.2 `connect_response` (지휘자 → 그 연주자)

```json
{
  "action": "connect_response",
  "status": "success",
  "server_version": "v0.1.5",
  "app_version": "0.9.0-beta.1",
  "ensemble_version": "0.4.0",
  "timestamp": 1760400000000
}
```

- `server_version` 은 옛 연주자 호환용 고정값. v0.2.5 이하 기기는 `app_version` 자리에 늘 `"v0.1.5"` 를 보냈다.
- 연결되면 지휘자는 그 연주자에게 지금 상태를 보낸다: 도는 합주 메트로놈이 있으면 `metronome_run`, 그리고 지금 악보 `file_change`.

### 2.3 합주 버전 (`ensemble_version`, #086)

- **합주 메시지 · 명령이 마지막으로 바뀐 판의 앱 버전**. 지금 `"0.4.0"`(`ensemble/EnsembleVersion.CURRENT`).
- 지휘자 · 연주자는 서로의 합주 버전이 **다를 때만** 업데이트할 기기를 가리키는 안내를 띄운다. 앱 버전만 다르면 띄우지 않는다.
- 필드가 없는 옛 기기(v0.7.1-beta.1 까지)는 `app_version` 이 0.4.0 이상이면 0.4.0 으로 본다.
- 합주 메시지를 바꾸는 판에서 그 판의 앱 버전으로 올린다. **옛 기기가 무시해도 박 · 쪽이 어긋나지 않는 선택 필드 추가**(예: `page_change.roll_start`)는 올리지 않는다.

### 2.4 `heartbeat` · `heartbeat_response`

연주자가 30초마다 `{"action":"heartbeat","timestamp":…,"client_id":…}`, 지휘자가 `{"action":"heartbeat_response","timestamp":…}`.

### 2.5 `request_sync` (연주자 → 지휘자)

`{"action":"request_sync","client_id":…,"device_name":…}` — 지휘자는 지금 받기만 하고 답하지 않는다(상태는 연결 때 보낸다).

## 3. 쪽 · 악보

### 3.1 `page_change` (지휘자 → 모두)

```json
{
  "action": "page_change",
  "page": 3,
  "file": "Brahms Sextet.pdf",
  "timestamp": 1760400000000,
  "turn_at": 1760400000400,
  "roll": true,
  "roll_start": true
}
```

| 필드 | |
|---|---|
| `page` | 지휘자가 보는 쪽(1부터, 원본). 두 쪽 지휘자(TV · 짝)면 보통 펼침의 **왼쪽 쪽** |
| `file` | 지금 악보 이름 — 연주자의 지금 악보와 다르면 무시 |
| `turn_at` | (선택) 이 벽시계 시각에 함께 넘김 — Phase 0 동기 넘김, 기본 꺼짐. **없거나 이미 지났으면 바로** |
| `roll` | (선택) 🎤 듣기 · 메트로놈 악보 연동이 **연주 중 들어선 쪽**(P10 §3.2 · #135). 두 쪽 연주자는 화면에 없는 쪽이면 바로 그 짝, 있으면 5초 뒤 다 친 쪽 자리만 바꾼다(1 \| 2 → 3 \| 2 → 3 \| 4, `follow/RollingTurns`). 한 쪽 연주자 · 파트 보기는 보통 넘김처럼 |
| `roll_start` | (선택, `roll` 과 함께) 이 쪽에서 **시작**했다(#088) — 두 쪽 연주자는 기다리지 않고 시작 펼침(오른쪽 자리에서 시작 → 왼쪽 자리를 다음 쪽으로: 4쪽 시작 → 5 \| 4) |

연주자가 무시하는 때:
- 합주 메트로놈을 **따라 도는 중** — 마디로 스스로 넘긴다(지휘자와 표시 모드가 달라도 맞게). 지휘자가 **일시정지 · 시작 마디를 고르는 동안**에는 따른다(v0.9.0-beta.1 부터, ScoreMate 앱과 같게)
- 두 대로 펼쳐 보기의 **짝 기기** — 주 기기가 받아 펼침으로 나눈다

### 3.2 `file_change` (지휘자 → 모두)

```json
{
  "action": "file_change",
  "file": "Brahms Sextet.pdf",
  "page": 1,
  "timestamp": 1760400000000,
  "file_server_url": "http://192.168.0.10:8090",
  "sha256": "9f2c…(소문자 hex 64자)"
}
```

- 연주자는 **`sha256`(파일 내용)으로 먼저** 찾는다(#063) — ScoreMate 에 연결된 기기는 내 ScoreMate 악보 → 캐시, 연결하지 않은 기기는 이름 → `PDFs/`. 없으면 받기를 묻고 `file_server_url`(없으면 지휘자 IP:8090)의 `/download/<이름>` 으로 받는다. 받은 파일 이름은 경로로 그대로 쓰지 않는다(`EnsembleFiles.safeName`).
- `sha256` 이 없으면(v0.2.6 이하 지휘자) 이름으로.
- 나눠 주는 것은 **원본 PDF 뿐**이다 — 파트 PDF · MusicXML · 서버 분석 파일은 기기 밖으로 보내지 않는다(저작권 원칙, P07 §0).

### 3.3 `back_to_list` · `ensemble_end` (지휘자 → 모두)

- `{"action":"back_to_list","timestamp":…}` — 지휘자가 악보 화면을 떠났다. 연주자도 목록으로.
- `{"action":"ensemble_end","timestamp":…}` — 지휘자가 합주를 껐다. 연주자도 합주 모드를 끝낸다(옛 앱은 무시).

### 3.4 `follow_position` (지휘자 → 모두, P10)

```json
{ "action": "follow_position", "file": "Brahms Sextet.pdf", "measure": 57 }
```

지휘자 🎤 듣기의 지금 마디 — **시스템이 바뀔 때만**. 연주자는 그 시스템을 연하게 표시한다. `measure` 0 = 듣기 끝(표시 지움).

## 4. 합주 메트로놈 (#055)

연주자는 지휘자와 같은 박을 **자기 시계로** 친다 — 시계 차이를 재고(§4.1), 지휘자가 방송하는 시간표(§4.2)를 옮겨 쓴다. 실측 마디 전환 차이 중앙값 2.5ms.

### 4.1 시계 차이 — `clock_ping` · `clock_pong`

```json
{ "action": "clock_ping", "t0": 123456789000 }
{ "action": "clock_pong", "t0": 123456789000, "server_ns": 987654321000 }
```

- 연주자가 연결 뒤 100ms 간격으로 8번, 그 뒤 2초마다 `clock_ping`(`t0` = 보낸 순간 연주자 `nanoTime`). 지휘자는 받은 순간 자기 `nanoTime` 을 `server_ns` 로 답한다.
- 연주자는 받은 순간 `t3` 로 표본 하나: `rtt = t3 − t0`, `offset = server_ns − (t0 + rtt/2)`. **최근 16개 중 RTT 가 가장 작은 표본**을 믿고, 바뀔 때는 한 번에 1ms 씩만(20ms 넘게 다르면 바로) 옮긴다(`ensemble/ClockSync`).
- 지휘자 시각 = 연주자 시각 + offset.

### 4.2 `metronome_run` (지휘자 → 모두)

```json
{
  "action": "metronome_run",
  "run_id": "a1b2c3",
  "file": "Brahms Sextet.pdf",
  "state": "playing",
  "anchor_beat": 0,
  "anchor_ns": 987654321000,
  "bar_beat": 0,
  "bpm": 96,
  "num": 6,
  "den": 8,
  "dotted": true,
  "start_measure": 7,
  "focus_measure": 7,
  "tempo_sections": [ { "m": 33, "rel": "note", "bpm": 120, "dotted": false } ],
  "count_in_bars": 2
}
```

| 필드 | |
|---|---|
| `run_id` | 한 번의 연주(시작 ~ 정지). 연주자가 스스로 빠진 연주를 기억하는 열쇠 |
| `state` | `playing` · `paused` · `selecting`(지휘자가 시작 마디를 고르는 중) · `stopped` |
| `anchor_beat` · `anchor_ns` | 박 `anchor_beat` 이 **지휘자 시계** `anchor_ns`(ns)에 울린다. 그 뒤 박 간격은 `bpm` 으로 일정(템포가 바뀌면 바뀌는 박을 새 기준으로 다시 보냄) |
| `bar_beat` | 마디 첫 박인 박 번호 하나(악보 연동이 아닐 때 강박 세기). 없으면 `anchor_beat` |
| `bpm` · `num` · `den` · `dotted` | 빠르기(박 = 분모 음표, `dotted` 면 점음표 박) · 박자. 없으면 4/4 · false. `bpm` 이 없거나 0 이하면 메시지를 버린다 |
| `start_measure` | 악보 연동이면 시작 마디 |
| `focus_measure` | `paused` 면 멈춘 마디, `selecting` 이면 지휘자의 커서 — 연주자 화면에 표시 |
| `tempo_sections` | (선택, #057) 구간별 빠르기 — `m` 시작 마디, `rel` 이어받기(`note` 음표 길이 그대로 · `beat` 박 길이 그대로 · `set` 직접), `bpm`, `dotted`. 박 시각표 자체는 보내지 않는다 — 연주자가 이 설정과 자기 악보로 똑같이 만든다 |
| `count_in_bars` | (선택, #060) 예비박 마디 수 1 · 2. 없으면 1 |

- 언제: 상태가 바뀔 때, 연주자가 연결될 때, 그리고 연주가 있는 동안 **5초마다** 다시(놓친 연주자 합류용).
- 연주자: `playing` 이면 시간표를 자기 시계로 옮겨 `AudioTimestamp` 로 박을 프레임에 맞춰 시작(늦게 들어와도 지금 박에서 합류). `paused` · `selecting` 이면 메트로놈을 멈추고 `focus_measure` 를 표시 — **마디가 바뀔 때만 그 쪽으로 옮긴다**(같은 마디의 5초 방송이 지휘자가 넘긴 쪽을 되돌리지 않게, v0.9.0-beta.1). `stopped` 면 정지.
- 소리는 연주자가 고른다(기본 끔) — 박 표시 · 지금 마디 · 넘김만 따라갈 수도 있다.

## 5. 찾기 (UDP 9091)

지휘자가 2초마다 브로드캐스트 주소들(인터페이스의 broadcast · 지금 IP 의 /24 `.255` · `255.255.255.255`)과 `.1` 로 보낸다:

```json
{
  "type": "conductor_announcement",
  "conductor_name": "<지휘자 기기 이름>",
  "websocket_port": 9090,
  "timestamp": 1760400000000,
  "ip_address": "192.168.0.10"
}
```

연주자는 9091 에서 최대 12초 듣고, 보낸 곳 주소(없으면 `ip_address`)와 `websocket_port` 로 붙는다.

## 6. 두 대로 펼쳐 보기와 합주 (P22)

짝(태블릿 · 휴대폰 두 대)은 합주에서 **TV 두 쪽 기기 한 대**다 — 합주에는 **주 기기만** 들어간다. 합주 메시지는 바뀌지 않았다(합주 버전 그대로).
- 주 기기가 연주자: `page_change` 는 두 기기를 합친 펼침으로, `roll` · `roll_start` 는 TV 두 쪽 연주자처럼.
- 주 기기가 지휘자: 보통의 짝이면 펼침의 왼쪽 쪽을 `page_change` 로, 차례 넘김 펼침(3 | 2)은 `roll` 로만 알린다.
- 짝 기기는 합주 메시지를 따르지도 보내지도 않는다.

## 7. 바뀐 내력

| 앱 판 | 합주 버전 | 바뀐 것 |
|---|---|---|
| v0.1.x | — | `page_change` · `file_change` · `back_to_list` · 연결 · heartbeat |
| v0.2.x | — | 합주 메트로놈(`clock_ping/pong` · `metronome_run`), 구간별 빠르기, 예비박 두 마디, `file_change.sha256`, `app_version` |
| v0.4.0 | **0.4.0** | `page_change.roll` · `follow_position` · `ensemble_end` (P10) |
| v0.7.2 | 0.4.0 | `ensemble_version` 필드(#086) |
| v0.7.3 | 0.4.0 | `page_change.roll_start`(#088 — 선택 필드라 합주 버전 그대로) |
| v0.9.0-beta.1 | 0.4.0 | 연주자가 고르는 중 · 일시정지 중에도 `page_change` 를 따름, 같은 `focus_measure` 되풀이에 쪽을 옮기지 않음(메시지는 그대로) |
