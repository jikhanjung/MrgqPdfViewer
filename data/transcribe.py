#!/usr/bin/env python3
"""Transcribe the Yejin melody from the vector score and synthesize audio.

Pitch comes from each notehead's vertical position (treble clef + G-major key).
Rhythm uses proportional-x timing: every measure is exactly 6 eighth-units (6/8),
and note/rest onsets are placed by their horizontal position within the measure.
Noteheads sound; rests act as silent boundaries that cap the previous note. Output
is a 16-bit PCM WAV written with the stdlib (no numpy)."""
import json, math, wave, struct, score_final as S
H=S.H
LAY=json.load(open("/tmp/score_layout.json"))
PLAYER=1   # Yejin = staff_bands index 1

LET="CDEFGAB"
def pitch_midi(y_td, top_td, bot_td):
    sp=(bot_td-top_td)/4.0; half=sp/2.0
    steps=round((y_td-top_td)/half)        # diatonic steps below F5 (top line)
    dn=(5*7+3)-steps                        # F5 diatonic number minus steps
    octv=dn//7; idx=dn%7; letter=LET[idx]
    semis=[0,2,4,5,7,9,11][idx]            # C-major semitone of letter
    if letter=="F": semis+=1               # G major -> F#
    return octv*12+semis+12                 # MIDI (C4=60 => octv4*12+0+12=60)

def classify_rest(w,h,c,ytd,top_td,bot_td):
    sp=(bot_td-top_td)/4.0; mid=top_td+2*sp; line4=top_td+sp
    centered=abs(ytd-mid)<sp*1.4
    if (not c) and 4.5<=w<=8 and 1.6<=h<=3.2: return True   # whole/half
    if c and 7.5<=h<=11 and 4<=w<=7 and centered: return True   # 8th
    if c and 11.5<=h<=15 and 5<=w<=8 and centered: return True  # 16th
    if c and 14<=h<=17.5 and w<=5.6 and centered: return True   # quarter
    return False

# build ordered systems (reading order) with measures and Yejin band
systems=[]
for pg in range(1,14):
    for s in LAY[str(pg)]:
        systems.append((pg,s))

events=[]     # (global_eighth_time, midi or None for rest-boundary, is_note)
meas_idx=0
report=[]
for pg,s in systems:
    rects=S.parse_page(S.PAGE_CONTENTS[pg])
    top_td,bot_td=s["staff_bands"][PLAYER]
    sp=(bot_td-top_td)/4.0
    lo=top_td-13; hi=bot_td+13
    heads=[]; rests=[]
    for X0,Y0,X1,Y1,c in rects:
        xc=(X0+X1)/2; yc=(Y0+Y1)/2; w=X1-X0; h=Y1-Y0; ytd=H-yc
        if not (lo<=ytd<=hi): continue
        if h<1.5 and w>80: continue
        if 5<=w<=7.5 and 4<=h<=6.5 and c:
            heads.append((xc,ytd))
        elif classify_rest(round(w,1),round(h,1),c,ytd,top_td,bot_td):
            rests.append((xc,None))
    for (mx0,mx1) in s["measures"]:
        ev=[(x,y,True) for x,y in heads if mx0-3<=x<=mx1-2] \
          +[(x,None,False) for x,_ in rests if mx0-3<=x<=mx1-2]
        ev.sort(key=lambda e:e[0])
        # dedupe near-identical x (chord/dup) keep one
        ded=[]
        for e in ev:
            if ded and abs(e[0]-ded[-1][0])<3 and e[2]==ded[-1][2]: continue
            ded.append(e)
        ev=ded
        base=meas_idx*6.0
        nnotes=sum(1 for e in ev if e[2])
        report.append((pg,meas_idx+1,nnotes,len(ev)-nnotes))
        if ev:
            xs=[e[0] for e in ev]
            for i,(x,y,isn) in enumerate(ev):
                t0=base+6.0*(x-mx0)/(mx1-mx0)
                t1=base+6.0 if i==len(ev)-1 else base+6.0*(ev[i+1][0]-mx0)/(mx1-mx0)
                if isn:
                    midi=pitch_midi(y,top_td,bot_td)
                    events.append((t0,max(0.18,t1-t0),midi))
        meas_idx+=1

print(f"Yejin: {meas_idx} measures, {len(events)} notes")
# show first 8 measures note counts
print("measures (pg,m#,notes,rests):", report[:10])

# ---- synthesize ----
SR=22050
EIGHTH=0.165               # seconds per eighth-unit (tempo); measure = 0.99s
total_t=meas_idx*6*EIGHTH + 1.0
buf=[0.0]*int(total_t*SR)
def midi_freq(m): return 440.0*2**((m-69)/12.0)
for (t0u,duru,midi) in events:
    t0=t0u*EIGHTH; dur=min(duru*EIGHTH*0.95, duru*EIGHTH)  # slight detach
    dur=max(dur,0.12)
    f=midi_freq(midi); n=int(dur*SR); st=int(t0*SR)
    for i in range(n):
        env=math.exp(-3.0*i/n)                      # decaying pluck/flute
        a=0.0 + (i/(0.005*SR) if i<0.005*SR else 1.0)  # 5ms attack
        a=min(a,1.0)
        ph=2*math.pi*f*i/SR
        s=(math.sin(ph)+0.35*math.sin(2*ph)+0.15*math.sin(3*ph))
        idx=st+i
        if idx<len(buf): buf[idx]+=0.22*a*env*s
# normalize & write
peak=max(1e-6,max(abs(v) for v in buf))
g=0.89/peak
with wave.open("/tmp/yejin.wav","w") as wf:
    wf.setnchannels(1); wf.setsampwidth(2); wf.setframerate(SR)
    wf.writeframes(b"".join(struct.pack("<h",int(max(-1,min(1,v*g))*32767)) for v in buf))
print("wrote /tmp/yejin.wav  (%.1f s)"%total_t)
