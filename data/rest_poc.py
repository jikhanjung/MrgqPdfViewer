#!/usr/bin/env python3
"""Rest recognition POC: classify rest glyphs on the Jinho staff (p1 top system)
by glyph shape (bbox + curved?) and vertical placement against the staff lines."""
import json, subprocess, score_final as S
H=S.H; F=200/72.0
LAY=json.load(open("/tmp/score_layout.json"))
PAGE,SYS,PLAYER=1,0,0   # Jinho = staff_bands[0]
band=LAY[str(PAGE)][SYS]["staff_bands"][PLAYER]
top_td,bot_td=band; sp=(bot_td-top_td)/4.0
line4_td=top_td+sp          # 4th line from bottom (2nd from top): whole rest hangs below
mid_td=top_td+2*sp          # middle line
x_left=LAY[str(PAGE)][SYS]["x_left"]; x_right=LAY[str(PAGE)][SYS]["x_right"]

rects=S.parse_page(S.PAGE_CONTENTS[PAGE])
glyphs=[]; heads=[]; stems=[]; dots=[]
for x0,y0,x1,y1,c in rects:
    yc=(y0+y1)/2; xc=(x0+x1)/2; w=x1-x0; h=y1-y0
    ytd=H-yc
    if not (top_td-16<=ytd<=bot_td+16 and x_left-2<=xc<=x_right+2): continue
    if h<1.5 and w>80: continue                      # staff line
    if w<2 and h>6: stems.append((xc,ytd)); continue # bar/stem
    if 5<=w<=7.5 and 4<=h<=6.5 and c: heads.append((xc,ytd)); continue
    if w<2.6 and h<2.6: dots.append((xc,ytd)); continue
    if 40<w<55: continue                              # beam
    glyphs.append((xc,ytd,w,h,c))

def near_dot(xc,ytd):
    return any(abs(dx-xc)<7 and dx>xc-1 and abs(dy-ytd)<5 for dx,dy in dots)

rests=[]
for xc,ytd,w,h,c in glyphs:
    lab=None
    centered = abs(ytd-mid_td) < sp*1.3
    if (not c) and 4.5<=w<=8 and 1.6<=h<=3.2:
        # whole vs half by which line it touches
        lab = "𝄻" if abs(ytd-line4_td) < sp*0.7 else "𝄗"   # whole-measure rest vs half
        lab = "wholeR"
    elif c and 7.5<=h<=11 and 4<=w<=7 and centered:
        lab="8thR"
    elif c and 11.5<=h<=15 and 5<=w<=8 and centered:
        lab="16thR"
    elif c and 14<=h<=17.5 and w<=5.6 and centered:
        # quarter rest — but exclude if a stem/notehead shares this x (then it's a note)
        if not any(abs(sx-xc)<3 for sx,_ in stems) and not any(abs(hx-xc)<3 for hx,_ in heads):
            lab="quarterR"
    if lab:
        if near_dot(xc,ytd): lab+="."
        rests.append((xc,ytd,lab))

rests.sort()
print(f"Jinho p1 top system: {len(rests)} rests detected")
for xc,ytd,lab in rests: print(f"  x={xc:6.1f}  {lab}")

# overlay
cy=round((top_td-22)*F); ch=round((bot_td-top_td+44)*F)
cx=round(36*F); cw=round((560-36)*F)
subprocess.run(["convert","/tmp/part-01.png","-crop",f"{cw}x{ch}+{cx}+{cy}","+repage","/tmp/rest_strip.png"])
ann=[]
for xc,ytd,lab in rests:
    px=xc*F-cx; py=(ytd)*F-cy
    ann+=["-fill","#0070c0","-annotate",f"+{px-18:.0f}+{py-26:.0f}",lab]
for hx,hy in heads:   # also mark notes lightly
    ann+=["-fill","#d00000","-annotate",f"+{hx*F-cx-4:.0f}+{hy*F-cy+34:.0f}","♪"]
subprocess.run(["convert","/tmp/rest_strip.png","-pointsize","15",
    "-font","/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]+ann+["/tmp/poc_rest.png"])
print("overlay -> /tmp/poc_rest.png")
