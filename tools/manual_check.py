#!/usr/bin/env python3
"""P19 — 설명서 한 · 영 구조 검사 (CI). 실패하면 exit 1.

docs/User_Manual.md(한) 와 docs/User_Manual.en.md(영)의 제목 줄(#)이 같은 차례로 같은 수인지 —
깊이와 앞 번호("## 3." · "### 3.1")가 줄마다 맞아야 한다. 한국어를 고치고 영어를 빠뜨리면 여기서 걸린다.
"""
import re, sys

repo = sys.argv[1] if len(sys.argv) > 1 else '.'
HEADING = re.compile(r'^(#{1,6})\s+(\d+(?:\.\d+)*\.?)?')

def outline(path):
    out, fence = [], False
    for line in open(path, encoding='utf-8'):
        if line.startswith('```'):
            fence = not fence
        if fence:
            continue
        m = HEADING.match(line)
        if m:
            out.append((len(m.group(1)), (m.group(2) or '').rstrip('.'), line.strip()))
    return out

ko = outline(f'{repo}/docs/User_Manual.md')
en = outline(f'{repo}/docs/User_Manual.en.md')
bad = []
for i in range(max(len(ko), len(en))):
    k = ko[i] if i < len(ko) else None
    e = en[i] if i < len(en) else None
    if k is None or e is None or k[:2] != e[:2]:
        bad.append(f'  {i + 1}: 한 {k[2] if k else "(없음)"}  ↔  영 {e[2] if e else "(없음)"}')
if bad:
    print('설명서 한 · 영 제목이 어긋납니다:')
    print('\n'.join(bad))
    sys.exit(1)
print(f'manual check: OK ({len(ko)} headings)')
