# 121 — 에뮬레이터는 업데이트 자동 확인 안 함

2026-10-11 · [#120](20261011_120_update_device_info.md) 뒤처리 · 사용자: "응 진행해줘"

## 왜
#120 으로 서버가 업데이트 확인을 기기별로 기록하자, 운영 `/manage/app-installs/` 에 **CI 에뮬레이터 두 대**(`sdk_gphone64_x86_64` · `sdk_gphone_x86`, 계측 API 34 · 30)가 install 로 잡혔다. 계측 · release 스모크가 파일 목록을 띄우면 자동 확인(10분마다, 첫 번은 바로)이 운영 서버로 간다 — main 푸시마다 기록이 늘 수 있다.

## 바꾼 것
- `UpdateController.checkIfDue` — 에뮬레이터면 자동 확인을 하지 않는다. 설정 → 업데이트 확인(손으로)은 그대로
- `UpdateDevice.isEmulator(hardware, product, fingerprint)`: `ranchu` · `goldfish` 하드웨어, `sdk…` 제품, `generic…` 지문 (JVM 테스트)

## 서버
- 이미 잡힌 에뮬레이터 두 줄은 서버 세션이 사용자 확인 뒤 지운다
