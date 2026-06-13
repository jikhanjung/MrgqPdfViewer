#!/usr/bin/env python3
"""Full 5-part ensemble, metrical timing.
Every measure occupies an EQUAL time slot (6 eighth-units). Within a measure the
detected note/rest durations are placed sequentially (no gaps) and then scaled so
they sum to exactly 6 -> equal measures, even tempo, barlines aligned across parts."""
import json, math, wave, numpy as np, score_final as S
from dur_detect import analyze_part
H=S.H
LAY=json.load(open("/tmp/score_layout.json"))
PLAYERS=["진호","예진","하진","예완","은석"]
pcache={pg:S.parse_page(S.PAGE_CONTENTS[pg]) for pg in range(1,14)}
systems=[(pg,s) for pg in range(1,14) for s in LAY[str(pg)]]

def part_notes(pi):
    """return list of (t0_eighth, dur_eighth, midi); measures equal-length & scaled to 6."""
    out=[]; mi=0
    for pg,s in systems:
        nl,rl=analyze_part(pcache[pg], s["staff_bands"][pi], s["measures"])
        for mx0,mx1 in s["measures"]:
            ev=sorted([(x,p,d,True) for x,p,d in nl if mx0-3<=x<=mx1-2]+
                      [(x,None,d,False) for x,_,d in rl if mx0-3<=x<=mx1-2])
            # group chords (same x) into single slots
            slots=[]   # (dur, [pitches])
            for x,p,d,isn in ev:
                if slots and abs(x-slots[-1][2])<4:
                    if isn and p is not None: slots[-1][1].append(p)
                    slots[-1][0]=max(slots[-1][0],d)
                    continue
                slots.append([d,[p] if (isn and p is not None) else [], x])
            base=mi*6.0
            if slots:
                tot=sum(sl[0] for sl in slots) or 6.0
                sc=6.0/tot
                t=base
                for d,pits,x in slots:
                    dd=d*sc
                    for p in pits:
                        out.append((t,dd,p))
                    t+=dd
            mi+=1
    return out

parts=[part_notes(pi) for pi in range(5)]
for name,ns in zip(PLAYERS,parts): print(f"{name}: {len(ns)} sounding notes")

# ---- render (half speed) ----
SR=44100; EIGHTH=0.33
total=max((n[0]+n[1] for ns in parts for n in ns))*EIGHTH+2.5
L=np.zeros(int(total*SR)); R=np.zeros_like(L)
CFG=[(-0.5,0.55,[1,.35,.15,.06]),(0.0,0.95,[1,.45,.2,.1,.05]),
     (0.5,0.55,[1,.35,.15,.06]),(-0.25,0.55,[1,.3,.12]),(0.15,0.8,[1,.5,.22,.1])]
def freq(m): return 440.0*2**((m-69)/12.0)
for ns,(pan,gain,harm) in zip(parts,CFG):
    lg=math.sqrt((1-pan)/2); rg=math.sqrt((1+pan)/2)
    for t0u,duru,midi in ns:
        t0=t0u*EIGHTH; dur=duru*EIGHTH*0.95; n=int(dur*SR)
        if n<10: continue
        t=np.arange(n)/SR; f=freq(midi)
        ph=2*np.pi*f*t*(1+0.004*np.sin(2*np.pi*5*t))
        w=sum(a*np.sin((k+1)*ph) for k,a in enumerate(harm))
        env=np.exp(-1.8*t/(duru*EIGHTH));
        a=int(0.01*SR)
        if 0<a<n: env[:a]*=np.linspace(0,1,a)
        rr=int(0.035*SR)
        if rr<n: env[-rr:]*=np.linspace(1,0,rr)
        seg=0.16*gain*w*env; st=int(t0*SR)
        L[st:st+n]+=lg*seg; R[st:st+n]+=rg*seg
def comb(x,d,g):
    d=int(d*SR); y=x.copy()
    for i in range(d,len(y)): y[i]+=g*y[i-d]
    return y
def verb(x):
    w=np.zeros_like(x)
    for d,g in [(0.0297,0.74),(0.0371,0.72),(0.0411,0.70),(0.0437,0.68)]: w+=comb(x,d,g)*0.25
    return w
wet=0.5*(verb(L)+verb(R)); Lo=0.85*L+0.2*wet; Ro=0.85*R+0.2*wet
st=np.stack([Lo,Ro],axis=1); st/=max(1e-6,np.max(np.abs(st))); st*=0.93
with wave.open("/tmp/ensemble2.wav","w") as wf:
    wf.setnchannels(2); wf.setsampwidth(2); wf.setframerate(SR)
    wf.writeframes((st*32767).astype('<i2').tobytes())
print(f"wrote /tmp/ensemble2.wav ({total:.0f}s)")
