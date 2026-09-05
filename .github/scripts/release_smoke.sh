#!/usr/bin/env bash
# minify 된 release APK 가 실제 기기에서 뜨는지 확인한다.
#
# 왜 별도 파일인가: android-emulator-runner 의 `script:` 는 **한 줄씩 별도의 sh 로**
# 실행된다. if/fi 같은 여러 줄 구문도, 앞 줄의 `set -eu` 도 다음 줄로 이어지지 않는다
# (실제로 여러 줄 변수 대입이 "Unterminated quoted string" 으로 죽었다).
# 그래서 로직은 파일에 두고 워크플로에서는 한 줄로 부른다.
#
# debug 로는 절대 안 걸리는 표면을 본다: R8 이 enum 상수명을 난독화하면 Room 에 저장된
# DisplayMode 를 못 읽는다.
set -euo pipefail

API_LEVEL="${1:-?}"
PKG=com.mrgq.pdfviewer

# 프로세스 목록. API 21 의 toolbox 에는 pidof 가 없고 ps 에 -A 도 없다.
# API 26+ 의 toybox 는 그 반대라, 둘 다 시도한 결과를 합쳐서 본다.
ps_all() {
  adb shell ps -A 2>/dev/null || true
  adb shell ps 2>/dev/null || true
}

# 실패했을 때 원인을 알 수 있을 만큼 남긴다. 크래시 버퍼가 비어 있는 경우
# (프로세스가 크래시가 아닌 다른 이유로 사라진 경우)가 실제로 있었다.
dump_diagnostics() {
  echo "── logcat -b crash ──"
  adb logcat -d -b crash 2>/dev/null | tail -80 || echo "(크래시 버퍼 없음)"
  echo "── logcat (앱 · AndroidRuntime · ActivityManager) ──"
  adb logcat -d 2>/dev/null \
    | grep -iE "mrgq|AndroidRuntime|ActivityManager|ANR|Killing|DEBUG" \
    | tail -120 || echo "(해당 로그 없음)"
  echo "── 실행 중인 프로세스 ──"
  ps_all | tail -40
  echo "── 최상위 액티비티 ──"
  adb shell dumpsys activity activities 2>/dev/null \
    | grep -iE "mResumedActivity|topResumedActivity|mFocusedApp" | tail -10 || true
}

adb uninstall "$PKG" || true
adb install -r app/build/outputs/apk/release/*.apk
adb logcat -c
adb shell am start -W -n "$PKG/.SplashActivity"

# 스플래시가 2.5초, 그 뒤 MainActivity 진입. 살아 있는지 최대 30초까지 지켜본다.
# (한 번만 보고 판단하면 기동 지연과 사망을 구분하지 못한다.)
alive=0
for i in $(seq 1 15); do
  sleep 2
  if ps_all | grep -q "$PKG"; then
    alive=1
  elif [ "$alive" = 1 ]; then
    echo "::error::release APK 가 기동 후 사라졌습니다 (API $API_LEVEL, ${i}회차)"
    dump_diagnostics
    exit 1
  fi
done

if [ "$alive" = 0 ]; then
  echo "::error::release APK 프로세스를 한 번도 보지 못했습니다 (API $API_LEVEL)"
  dump_diagnostics
  exit 1
fi

# 살아 있어도 크래시 버퍼에 우리 패키지가 찍혔으면 실패로 본다
crash="$(adb logcat -d -b crash 2>/dev/null || true)"
if printf '%s' "$crash" | grep -q "$PKG"; then
  echo "::error::release APK 에서 크래시가 기록됐습니다 (API $API_LEVEL)"
  printf '%s\n' "$crash" | tail -80
  exit 1
fi

echo "release APK 정상 기동 ✓ (API $API_LEVEL)"
