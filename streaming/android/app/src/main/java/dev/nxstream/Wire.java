package dev.nxstream;

import java.nio.*;
import java.util.*;

/** NXST v1. All integers big endian; 48-byte header, <=1152 payload bytes. */
public final class Wire {
    public static final int HEADER=48, CHUNK=1152, MAX=2*1024*1024;
    public static final class Frame {
        public int type, flags, seq, width, height, channels, rate;
        public long pts;
        public byte[] data;
        public boolean key() { return (flags & 1)!=0; }
    }
    private static final class Pending {
        Frame f; byte[] data; BitSet bits; int count, got; long born;
    }
    private final Map<Long,Pending> pending=new HashMap<>();
    private final long token;
    public int dropped;
    public Wire(long token) { this.token=token; }
    public Frame accept(byte[] packet, int size, long now) {
        Iterator<Pending> it=pending.values().iterator();
        while(it.hasNext()) if(now-it.next().born>60_000_000L) { it.remove(); dropped++; }
        if(size<HEADER || size>HEADER+CHUNK) return null;
        ByteBuffer b=ByteBuffer.wrap(packet,0,size).order(ByteOrder.BIG_ENDIAN);
        if(b.getInt()!=0x4e585354 || b.get()!=1) return null;
        Frame f=new Frame(); f.type=b.get()&255; f.flags=b.getShort()&65535; f.seq=b.getInt(); f.pts=b.getLong();
        f.width=b.getShort()&65535; f.height=b.getShort()&65535; f.channels=b.getShort()&65535; b.getShort(); f.rate=b.getInt();
        int index=b.getShort()&65535, count=b.getShort()&65535, total=b.getInt();
        if(b.getLong()!=token || (f.type!=1 && f.type!=2) || total<=0 || total>MAX || count!=(total+CHUNK-1)/CHUNK || index>=count) return null;
        int len=Math.min(CHUNK,total-index*CHUNK);
        if(size!=HEADER+len) return null;
        if(f.type==1 && (!((f.width==2880&&f.height==1620)||(f.width==2560&&f.height==1440)||(f.width==1920&&f.height==1080)))) return null;
        if(f.type==2 && (f.rate!=48000 || !(f.channels==2||f.channels==6||f.channels==8||f.channels==12) || total%(f.channels*2)!=0 || total>23040)) return null;
        long key=((long)f.type<<32)|(f.seq&0xffffffffL);
        Pending p=pending.get(key);
        if(p==null) {
            if(pending.size()>=8) { long oldest=Collections.min(pending.keySet(),Comparator.comparingLong(k->pending.get(k).born)); pending.remove(oldest); dropped++; }
            p=new Pending(); p.f=f; p.data=new byte[total]; p.count=count; p.bits=new BitSet(count); p.born=now; pending.put(key,p);
        }
        if(p.data.length!=total||p.count!=count||p.f.pts!=f.pts||p.f.flags!=f.flags||p.f.width!=f.width||p.f.height!=f.height||p.f.channels!=f.channels||p.f.rate!=f.rate) return null;
        if(!p.bits.get(index)) { System.arraycopy(packet,HEADER,p.data,index*CHUNK,len); p.bits.set(index); p.got++; }
        if(p.got!=count) return null;
        pending.remove(key); p.f.data=p.data; return p.f;
    }
    public static byte[] keyRequest(long token) { return ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).putInt(0x4e584b46).putInt(1).putLong(token).array(); }
}
