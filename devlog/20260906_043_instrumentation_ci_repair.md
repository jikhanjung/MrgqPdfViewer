# 043 — 계측 CI 복구: 도입 이후 한 번도 초록불이 아니었던 워크플로

작성일: 2026-09-06 (작업은 2026-09-05 심야 ~ 09-06 새벽)
관련 커밋: `1fcce4c` → `ff556c3` → `ade1b1e` → `499bb48`
선행: [`c88491f`](../.github/workflows/instrumentation.yml) — 워크플로 도입 (2026-08-15)
관련 문서: [`docs/4_Build_CI_Release.md`](../docs/4_Build_CI_Release.md) §2 갭 1

---

## 0. 요약

2026-08-15 에 추가한 `Instrumentation` 워크플로(에뮬레이터 API 21/30/34 에서
계측 테스트 + release APK 스모크)는 **도입된 그날부터 3주 동안 한 번도 통과한 적이
없었다.** 세 매트릭스 전부 실패였고, 그 상태로 방치됐다.

네 번의 수정으로 전 매트릭스 초록불을 만들었다. 실패는 네 겹이었고, **앞의 셋은
전부 CI 스크립트 자체의 문제였다.** 즉 이 게이트는 지금까지 앱을 한 줄도 검증하지
못하고 있었다.

| # | 증상 | 원인 | 커밋 |
|---|---|---|---|
| 1 | 세 매트릭스 모두 `Build APKs` 에서 즉사 | release 서명 폴백용 debug keystore 가 러너에 없음 | `1fcce4c` |
| 2 | `set: Illegal option -o pipefail` | 러너가 script 를 sh(dash) 로 실행 | `ff556c3` |
| 3 | `Syntax error: Unterminated quoted string` | 러너가 script 를 **한 줄씩 별도 셸로** 실행 | `ade1b1e` |
| 4 | API 34 만 "프로세스 없음", 크래시 로그도 없음 | 8초 시점 **단일 스냅샷** 판정 | `499bb48` |

---

## 1. 왜 3주 동안 몰랐나

빨간불이 난 건 워크플로를 도입한 바로 그 커밋이었다. 그 커밋의 `Android Build` 는
초록불이었기 때문에 "빌드는 통과했다"는 신호만 눈에 남았고, 같은 푸시의 두 번째
워크플로가 죽은 것은 지나갔다. 그 뒤로 3주간 커밋이 없었으므로 재시도도 없었다.

부수 교훈: **로그는 21일 뒤 만료된다.** 원인 조사를 시작한 시점에 원본 실패 로그는
이미 사라져 있었고, 진단은 워크플로 정의를 읽고 재현하는 것으로 시작해야 했다.

---

## 2. 실패 1 — 서명 키 (러너에 debug keystore 가 없다)

`app/build.gradle.kts` 의 release 서명은 `signing.properties` 가 없으면
`~/.android/debug.keystore` 로 폴백한다. 그런데 그 파일은 **debug 서명 설정에서만**
AGP 가 자동 생성한다. 러너에는 존재하지 않는다.

`build.yml`(재사용 빌드 정의)에는 이를 미리 만드는 `Ensure debug keystore` 단계가
있었지만, `instrumentation.yml` 은 `build.yml` 을 호출하지 않고 자체적으로
`assembleRelease` 를 돌리면서 그 단계를 빠뜨렸다. 세 매트릭스가 모두 1분 20초 안팎에
같은 자리에서 죽은 것이 이 설명과 맞았다.

> **일반화**: 재사용 워크플로를 만들어 놓고 그 옆에 "비슷한 일을 하는 두 번째 경로"를
> 두면, 재사용의 이점이 정확히 그만큼 새어 나간다. `ci.md` §6 이 경고하는 드리프트가
> 정의 복제가 아니라 **전제 조건 복제 누락**의 형태로 나타난 사례다.

## 3. 실패 2·3 — 러너의 `script:` 실행 모델

`reactivecircus/android-emulator-runner` 의 `script:` 는 우리가 쓰던 통념과 다르게
동작한다.

1. **bash 가 아니라 sh 로 돈다.** 우분투에서 `/bin/sh` 는 dash 이므로
   `set -o pipefail` 이 없다. 스크립트 첫 줄에서 exit 2.
2. **줄 단위로, 각각 별도의 셸에서 돈다.** 로그에 그대로 찍힌다:

   ```
   [command]/usr/bin/sh -c set -eu
   [command]/usr/bin/sh -c echo "── 계측 테스트 ──"
   [command]/usr/bin/sh -c ./gradlew connectedDebugAndroidTest
   ```

   `set -eu` 는 자기 줄에서 끝나므로 아무 효과가 없고, `if ... fi` 나 여러 줄 변수
   대입은 첫 줄에서 잘린다. 실제 에러가 `Unterminated quoted string` 이었던 이유다.

**해법**: 로직을 `.github/scripts/release_smoke.sh` 로 빼고 워크플로에서는 한 줄로
부른다. 파일 안에서는 bash 가 보장되므로 `set -euo pipefail` 도 정상적으로 쓸 수 있다.

```yaml
script: |
  ./gradlew connectedDebugAndroidTest
  bash .github/scripts/release_smoke.sh ${{ matrix.api-level }}
```

## 4. 실패 4 — API 34 의 유령 사망

여기서 처음으로 앱까지 도달했다. API 21 과 30 은 계측 테스트와 스모크를 모두
통과했고, **API 34 만** 실패했다.

```
Starting: Intent { cmp=com.mrgq.pdfviewer/.SplashActivity }
Status: ok
TotalTime: 2097          ← 콜드 스타트 성공
(8초 뒤)  프로세스 목록에 없음, 크래시 버퍼는 비어 있음
```

크래시 없이 사라진 것이라 앱 문제인지 측정 문제인지 구분할 수 없었다. **판정 방식이
증거를 남기지 않는 구조**였다는 것이 진짜 문제다. `sleep 8` 뒤 단 한 번 `ps` 를 보고
"없으면 사망"으로 단정했다.

판정을 다음과 같이 바꿨다.

- 2초 간격으로 30초간 폴링한다. **"한 번도 못 봤다"** 와 **"봤다가 사라졌다"** 를
  구분해서 보고한다. 앞은 기동 실패, 뒤는 사망이다.
- 실패 시 크래시 버퍼만이 아니라 메인 로그(앱 · AndroidRuntime · ActivityManager),
  프로세스 목록, 최상위 액티비티까지 남긴다.

이 상태로 재실행하니 **API 34 도 30초 내내 프로세스가 살아 있었다.** 즉 앱은
멀쩡했고, 8초 단일 스냅샷이 오판한 것이었다. 안정성 확인을 위해 `workflow_dispatch`
로 한 번 더 돌려 재현성을 확인했다.

부수적으로 API 21/26+ 의 셸 도구 차이도 걷어냈다. 원본 스크립트는 `pidof` 를 썼는데
**그 명령은 API 21 의 toolbox 에 없다**(toybox = API 23+). 반대로 `ps -A` 는 toolbox 에
없다. 이 워크플로의 존재 이유가 minSdk 21 검증인데 **검증 스크립트 자체가 API 21 에서
못 도는 도구를 쓰고 있었다.** 둘 다 시도해 합친 결과에서 찾도록 바꿨다.

---

## 5. 결과

| API | 계측 테스트 (Room 저장 왕복) | release APK 스모크 |
|---|---|---|
| 21 (minSdk) | ✅ | ✅ |
| 30 (targetSdk) | ✅ | ✅ |
| 34 (실사용 기기) | ✅ | ✅ |

처음으로 확인된 사실 두 가지:

1. **Room 저장 왕복이 minSdk 하한에서 실제로 돈다.** 선언만 해두고 한 번도 검증한 적
   없던 구간이다 (#040 계열 작업과 무관하게, `docs/4_Build_CI_Release.md` 가 지적한
   "minSdk 21 을 선언만 하고 검증한 적 없다"에 해당).
2. **minify 된 release APK 가 실제로 기동한다.** 이 앱은 `isMinifyEnabled = true` 인데
   릴리스 APK 가 지금껏 한 번도 실행된 적이 없었다. Android Studio 는 debug 를 설치하고,
   v0.1.13 은 빌드·서명·배포만 됐다. R8 이 enum 상수명을 난독화하면 Room 에 저장된
   `DisplayMode` 를 못 읽는데, 그 표면이 이제 매 푸시마다 확인된다.

---

## 6. 남은 것

- **API 34 유령 사망의 진짜 원인은 미규명이다.** 폴링으로 재현되지 않으므로 측정
  오판으로 판단했지만, 8초 시점에 프로세스가 실제로 잠깐 없었을 가능성을 완전히
  배제하진 못했다. 다시 나타나면 §4 에서 추가한 진단 로그가 답을 줄 것이다.
- **계측 테스트는 debug 빌드로 돈다.** 즉 위 2번의 R8 난독화 회귀는 "앱이 뜬다"까지만
  검증하고, 실제로 저장된 `DisplayMode` 를 minify 된 빌드에서 읽어 보지는 않는다.
  `connectedReleaseAndroidTest` 로 한 단계 더 갈 수 있다.
- 이 워크플로는 main 푸시와 수동 실행에서만 돈다. PR 마다 돌리지 않는 결정은 유지한다
  (회당 5~6분, 매트릭스 3개).
