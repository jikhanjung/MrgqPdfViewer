#!/usr/bin/env python3
"""Proof-of-concept note (pitch) recognition from the vector score.
Detect noteheads (small filled ellipses ~6x5pt), read their vertical position
against the known staff lines, and map to pitch via treble clef + G-major key (F#).
Emit an ImageMagick overlay of pitch labels on the Yejin staff, page-1 top system."""
import json, subprocess, score_final as S
H=S.H; F=200/72.0
LAY=json.load(open("/tmp/score_layout.json"))

PAGE=1; SYS=0; PLAYER=1   # top system, Yejin (staff_bands index 1)
band = LAY[str(PAGE)][SYS]["staff_bands"][PLAYER]      # top-down [top,bot]
top_td, bot_td = band
sp = (bot_td-top_td)/4.0          # staff-line spacing (top-down)
half = sp/2.0
F5_y = top_td                      # top line of treble staff = F5
F5_dn = 5*7+3                       # diatonic number of F5 (C=0..B=6); octave*7+idx

LET="CDEFGAB"
def pitch(y_td):
    steps = round((y_td - F5_y)/half)      # diatonic steps below F5 (down = +)
    dn = F5_dn - steps
    octv = dn//7; letter = LET[dn%7]
    name = letter + ("#" if letter=="F" else "") + str(octv)   # G major: F#
    return name, steps

# notehead band in bottom-up coords (parser space), with ledger padding
bu_lo = H - bot_td - 16
bu_hi = H - top_td + 16
rects = S.parse_page(S.PAGE_CONTENTS[PAGE])
heads=[]
for x0,y0,x1,y1,c in rects:
    if not c: continue
    w=x1-x0; h=y1-y0; yc=(y0+y1)/2; xc=(x0+x1)/2
    if 5.0<=w<=7.5 and 4.0<=h<=6.5 and bu_lo<=yc<=bu_hi:
        heads.append((xc, H-yc))   # store top-down y
heads.sort()
print(f"Yejin p1 top-system: {len(heads)} noteheads detected")
# only within the system's playable x-range
x_left = LAY[str(PAGE)][SYS]["x_left"]; x_right=LAY[str(PAGE)][SYS]["x_right"]
notes=[(x,y,*pitch(y)) for x,y in heads if x_left-4<=x<=x_right+4]
# print as a pitch sequence
seq=[n[2] for n in notes]
print("pitch sequence (left->right):")
print("  "+" ".join(seq))

# build overlay on the 200dpi page render, cropped to this staff
cy=round((top_td-20)*F); ch=round((bot_td-top_td+40)*F)
cx=round(36*F); cw=round((560-36)*F)
subprocess.run(["convert","/tmp/part-01.png","-crop",f"{cw}x{ch}+{cx}+{cy}","+repage","/tmp/poc_strip.png"])
# annotate: label above each notehead (x relative to crop)
ann=[]
for x,y,name,steps in notes:
    px=(x*F)-cx; py=(y*F)-cy
    ann+=["-fill","#c00000","-annotate",f"+{px-12:.0f}+{py-18:.0f}",name]
cmd=["convert","/tmp/poc_strip.png","-pointsize","17","-font",
     "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]+ann+["/tmp/poc_pitch.png"]
subprocess.run(cmd)
print("overlay -> /tmp/poc_pitch.png")
