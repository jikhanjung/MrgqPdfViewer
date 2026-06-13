#!/usr/bin/env python3
"""Build per-player part books from the full score by cropping each player's staff
band out of every system and re-laying them out as single-staff lines on A4 pages."""
import json, subprocess, os, math

DPI=200; F=DPI/72.0
PAGE=[ "/tmp/part-%02d.png"%p for p in range(1,14) ]
LAYOUT=json.load(open("/tmp/score_layout.json"))
KO="/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
PLAYERS=["진호","예진","하진","예완","은석"]   # top->bottom == staff_bands index
ROMAN=["Jinho","Yejin","Hajin","Yewan","Eunseok"]
OUT="/mnt/d/projects/MrgqPdfViewer/data/parts"
os.makedirs(OUT, exist_ok=True)
TMP="/tmp/parts"; os.makedirs(TMP, exist_ok=True)

# crop geometry in points
X0, X1, PAD = 36.0, 560.0, 23.0
CX, CW = round(X0*F), round((X1-X0)*F)

# A4 @ DPI
PW, PH = round(595.32*F), round(841.92*F)   # 1654 x 2339
MARGIN_L, MARGIN_T, MARGIN_B = 120, 110, 70
STRIP_W = CW
STRIP_X = MARGIN_L + 60      # leave a left gutter for measure numbers
GAP = 40
HEADER1 = 230                # title band height on page 1

# build ordered system list with (page, sys_index, start_measure)
systems=[]; m=1
for pg in range(1,14):
    for si, s in enumerate(LAYOUT[str(pg)]):
        systems.append((pg, si, m, s))
        m += s["n_measures"]
TOTAL_M = m-1

def run(args):
    r=subprocess.run(args, capture_output=True, text=True)
    if r.returncode!=0: raise SystemExit("convert failed: "+r.stderr[:400])

def crop_strip(pg, band, dst):
    top,bot = band
    cy = round((top-PAD)*F); ch = round((bot-top+2*PAD)*F)
    run(["convert", PAGE[pg-1], "-crop", f"{CW}x{ch}+{CX}+{cy}", "+repage", dst])
    return ch

for pi, name in enumerate(PLAYERS):
    # 1) crop every system's strip for this player
    strips=[]  # (path, height, start_measure)
    for (pg, si, sm, s) in systems:
        band = s["staff_bands"][pi]
        dst = f"{TMP}/{ROMAN[pi]}_{pg}_{si}.png"
        ch = crop_strip(pg, band, dst)
        strips.append((dst, ch, sm))
    # 2) paginate
    pages=[]; idx=0; pageno=0
    while idx < len(strips):
        pageno+=1
        y = MARGIN_T + (HEADER1 if pageno==1 else 0)
        comp=[]; annot=[]
        while idx < len(strips):
            path,ch,sm = strips[idx]
            if y+ch > PH-MARGIN_B: break
            comp += ["(", path, ")", "-geometry", f"+{STRIP_X}+{y}", "-composite"]
            annot += ["-annotate", f"+{MARGIN_L-70}+{y+ch//2+10}", str(sm)]
            y += ch + GAP
            idx+=1
        ppath=f"{TMP}/{ROMAN[pi]}_page{pageno}.png"
        cmd=["convert","-size",f"{PW}x{PH}","xc:white"]+comp
        cmd+=["-font",KO,"-pointsize","30","-fill","#0050b0"]+annot   # measure numbers
        if pageno==1:
            cmd+=["-font",KO,"-fill","black",
                  "-pointsize","58","-gravity","North","-annotate","+0+70","Die Moldau (Vltava)",
                  "-pointsize","30","-gravity","NorthEast","-annotate",f"+90+95","Smetana",
                  "-pointsize","44","-gravity","NorthWest","-annotate",f"+120+150",f"{name}  ({ROMAN[pi]})"]
        cmd+=["-gravity","SouthEast","-font",KO,"-pointsize","22","-fill","#888888",
              "-annotate","+40+30",f"{pageno}"]
        cmd.append(ppath)
        run(cmd)
        pages.append(ppath)
    # 3) combine into a PDF
    pdf=f"{OUT}/Moldau_{pi+1}_{ROMAN[pi]}.pdf"
    run(["convert"]+pages+["-density",str(DPI),pdf])
    print(f"{name} ({ROMAN[pi]}): {len(strips)} systems -> {len(pages)} pages -> {pdf}")

print(f"\nTotal measures per part: {TOTAL_M}, systems: {len(systems)}")
