#!/usr/bin/env python3
"""Exercise real Metal -> VideoToolbox -> UDP, reassemble and decode with ffmpeg."""
import os, socket, struct, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def run(size):
    out=ROOT.parent/'stream-smoke'/size;out.mkdir(parents=True,exist_ok=True)
    executable=ROOT.parent/'stream-smoke/sender'
    if not executable.exists() or executable.stat().st_mtime < max((ROOT/rel).stat().st_mtime for rel in ('streaming/ios/nx_stream.m', 'streaming/tests/sender_smoke.m')):
        subprocess.run(['xcrun','clang','-fobjc-arc','-fmodules','-O1',str(ROOT/'streaming/ios/nx_stream.m'),str(ROOT/'streaming/tests/sender_smoke.m'),'-framework','Foundation','-framework','Metal','-framework','VideoToolbox','-framework','CoreVideo','-framework','CoreMedia','-framework','QuartzCore','-o',str(executable)],check=True)
    token=0x123456789abcdef0
    env=dict(os.environ,DM_STREAM_HOST='127.0.0.1',DM_STREAM_TOKEN=f'{token:016x}',DM_STREAM_SIZE=size)
    s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.bind(('127.0.0.1',48000));s.settimeout(.2);s.setsockopt(socket.SOL_SOCKET,socket.SO_RCVBUF,2*1024*1024)
    pending={};video=[];audio=[]
    proc=subprocess.Popen([str(executable)],env=env)
    while True:
        try:data,peer=s.recvfrom(2048)
        except socket.timeout:
            if proc.poll() is not None:break
            continue
        assert len(data)>=48
        magic,version,kind,flags,seq,pts,w,h,ch,_,rate,index,count,total,tok=struct.unpack('>4sBBHIQHHHHIHHIQ',data[:48])
        assert magic==b'NXST' and version==1 and tok==token
        assert (w,h)==tuple(map(int,size.split('x')))
        key=(kind,seq);entry=pending.setdefault(key,{'parts':{},'count':count,'total':total,'flags':flags,'ch':ch})
        entry['parts'][index]=data[48:]
        if len(entry['parts'])==count:
            raw=b''.join(entry['parts'][i] for i in range(count));assert len(raw)==total
            if kind==1:video.append((seq,raw,flags))
            else:
                assert ch==6 and rate==48000
                samples=struct.unpack('<'+'h'*(len(raw)//2),raw);assert samples[2]==1000
                audio.append(raw)
            del pending[key]
    assert proc.wait()==0;s.close()
    assert len(video)>20 and len(audio)>20,(len(video),len(audio))
    video.sort();assert video[0][2]&1
    stream=out/'video.h264';stream.write_bytes(b''.join(v[1] for v in video))
    subprocess.run(['ffmpeg','-v','error','-i',str(stream),'-f','null','-'],check=True)
    subprocess.run(['ffmpeg','-v','error','-i',str(stream),'-frames:v','1','-y',str(out/'first.png')],check=True)
    rgb=subprocess.check_output(['ffmpeg','-v','error','-i',str(out/'first.png'),'-vf','crop=1:1:0:0,format=rgb24','-frames:v','1','-f','rawvideo','-'])
    assert abs(rgb[0]-188)<=5 and rgb[1]<=5 and rgb[2]<=5, ('SDR color metadata mismatch',list(rgb))
    print(f'{size}: {len(video)} decoded H.264 frames, {len(audio)} six-channel PCM blocks')
if __name__=='__main__':
    for size in ('1920x1080','2560x1440','2880x1620'):run(size)
