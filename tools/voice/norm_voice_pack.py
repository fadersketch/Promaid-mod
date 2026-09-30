# -*- coding: utf-8 -*-
"""把 _voice_new2/*.raw.wav 峰值归一化到 -1.5 dB 并编码 ogg q4（libvorbis）。"""
import os
import re
import subprocess

FF = r'D:\GPT-SoVITS-v2pro-20250604-nvidia50\runtime\ffmpeg.exe'
OUT = r'C:\Users\Sketch\.zcode\workspace\default\promaid-mod\_voice_new2'
TARGET = -1.5


def run(args):
    return subprocess.run(args, capture_output=True, text=True, encoding='utf-8', errors='replace')


files = sorted(f for f in os.listdir(OUT) if f.endswith('.raw.wav'))
print('raw files:', len(files))
bad = []
for f in files:
    src = os.path.join(OUT, f)
    dst = os.path.join(OUT, f.replace('.raw.wav', '.ogg'))
    r = run([FF, '-hide_banner', '-i', src, '-af', 'volumedetect', '-f', 'null', '-'])
    m = re.search(r'max_volume:\s*(-?[\d.]+)\s*dB', r.stderr)
    if not m:
        bad.append(f)
        print('FAIL volumedetect', f)
        continue
    maxv = float(m.group(1))
    gain = TARGET - maxv
    run([FF, '-y', '-hide_banner', '-i', src,
         '-af', 'volume=%.2fdB,alimiter=limit=0.95' % gain,
         '-c:a', 'libvorbis', '-q:a', '4', dst])
    ok = os.path.isfile(dst) and os.path.getsize(dst) > 0
    if not ok:
        bad.append(f)
    print('%-30s maxv=%6.2f gain=%+6.2f -> %s %s' % (
        f, maxv, gain, 'OK' if ok else 'FAIL', os.path.getsize(dst) if ok else 0))
print('encoded:', len([f for f in os.listdir(OUT) if f.endswith('.ogg')]), 'bad:', bad)
