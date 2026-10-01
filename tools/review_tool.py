"""Read a Swish Vision review zip: list every call and correction with its picture sheet,
and replay the recorded detections through the current tracker to compare calls.

usage: python3 tools/review_tool.py <review.zip> [replay.jar]
"""
import json, os, subprocess, sys, zipfile

zpath = sys.argv[1]
jar = sys.argv[2] if len(sys.argv) > 2 else os.path.expanduser('~/localtest/replay.jar')
out = os.path.splitext(zpath)[0] + '_unpacked'
os.makedirs(out, exist_ok=True)
with zipfile.ZipFile(zpath) as z:
    z.extractall(out)

frames = [json.loads(l) for l in open(os.path.join(out, 'frames.jsonl')) if l.strip()]
events = [json.loads(l) for l in open(os.path.join(out, 'events.jsonl')) if l.strip()] if os.path.exists(os.path.join(out, 'events.jsonl')) else []
sheets = sorted(f for f in os.listdir(out) if f.endswith('.jpg'))
summary = json.load(open(os.path.join(out, 'summary.json'))) if os.path.exists(os.path.join(out, 'summary.json')) else {}

t0 = frames[0]['t'] if frames else 0
dur = (frames[-1]['t'] - t0) / 1000 if frames else 0
with_ball = sum(1 for f in frames if f.get('b'))
with_rim = sum(1 for f in frames if f.get('rim'))
print(f"frames: {len(frames)} over {dur:.0f}s ({len(frames)/max(dur,1):.1f}/s) | rim set in {100*with_rim/max(len(frames),1):.0f}% | ball seen in {100*with_ball/max(len(frames),1):.0f}%")
if summary:
    print(f"session: {summary.get('makes')}/{summary.get('shots')} | camera: {summary.get('camera')} | avgFps {summary.get('avgFps')}")
rims = [f['rim'] for f in frames if f.get('rim')]
if rims:
    w = sorted(r[2] for r in rims)[len(rims)//2]
    print(f"rim width ~{w:.0f}px in the camera frame")

print("\nevents:")
sheet_by_id = {}
for s in sheets:
    try: sheet_by_id[int(s.split('_')[0])] = s
    except ValueError: pass
for e in events:
    rel = (e['t'] - t0) / 1000
    print(f"  {rel:7.1f}s  {e['e']:14s} {e.get('d',''):8s} {sheet_by_id.get(e.get('id'), '')}")

# Replay through the current tracker
rp = os.path.join(out, 'replay.txt')
with open(rp, 'w') as fh:
    for f in frames:
        rim = f.get('rim') or [0, 0, 0, 0]
        dets = ";".join(" ".join(str(v) for v in d) for d in f.get('b', []))
        fh.write(f"{f['t']} {' '.join(str(v) for v in rim)} | {dets}\n")
app_calls = [(f['t'], f['c']) for f in frames if f.get('c')]
if os.path.exists(jar):
    res = subprocess.run(['java', '-cp', jar, 'ReplayKt', rp], capture_output=True, text=True)
    new_calls = [(int(a), b) for a, b in (l.split() for l in res.stdout.splitlines() if l and not l.startswith('Picked'))]
    print(f"\napp made {len(app_calls)} calls; current tracker on the same detections makes {len(new_calls)}")
    for t, c in new_calls:
        match = [a for a in app_calls if abs(a[0] - t) < 800]
        print(f"  {(t-t0)/1000:7.1f}s  now {c:4s}   app {match[0][1] if match else '-'}")
print(f"\nunpacked to {out}")
