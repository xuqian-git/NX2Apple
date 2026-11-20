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
    /** Incomplete frames removed by age or by the in-progress bound. Video losses are also counted separately. */
    public int dropped, droppedVideo;
    /** Most recent incomplete video frame: sequence, fragments received and expected. */
    public int lostSeq, lostGot, lostCount;
    public Wire(long token) { this.token=token; }
    public Frame accept(byte[] packet, int size, long now) {
        Iterator<Pending> it=pending.values().iterator();
        while(it.hasNext()) { Pending p=it.next(); if(now-p.born>60_000_000L) { it.remove(); lost(p); } }
        if(size<HEADER || size>HEADER+CHUNK) return null;
        ByteBuffer b=ByteBuffer.wrap(packet,0,size).order(ByteOrder.BIG_ENDIAN);
        if(b.getInt()!=0x4e585354 || b.get()!=1) return null;
        Frame f=new Frame(); f.type=b.get()&255; f.flags=b.getShort()&65535; f.seq=b.getInt(); f.pts=b.getLong();
        f.width=b.getShort()&65535; f.height=b.getShort()&65535; f.channels=b.getShort()&65535; b.getShort(); f.rate=b.getInt();
        int index=b.getShort()&65535, count=b.getShort()&65535, total=b.getInt();
        if(b.getLong()!=token || (f.type!=1 && f.type!=2) || total<=0 || total>MAX || count!=(total+CHUNK-1)/CHUNK || index>=count) return null;
        int len=Math.min(CHUNK,total-index*CHUNK);
        if(size!=HEADER+len) return null;
        if(f.type==1 && (f.width<320||f.height<240||f.width>4096||f.height>4096||(f.width&1)!=0||(f.height&1)!=0||(long)f.width*f.height>4096L*2160)) return null;
        if(f.type==2 && (f.rate!=48000 || !(f.channels==2||f.channels==6||f.channels==8||f.channels==12) || total%(f.channels*2)!=0 || total>23040)) return null;
        long key=((long)f.type<<32)|(f.seq&0xffffffffL);
        Pending p=pending.get(key);
        if(p==null) {
            if(pending.size()>=8) { long oldest=Collections.min(pending.keySet(),Comparator.comparingLong(k->pending.get(k).born)); lost(pending.remove(oldest)); }
            p=new Pending(); p.f=f; p.data=new byte[total]; p.count=count; p.bits=new BitSet(count); p.born=now; pending.put(key,p);
        }
        if(p.data.length!=total||p.count!=count||p.f.pts!=f.pts||p.f.flags!=f.flags||p.f.width!=f.width||p.f.height!=f.height||p.f.channels!=f.channels||p.f.rate!=f.rate) return null;
        if(!p.bits.get(index)) { System.arraycopy(packet,HEADER,p.data,index*CHUNK,len); p.bits.set(index); p.got++; }
        if(p.got!=count) return null;
        pending.remove(key); p.f.data=p.data; return p.f;
    }
    private void lost(Pending p) {
        dropped++;
        if(p.f.type==1) { droppedVideo++; lostSeq=p.f.seq; lostGot=p.got; lostCount=p.count; }
    }
    /** Receiver clock probe: NXPG, version 1, token, receiver nanoseconds echoed by the sender. */
    public static final int PING_SIZE=24, PONG_SIZE=56;
    public static byte[] ping(long token, long receiverNanos) { return ByteBuffer.allocate(PING_SIZE).order(ByteOrder.BIG_ENDIAN).putInt(0x4e585047).putInt(1).putLong(token).putLong(receiverNanos).array(); }
    /** Sender reply with its clock and cumulative counters. Counters are unsigned and wrap. */
    public static final class Pong {
        public long echo, senderMicros;
        public int sendErrors, captured, skipped, encodeMaxMicros, lastErrno, packetsSent;
    }
    public static Pong pong(byte[] packet, int size, long token) {
        if(size!=PONG_SIZE) return null;
        ByteBuffer b=ByteBuffer.wrap(packet,0,size).order(ByteOrder.BIG_ENDIAN);
        if(b.getInt()!=0x4e58504f || b.getInt()!=1 || b.getLong()!=token) return null;
        Pong p=new Pong(); p.echo=b.getLong(); p.senderMicros=b.getLong();
        p.sendErrors=b.getInt(); p.captured=b.getInt(); p.skipped=b.getInt(); p.encodeMaxMicros=b.getInt(); p.lastErrno=b.getInt(); p.packetsSent=b.getInt();
        return p;
    }
    public static byte[] keyRequest(long token) { return ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).putInt(0x4e584b46).putInt(1).putLong(token).array(); }
}
