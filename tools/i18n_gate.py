#!/usr/bin/env python3
"""P18 6단계 — 화면 글 게이트 (CI). 실패하면 exit 1.

1. values/ 의 모든 줄이 values-en/ 에도 있고 자리표(%1$s …) 수가 같다 (tools/i18n_check.py 와 같은 검사)
2. app/src/main/java 의 Kotlin 에 **한글이 든 문자열 리터럴**이 없다 — 화면 글은 리소스로.
   빼는 것: 주석, 로그(Log.*), require/check/error · throw(화면에 안 나가는 예외 — 화면에 나가는 예외는 UiText 키), voice/(한국어 인식 문법 — 화면 글은 strings_voice),
   server/WebText.kt(브라우저 언어용 사전), 줄 끝에 `// i18n-ignore: 이유` 를 단 줄(합주 메시지 · 파일 경로처럼 바꾸면 안 되는 글)
3. 레이아웃 XML 의 android:text · hint · contentDescription 에 한글 리터럴이 없다(tools:text 는 됨)
"""
import glob, os, re, subprocess, sys

repo = sys.argv[1] if len(sys.argv) > 1 else '.'
bad = []

check = subprocess.run([sys.executable, os.path.join(repo, 'tools/i18n_check.py'), repo], capture_output=True, text=True)
last = check.stdout.strip().splitlines()[-1] if check.stdout.strip() else ''
if not last.endswith('problems=0'):
    bad.append('리소스 대조 실패:\n' + check.stdout)

HANGUL_LIT = re.compile(r'"(?:[^"\\\n]|\\.)*[가-힣](?:[^"\\\n]|\\.)*"')
SKIP_DIRS = ('/voice/',)
SKIP_FILES = ('server/WebText.kt',)
SKIP_LINE = re.compile(r'^\s*(//|\*|/\*)|Log\.[dewiv]\(|\brequire\(|\bcheck\(|\berror\(|\bthrow\s|i18n-ignore')

def strip_trailing_comment(line):
    # 문자열 밖의 첫 // 부터 지운다
    in_str = False
    i = 0
    while i < len(line):
        c = line[i]
        if in_str:
            if c == '\\': i += 2; continue
            if c == '"': in_str = False
        elif c == '"': in_str = True
        elif line.startswith('//', i): return line[:i]
        i += 1
    return line

for path in sorted(glob.glob(os.path.join(repo, 'app/src/main/java/**/*.kt'), recursive=True)):
    rel = os.path.relpath(path, repo).replace(os.sep, '/')
    if any(d in '/' + rel for d in SKIP_DIRS) or rel.endswith(SKIP_FILES):
        continue
    in_log = 0
    for n, line in enumerate(open(path, encoding='utf-8'), 1):
        # 여러 줄 로그: Log.x( 로 시작해 괄호가 닫힐 때까지
        if in_log > 0:
            in_log += line.count('(') - line.count(')')
            continue
        if re.search(r'Log\.[dewiv]\(', line):
            in_log = line.count('(') - line.count(')')
            if in_log < 0: in_log = 0
            continue
        if SKIP_LINE.search(line):
            continue
        if HANGUL_LIT.search(strip_trailing_comment(line)):
            bad.append(f'{rel}:{n}: 한글 리터럴 — 리소스로 옮기거나 `// i18n-ignore: 이유`: {line.strip()[:120]}')

ATTR = re.compile(r'android:(text|hint|contentDescription)="([^"]*)"')
for path in sorted(glob.glob(os.path.join(repo, 'app/src/main/res/layout*/*.xml'))):
    rel = os.path.relpath(path, repo)
    for n, line in enumerate(open(path, encoding='utf-8'), 1):
        for m in ATTR.finditer(line):
            if not m.group(2).startswith(('@', '?')) and re.search('[가-힣]', m.group(2)):
                bad.append(f'{rel}:{n}: 레이아웃 한글 글 — @string 으로: {m.group(0)}')

for b in bad:
    print(b)
print(f'i18n gate: {"FAIL" if bad else "OK"} ({len(bad)})')
sys.exit(1 if bad else 0)
