#!/usr/bin/env python3
"""Detect actual note/rest durations (not proportional-x) so playback is metrical.
Per notehead: find its stem, count beams crossing the stem's far end (2=16th,1=8th),
else look for a flag (8th) else quarter; augmentation dot => x1.5. Rests classified
by glyph. Validate by summing each measure to 6 eighth-units (6/8)."""
import json, score_final as S
H=S.H
LAY=json.load(open("/tmp/score_layout.json"))
LET="CDEFGAB"
def pitch_midi(y,top,bot):
    sp=(bot-top)/4.0; steps=round((y-top)/(sp/2)); dn=(5*7+3)-steps
    o=dn//7; i=dn%7; return o*12+([0,2,4,5,7,9,11][i]+(1 if i==3 else 0))+12

def rest_dur(w,h,c,ytd,top,bot,dots):
    sp=(bot-top)/4.0; mid=top+2*sp; cen=abs(ytd-mid)<sp*1.4
    base=None
    if (not c) and 4.5<=w<=8 and 1.6<=h<=3.2: base=6.0          # whole-measure rest
    elif c and 7.5<=h<=11 and 4<=w<=7 and cen: base=1.0        # 8th
    elif c and 11.5<=h<=15 and 5<=w<=8 and cen: base=0.5       # 16th
    elif c and 14<=h<=17.5 and w<=5.6 and cen: base=2.0        # quarter
    return base

def analyze_part(pg_rects, band, measures):
    top,bot=band; sp=(bot-top)/4.0; lo=top-13; hi=bot+13
    heads=[]; stems=[]; beams=[]; dots=[]; curves=[]; rests=[]
    for X0,Y0,X1,Y1,c in pg_rects:
        xc=(X0+X1)/2; w=X1-X0; h=Y1-Y0; ytd=H-(Y0+Y1)/2
        yt0=H-Y1; yt1=H-Y0   # td top,bottom of this rect
        if h<1.5 and w>80: continue
        if w<2.0 and h>6 and (lo-6<=ytd<=hi+6):     # stem (allow extending past band)
            stems.append((xc,yt0,yt1)); continue
        if not(lo<=ytd<=hi):
            # beams can sit a bit above/below band (stem ends); keep wide non-curve
            if (not c) and w>9 and 1.8<=h<=9 and (lo-30<=ytd<=hi+30):
                beams.append((X0,X1,yt0,yt1))
            continue
        if 5<=w<=7.5 and 4<=h<=6.5 and c: heads.append((xc,ytd))
        elif w<2.6 and h<2.6: dots.append((xc,ytd))
        elif (not c) and w>9 and 1.8<=h<=9: beams.append((X0,X1,yt0,yt1))
        elif c: curves.append((xc,ytd,w,h))
    # also catch beams slightly outside via second pass already done
    def has_dot(hx,hy):
        return any(hx< dx<=hx+8 and abs(dy-hy)<sp for dx,dy in dots)
    def find_stem(hx,hy):
        best=None;bd=5.0
        for sx,a,b in stems:
            if abs(sx-hx)<bd and b>=hy-3 and a<=hy+3:
                bd=abs(sx-hx); best=(sx,a,b)
        return best
    def beam_count(sx,far_y):
        n=0
        for bx0,bx1,a,b in beams:
            if bx0-2<=sx<=bx1+2 and (a-6<=far_y<=b+6): n+=1
        return n
    def has_flag(sx,far_y):
        return any(abs(cx-sx)<8 and abs(cy-far_y)<9 and 3<w<10 and 6<h<22
                   for cx,cy,w,h in curves)
    # build note list with durations
    notelist=[]   # (x, pitch, dur)
    for hx,hy in heads:
        stm=find_stem(hx,hy)
        if stm:
            sx,a,b=stm
            up = (abs(hy-b)<abs(hy-a))     # head near bottom => stem up
            far = a if up else b
            bc=beam_count(sx,far)
            if bc>=2: dur=0.5
            elif bc==1: dur=1.0
            elif has_flag(sx,far): dur=1.0
            else: dur=2.0
        else:
            dur=2.0
        if has_dot(hx,hy): dur*=1.5
        notelist.append((hx,pitch_midi(hy,top,bot),dur))
    restlist=[]
    for X0,Y0,X1,Y1,c in pg_rects:
        xc=(X0+X1)/2; w=X1-X0; h=Y1-Y0; ytd=H-(Y0+Y1)/2
        if not(lo<=ytd<=hi): continue
        d=rest_dur(round(w,1),round(h,1),c,ytd,top,bot,dots)
        if d:
            if d<6 and any(xc<dx<=xc+8 and abs(dy-ytd)<sp for dx,dy in dots): d*=1.5
            restlist.append((xc,None,d))
    return notelist,restlist

if __name__=="__main__":
    pcache={pg:S.parse_page(S.PAGE_CONTENTS[pg]) for pg in range(1,14)}
    systems=[(pg,s) for pg in range(1,14) for s in LAY[str(pg)]]
    PLAYERS=["진호","예진","하진","예완","은석"]
    for pi,name in enumerate(PLAYERS):
        ok=0;tot=0;mi=0;samples=[]
        for pg,s in systems:
            nl,rl=analyze_part(pcache[pg], s["staff_bands"][pi], s["measures"])
            for mx0,mx1 in s["measures"]:
                ev=sorted([e for e in nl if mx0-3<=e[0]<=mx1-2]+
                          [e for e in rl if mx0-3<=e[0]<=mx1-2])
                # chord: merge same-x notes (one slot)
                ssum=0; seen=[]
                for x,p,d in ev:
                    if seen and abs(x-seen[-1])<4: continue
                    seen.append(x); ssum+=d
                tot+=1; mi+=1
                if abs(ssum-6)<0.6: ok+=1
                if pi==1 and mi<=14: samples.append(round(ssum,1))
        print(f"{name}: {ok}/{tot} measures sum~6  ({100*ok//tot}%)")
        if pi==1: print("   예진 first 14 measure-sums:",samples)
