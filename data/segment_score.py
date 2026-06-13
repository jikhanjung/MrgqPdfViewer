#!/usr/bin/env python3
"""Segment a Sibelius/MS-Print-to-PDF vector score into systems and measures.

Coordinates: the page content is drawn under a base CTM of (0.75 0 0 -0.75 0 H),
so parsed points land in PDF user space where y=0 is the BOTTOM of the page.
For human reading order we flip to top-down: ytop = H - y_pdf. Systems are ordered
by DESCENDING y_pdf (top of page first).

Structure detection:
  staff line  = filled rect, height<1.5pt, width>80pt        (5 per staff)
  barline     = filled rect, width<2pt, both ends aligned to a staff's top &
                bottom line, and present at the same x across a majority of the
                system's staves. (Note stems attach to a notehead and rarely span
                the full staff top-to-bottom, so they fail the span+alignment test.)
"""
import subprocess, re, json, sys

PDF = "/tmp/clean.pdf"
H = 841.92007

# page object -> content stream object ids (from the Pages tree)
PAGE_CONTENTS = {
 1:[28,29],2:[32,33],3:[36,37],4:[40,41],5:[44,45],6:[48,49],7:[52,53],
 8:[56,57],9:[60,61],10:[64,65],11:[68,69],12:[72,73],13:[84,85]}

def mat_mul(m1,m2):
    a1,b1,c1,d1,e1,f1=m1; a2,b2,c2,d2,e2,f2=m2
    return (a1*a2+b1*c2,a1*b2+b1*d2,c1*a2+d1*c2,c1*b2+d1*d2,e1*a2+f1*c2+e2,e1*b2+f1*d2+f2)
def ap(m,x,y):
    a,b,c,d,e,f=m; return (a*x+c*y+e,b*x+d*y+f)

def parse_page(objs):
    lines=[]
    for o in objs:
        out=subprocess.run(["mutool","show",PDF,str(o)],capture_output=True,text=True).stdout
        if 'stream' in out: out=out.split('stream',1)[1]
        lines+=out.splitlines()
    ctm=(1,0,0,1,0,0); stack=[]; pts=[]; curve=False; rects=[]
    for ln in lines:
        t=ln.split()
        if not t: continue
        op=t[-1]; nums=[]
        for tok in t[:-1]:
            try: nums.append(float(tok))
            except: pass
        if op=='cm' and len(nums)>=6: ctm=mat_mul(tuple(nums[:6]),ctm)
        elif op=='q': stack.append(ctm)
        elif op=='Q':
            if stack: ctm=stack.pop()
        elif op in('m','l') and len(nums)>=2: pts.append(ap(ctm,nums[0],nums[1]))
        elif op=='c' and len(nums)>=6: curve=True; pts.append(ap(ctm,nums[4],nums[5]))
        elif op in('v','y') and len(nums)>=4: curve=True; pts.append(ap(ctm,nums[-2],nums[-1]))
        elif op in('f','F','f*','b','b*','B','B*','s','S','n'):
            if pts:
                xs=[p[0] for p in pts]; ys=[p[1] for p in pts]
                rects.append((min(xs),min(ys),max(xs),max(ys),curve))
            pts=[]; curve=False
    return rects

def cluster(vals,tol):
    vals=sorted(vals); out=[]; cur=[vals[0]]
    for v in vals[1:]:
        if v-cur[-1]<=tol: cur.append(v)
        else: out.append(sum(cur)/len(cur)); cur=[v]
    out.append(sum(cur)/len(cur)); return out

def analyze(page, objs):
    rects=parse_page(objs)
    staff=[]; vert=[]; heads=[]
    for x0,y0,x1,y1,c in rects:
        w=x1-x0; h=y1-y0
        if c:
            # notehead: small curved filled blob (~half-staff-space wide/tall)
            if 4<w<9 and 3<h<7: heads.append(((x0+x1)/2,(y0+y1)/2))
            continue
        if h<1.5 and w>80: staff.append(((y0+y1)/2,x0,x1))
        elif w<2.0 and h>6: vert.append(((x0+x1)/2,y0,y1))
    def has_head_at(x,y):
        return any(abs(hx-x)<4.5 and abs(hy-y)<4.5 for hx,hy in heads)
    if not staff: return None
    # distinct staff-line y's
    centers=sorted(cluster([s[0] for s in staff],2.0))
    # group into staves of 5 (lines come sorted; spacing within staff ~5pt)
    staves=[]
    i=0
    while i+5<=len(centers):
        g=centers[i:i+5]
        if g[-1]-g[0] < 28:  # 5 lines of one staff span ~20pt
            staves.append((g[0],g[-1])); i+=5
        else: i+=1
    # group staves into systems by vertical gap (in pdf-y; consecutive staves ~45pt apart)
    staves_sorted=sorted(staves)  # ascending pdf-y
    systems=[]; cur=[staves_sorted[0]]
    for s in staves_sorted[1:]:
        if s[0]-cur[-1][1] > 55: systems.append(cur); cur=[s]
        else: cur.append(s)
    systems.append(cur)
    systems.sort(key=lambda sg:-sg[0][0])  # top of page (higher pdf-y) first
    out=[]
    for sg in systems:
        top_pdf=sg[-1][1]; bot_pdf=sg[0][0]   # higher y = top edge
        x_left=min(min(x0 for c,x0,x1 in staff if abs(c-st)<2 or abs(c-sb)<2) for st,sb in sg)
        x_right=max(max(x1 for c,x0,x1 in staff if abs(c-st)<2 or abs(c-sb)<2) for st,sb in sg)
        # per-staff barline segments: span EXACTLY top->bottom staff line (<=1.5pt),
        # and neither end has a notehead attached (that would make it a stem).
        seg=[]
        for st,sb in sg:
            xs=[]
            for x,y0,y1 in vert:
                if abs(y0-st)<=1.5 and abs(y1-sb)<=1.5:
                    if not (has_head_at(x,st) or has_head_at(x,sb)):
                        xs.append(x)
            seg.append(sorted(xs))
        allx=sorted(x for xs in seg for x in xs)
        bars=[]
        if allx:
            for cx in cluster(allx,3.0):
                n=sum(1 for xs in seg if any(abs(x-cx)<=3 for x in xs))
                if n>=max(3,len(sg)-1): bars.append(round(cx,1))  # in (almost) all staves
        # merge spurious double barlines (real measures are never <15pt wide):
        # keep the member present in more staves
        if bars:
            merged=[bars[0]]
            for b in bars[1:]:
                if b-merged[-1] < 15:
                    pick=lambda v:sum(1 for xs in seg if any(abs(x-v)<=3 for x in xs))
                    if pick(b)>pick(merged[-1]): merged[-1]=b
                else: merged.append(b)
            bars=merged
        # ensure system's right edge counts as final barline
        if bars and abs(bars[-1]-x_right)>4: bars.append(round(x_right,1))
        # measure spans: from left staff edge through each barline
        bounds=[round(x_left,1)]+[b for b in bars if b>x_left+4]
        meas=[(bounds[k],bounds[k+1]) for k in range(len(bounds)-1)]
        out.append(dict(
            y_top=round(H-top_pdf,1), y_bot=round(H-bot_pdf,1),
            x_left=round(x_left,1), x_right=round(x_right,1),
            n_staves=len(sg), n_measures=len(meas),
            barlines=bounds, measures=meas))
    return out

if __name__=="__main__":
    grand=0; allpages={}
    for pg in range(1,14):
        res=analyze(pg, PAGE_CONTENTS[pg])
        allpages[pg]=res
        nsys=len(res); nmea=sum(s['n_measures'] for s in res)
        print(f"=== PAGE {pg}: {nsys} systems, {nmea} measures (m{grand+1}..m{grand+nmea}) ===")
        for si,s in enumerate(res):
            mn=grand+1+sum(allpages[pg][k]['n_measures'] for k in range(si))
            print(f"  system {si+1}: {s['n_staves']} staves, "
                  f"y=[{s['y_top']:.0f},{s['y_bot']:.0f}] x=[{s['x_left']:.0f},{s['x_right']:.0f}]  "
                  f"{s['n_measures']} measures (m{mn}..m{mn+s['n_measures']-1})  bars={s['barlines']}")
        grand+=nmea
    print(f"\nTOTAL: {grand} measures across {sum(len(v) for v in allpages.values())} systems, 13 pages")
    json.dump(allpages, open("/tmp/score_layout.json","w"), indent=1)
    print("written /tmp/score_layout.json")
