package dev.nxstream;
import java.nio.*;
import java.util.*;
public class WireTest {
 static final long TOKEN=0x123456789abcdef0L;
 static byte[] packet(int seq,int index,int total,long token){int n=Math.min(Wire.CHUNK,total-index*Wire.CHUNK);ByteBuffer b=ByteBuffer.allocate(Wire.HEADER+n).order(ByteOrder.BIG_ENDIAN);b.putInt(0x4e585354).put((byte)1).put((byte)1).putShort((short)1).putInt(seq).putLong(1).putShort((short)1920).putShort((short)1080).putShort((short)0).putShort((short)0).putInt(48000).putShort((short)index).putShort((short)((total+1151)/1152)).putInt(total).putLong(token);while(b.hasRemaining())b.put((byte)(index+1));return b.array();}
 static void require(boolean value){if(!value)throw new AssertionError();}
 static Wire.Frame add(Wire w,byte[] p,long now){return w.accept(p,p.length,now);}
 public static void main(String[] args){
  Wire w=new Wire(TOKEN);require(add(w,packet(1,1,2000,TOKEN),1)==null);require(add(w,packet(1,1,2000,TOKEN),2)==null);
  Wire.Frame f=add(w,packet(1,0,2000,TOKEN),3);require(f!=null&&f.data.length==2000&&f.data[0]==1&&f.data[1999]==2);
  require(add(w,packet(2,0,100,TOKEN+1),4)==null);
  byte[] bad=packet(2,0,100,TOKEN);ByteBuffer.wrap(bad).putInt(36,Integer.MAX_VALUE);require(add(w,bad,4)==null);
  require(add(w,packet(3,0,2000,TOKEN),5)==null);require(add(w,packet(3,1,2000,TOKEN),70_000_000)==null);require(w.dropped==1);
  byte[] truncated=Arrays.copyOf(packet(4,0,100,TOKEN),50);require(add(w,truncated,80_000_000)==null);
  Random random=new Random(1);for(int i=0;i<10000;i++){byte[] junk=new byte[random.nextInt(1300)];random.nextBytes(junk);w.accept(junk,junk.length,i);}
  // Conflicting metadata must not poison an existing frame or splice two frames.
  byte[] first=packet(5,0,2000,TOKEN),second=packet(5,1,2000,TOKEN);require(add(w,first,90_000_000)==null);ByteBuffer.wrap(second).putShort(20,(short)2560).putShort(22,(short)1440);require(add(w,second,90_000_001)==null);
  require(add(w,packet(5,1,2000,TOKEN),90_000_002)!=null);
  byte[] custom=packet(6,0,100,TOKEN);ByteBuffer.wrap(custom).putShort(20,(short)2560).putShort(22,(short)1600);require(add(w,custom,100_000_000)!=null);
  ByteBuffer.wrap(custom).putShort(20,(short)4096).putShort(22,(short)4096);require(add(w,custom,100_000_001)==null);
  ByteBuffer.wrap(custom).putShort(20,(short)1921).putShort(22,(short)1080);require(add(w,custom,100_000_002)==null);
  require(w.droppedVideo==w.dropped&&w.lostSeq==3&&w.lostGot==1&&w.lostCount==2);
  // Clock probe round trip and strict pong parsing.
  byte[] ping=Wire.ping(TOKEN,0x0102030405060708L);require(ping.length==Wire.PING_SIZE&&ping[0]=='N'&&ping[3]=='G'&&ByteBuffer.wrap(ping).getLong(16)==0x0102030405060708L);
  ByteBuffer pong=ByteBuffer.allocate(Wire.PONG_SIZE).putInt(0x4e58504f).putInt(1).putLong(TOKEN).putLong(0x0102030405060708L).putLong(123456789L).putInt(1).putInt(2).putInt(3).putInt(4).putInt(55).putInt(-1);
  Wire.Pong o=Wire.pong(pong.array(),Wire.PONG_SIZE,TOKEN);require(o!=null&&o.echo==0x0102030405060708L&&o.senderMicros==123456789L&&o.sendErrors==1&&o.captured==2&&o.skipped==3&&o.encodeMaxMicros==4&&o.lastErrno==55&&o.packetsSent==-1);
  require(Wire.pong(pong.array(),Wire.PONG_SIZE,TOKEN+1)==null&&Wire.pong(pong.array(),Wire.PONG_SIZE-1,TOKEN)==null);
  System.out.println("Wire tests passed: reorder, duplicate, token, bounds, expiry, metadata, 10000 malformed packets, clock probe");
 }
}
