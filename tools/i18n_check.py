#!/usr/bin/env python3
"""P18 i18n check: values/ vs values-en/ keys + placeholder counts; residual Korean literals in given Kotlin files.
usage: i18n_check.py <repo> [kotlin files...]"""
import re, sys, os, glob
repo = sys.argv[1]
res = os.path.join(repo, 'app/src/main/res')
def load(d):
    out = {}
    for f in glob.glob(os.path.join(res, d, 'strings*.xml')):
        s = open(f, encoding='utf-8').read()
        for m in re.finditer(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', s, re.S):
            if 'translatable="false"' in m.group(2): continue
            out[m.group(1)] = (m.group(3), os.path.basename(f))
        for m in re.finditer(r'<plurals name="([^"]+)"', s):
            out[m.group(1)] = ('<plurals>', os.path.basename(f))
    return out
ko, en = load('values'), load('values-en')
ph = re.compile(r'%(\d+\$)?[sdf]')
bad = 0
for k, (v, f) in ko.items():
    if k not in en: print(f'MISSING en: {k} ({f})'); bad += 1; continue
    a, b = sorted(ph.findall(v)), sorted(ph.findall(en[k][0]))
    if len(ph.findall(v)) != len(ph.findall(en[k][0])): print(f'PLACEHOLDER mismatch: {k}: ko={v!r} en={en[k][0]!r}'); bad += 1
    if len(ph.findall(v)) > 1 and any(not p for p in ph.findall(v)): print(f'NON-POSITIONAL multi-arg: {k}'); bad += 1
for k in en:
    if k not in ko: print(f'EXTRA en (no ko): {k} ({en[k][1]})'); bad += 1
# unescaped apostrophes in values-en
for f in glob.glob(os.path.join(res, 'values*', 'strings*.xml')):
    for i, line in enumerate(open(f, encoding='utf-8'), 1):
        m = re.search(r'<string[^>]*>(.*)</string>', line)
        if m and re.search(r"(?<!\\)'", m.group(1)): print(f"UNESCAPED apostrophe: {f}:{i}"); bad += 1
print(f'keys ko={len(ko)} en={len(en)} problems={bad}')
lit = re.compile(r'"[^"\n]*[가-힣][^"\n]*"')
for kt in sys.argv[2:]:
    n = 0
    for i, line in enumerate(open(kt, encoding='utf-8'), 1):
        t = line.strip()
        if t.startswith(('//', '*', '/*')) or re.search(r'Log\.[dewiv]\(', t): continue
        if lit.search(line): n += 1; print(f'  KO literal {kt}:{i}: {t[:110]}')
    print(f'{kt}: {n} Korean literal lines left')
