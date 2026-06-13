#!/usr/bin/env python3
"""Render the full 5-part ensemble to stereo audio.
Each player's staff is transcribed (pitch from notehead position, proportional-x
rhythm with measure = 6 eighth-units) and rendered with its own pan/timbre.
Vertical alignment in the engraving => simultaneous onsets, so parts stay in sync."""
import json, math, wave, numpy as np, score_final as S
H=S.H
LAY=json.load(open("/tmp/score_layout.json"))
PLAYERS=["진호","예진","하진","예완","은석"]
LET="CDEFGAB"
def pitch_midi(y,top,bot):
    sp=(bot-top)/4.0; half=sp/2.0
    steps=round((y-top)/half); dn=(5*7+3)-steps
    octv=dn//7; idx=dn%7
    return octv*12+([0,2,4,5,7,9,11][idx]+(1 if idx==3 else 0))+12
def is_rest(w,h,c,ytd,top,bot):
    sp=(bot-top)/4.0; mid=top+2*sp; cen=abs(ytd-mid)<sp*1.4
    if (not c) and 4.5<=w<=8 and 1.6<=h<=3.2: return True
    if c and 7.5<=h<=11 and 4<=w<=7 and cen: return True
    if c and 11.5<=h<=15 and 5<=w<=8 and cen: return True
    if c and 14<=h<=17.5 and w<=5.6 and cen: return True
    return False

systems=[(pg,s) for pg in range(1,14) for s in LAY[str(pg)]]
# cache page parses
pcache={pg:S.parse_page(S.PAGE_CONTENTS[pg]) for pg in range(1,14)}

def extract(pi):
    notes=[]; mi=0
    for pg,s in systems:
        top,bot=s["staff_bands"][pi]; lo=top-13; hi=bot+13
        heads=[]; rests=[]
        for X0,Y0,X1,Y1,c in pcache[pg]:
            xc=(X0+X1)/2; w=X1-X0; h=Y1-Y0; ytd=H-(Y0+Y1)/2
            if not(lo<=ytd<=hi): continue
            if h<1.5 and w>80: continue
            if 5<=w<=7.5 and 4<=h<=6.5 and c: heads.append((xc,ytd))
            elif is_rest(round(w,1),round(h,1),c,ytd,top,bot): rests.append(xc)
        for mx0,mx1 in s["measures"]:
            hh=sorted([(x,y) for x,y in heads if mx0-3<=x<=mx1-2])
            rr=sorted([x for x in rests if mx0-3<=x<=mx1-2])
            # cluster noteheads into onsets (chords share x); merge rest x's
            onsets=[]   # (x, [pitches])
            for x,y in hh:
                if onsets and abs(x-onsets[-1][0])<4: onsets[-1][1].append((x,y))
                else: onsets.append([x,[(x,y)]])
            allx=sorted([(o[0],'n',o) for o in onsets]+[(x,'r',None) for x in rr])
            base=mi*6.0
            for i,(x,kind,o) in enumerate(allx):
                t0=base+6*(x-mx0)/(mx1-mx0)
                t1=base+6 if i==len(allx)-1 else base+6*(allx[i+1][0]-mx0)/(mx1-mx0)
                if kind=='n':
                    for (nx,ny) in o[1]:
                        notes.append((t0,max(0.25,t1-t0),pitch_midi(ny,top,bot)))
            mi+=1
    return notes,mi

parts=[]
for pi,name in enumerate(PLAYERS):
    ns,mi=extract(pi)
    ms=[n[2] for n in ns]
    print(f"{name}: {len(ns)} notes, range {min(ms)}-{max(ms)} (MIDI)")
    parts.append(ns)

# ---- render ----
SR=44100; EIGHTH=0.33                      # half speed
total=max(n[0]+n[1] for ns in parts for n in ns)*EIGHTH+2.5
L=np.zeros(int(total*SR)); R=np.zeros_like(L)
# per-part: pan(-1..1), gain, harmonic weights, octave shift
CFG=[ (-0.5,0.55,[1,.35,.15,.06],0),     # 진호
      ( 0.0,0.95,[1,.45,.2,.1,.05],0),   # 예진 melody (center, loud)
      ( 0.5,0.55,[1,.35,.15,.06],0),     # 하진
      (-0.25,0.55,[1,.3,.12],0),         # 예완
      ( 0.15,0.75,[1,.5,.22,.1],0) ]     # 은석 bass (warm)
def freq(m): return 440.0*2**((m-69)/12.0)
for ns,(pan,gain,harm,oct_) in zip(parts,CFG):
    lg=math.sqrt((1-pan)/2); rg=math.sqrt((1+pan)/2)
    for t0u,duru,midi in ns:
        midi+=12*oct_
        t0=t0u*EIGHTH; dur=duru*EIGHTH; n=int(min(dur*0.97,dur)*SR)
        if n<10: continue
        t=np.arange(n)/SR; f=freq(midi)
        vib=1.0+0.004*np.sin(2*np.pi*5.0*t)
        ph=2*np.pi*f*t*vib
        w=sum(a*np.sin((k+1)*ph) for k,a in enumerate(harm))
        env=np.exp(-2.0*t/dur)
        a=int(0.01*SR)
        if 0<a<n: env[:a]*=np.linspace(0,1,a)
        r=int(0.035*SR)
        if r<n: env[-r:]*=np.linspace(1,0,r)
        seg=0.16*gain*w*env
        st=int(t0*SR)
        L[st:st+n]+=lg*seg; R[st:st+n]+=rg*seg

# light reverb
def comb(x,d,g):
    d=int(d*SR); y=x.copy()
    for i in range(d,len(y)): y[i]+=g*y[i-d]
    return y
def verb(x):
    w=np.zeros_like(x)
    for d,g in [(0.0297,0.74),(0.0371,0.72),(0.0411,0.70),(0.0437,0.68)]:
        w+=comb(x,d,g)*0.25
    return w
wet=0.5*(verb(L)+verb(R))
Lo=0.85*L+0.22*wet; Ro=0.85*R+0.22*wet
st=np.stack([Lo,Ro],axis=1); st/=max(1e-6,np.max(np.abs(st))); st*=0.93
pcm=(st*32767).astype('<i2')
with wave.open("/tmp/ensemble.wav","w") as wf:
    wf.setnchannels(2); wf.setsampwidth(2); wf.setframerate(SR)
    wf.writeframes(pcm.tobytes())
print(f"wrote /tmp/ensemble.wav  ({total:.0f}s)")
