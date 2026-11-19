package dev.nxstream;

import android.media.*;
import android.os.*;
import android.view.Surface;
import java.net.*;
import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

final class Receiver implements AutoCloseable {
    private final Surface surface;
    private final long token;
    private final boolean stereo;
    private final Consumer<String> status;
    private final ArrayBlockingQueue<Wire.Frame> video=new ArrayBlockingQueue<>(3),audio=new ArrayBlockingQueue<>(12);
    private final AtomicBoolean alive=new AtomicBoolean(true),needKey=new AtomicBoolean(true);
    private final Thread network,decoder,sound;
    private volatile DatagramSocket socket;
    private volatile SocketAddress peer;
    private long lastRequest;
    Receiver(Surface surface,long token,boolean stereo,Consumer<String> status) {
        this.surface=surface;this.token=token;this.stereo=stereo;this.status=status;
        network=new Thread(this::receive,"nx-receive");decoder=new Thread(this::decode,"nx-decode");sound=new Thread(this::play,"nx-audio");
        network.start();decoder.start();sound.start();
    }
    private void requestKey(){
        long now=System.nanoTime();if(now-lastRequest<250_000_000L||peer==null)return;lastRequest=now;
        try{byte[] p=Wire.keyRequest(token);socket.send(new DatagramPacket(p,p.length,peer));}catch(Exception ignored){}
    }
    private void receive(){
        Wire wire=new Wire(token);int previousVideo=0,previousAudio=0;boolean seenVideo=false,seenAudio=false;
        try(DatagramSocket s=new DatagramSocket(48000)){
            socket=s;s.setReceiveBufferSize(2*1024*1024);s.setSoTimeout(100);
            byte[] buffer=new byte[1201];long lastVideo=System.nanoTime();
            while(alive.get()){
                DatagramPacket p=new DatagramPacket(buffer,buffer.length);
                try{s.receive(p);}catch(SocketTimeoutException e){if(System.nanoTime()-lastVideo>500_000_000L){needKey.set(true);requestKey();}continue;}
                if(peer!=null&&!peer.equals(p.getSocketAddress()))continue;
                Wire.Frame f=wire.accept(buffer,p.getLength(),System.nanoTime());if(f==null)continue;
                peer=p.getSocketAddress();
                if(f.type==1){
                    if(seenVideo && Integer.compareUnsigned(f.seq,previousVideo)<=0)continue;
                    if(seenVideo && f.seq!=previousVideo+1)needKey.set(true);
                    previousVideo=f.seq;seenVideo=true;lastVideo=System.nanoTime();
                    if(needKey.get()){if(!f.key()){requestKey();continue;}video.clear();needKey.set(false);}
                    if(!video.offer(f)){video.clear();needKey.set(true);requestKey();}
                }else{
                    if(seenAudio&&Integer.compareUnsigned(f.seq,previousAudio)<=0)continue;
                    previousAudio=f.seq;seenAudio=true;
                    if(!audio.offer(f)){audio.clear();audio.offer(f);}
                }
            }
        }catch(Exception e){if(alive.get())status.accept("Network: "+e.getMessage());}
    }
    private static String hardwareDecoder(int w,int h){
        MediaFormat f=MediaFormat.createVideoFormat("video/avc",w,h);f.setInteger(MediaFormat.KEY_FRAME_RATE,60);
        for(MediaCodecInfo i:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
            if(i.isEncoder()||!i.isHardwareAccelerated())continue;
            try{if(i.getCapabilitiesForType("video/avc").isFormatSupported(f))return i.getName();}catch(IllegalArgumentException ignored){}
        }
        throw new IllegalStateException("No hardware H.264 decoder for "+w+"x"+h+" at 60 fps. Select a lower size on iPhone.");
    }
    private void decode(){
        MediaCodec codec=null;int width=0,height=0;boolean recovery=true;long shown=0;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        try{
            while(alive.get()){
                Wire.Frame f=video.poll(4,TimeUnit.MILLISECONDS);
                if(f!=null){
                    if(codec==null||width!=f.width||height!=f.height){
                        if(!f.key()){needKey.set(true);continue;}
                        if(codec!=null){codec.stop();codec.release();codec=null;}
                        width=f.width;height=f.height;String name=hardwareDecoder(width,height);codec=MediaCodec.createByCodecName(name);
                        MediaFormat format=MediaFormat.createVideoFormat("video/avc",width,height);format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,Wire.MAX);format.setInteger(MediaFormat.KEY_PRIORITY,0);format.setInteger(MediaFormat.KEY_FRAME_RATE,60);
                        boolean low=codec.getCodecInfo().getCapabilitiesForType("video/avc").isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
                        if(low)format.setInteger(MediaFormat.KEY_LOW_LATENCY,1);
                        codec.configure(format,surface,null,0);codec.start();recovery=false;
                        status.accept(width+"×"+height+" · "+name+" · low latency "+low);
                    }
                    if(recovery){if(!f.key())continue;codec.flush();recovery=false;}
                    int index=codec.dequeueInputBuffer(0);
                    if(index<0){needKey.set(true);recovery=true;video.clear();}
                    else{
                        ByteBuffer b=codec.getInputBuffer(index);
                        if(b==null||b.capacity()<f.data.length)throw new IllegalStateException("Decoder input buffer too small");
                        b.clear();b.put(f.data);codec.queueInputBuffer(index,0,f.data.length,f.pts,0);
                    }
                }
                if(codec!=null){int index;while((index=codec.dequeueOutputBuffer(info,0))>=0){codec.releaseOutputBuffer(index,true);shown++;}}
            }
        }catch(Exception e){if(alive.get())status.accept("Video: "+e.getMessage());}
        finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}}
    }
    private int mask(int channels){
        if(channels==6)return AudioFormat.CHANNEL_OUT_5POINT1;
        if(channels==8)return AudioFormat.CHANNEL_OUT_7POINT1_SURROUND;
        if(channels==12&&Build.VERSION.SDK_INT>=32)return AudioFormat.CHANNEL_OUT_7POINT1POINT4;
        return AudioFormat.CHANNEL_OUT_STEREO;
    }
    private AudioTrack makeTrack(int channels){
        int mask=mask(channels);if(Integer.bitCount(mask)!=channels)return null;
        AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        AudioFormat format=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(mask).build();
        // Public API does not expose proprietary car DSP paths. Only attempt advertised direct multichannel support.
        if(channels>2&&!AudioTrack.isDirectPlaybackSupported(format,attrs))return null;
        int minimum=AudioTrack.getMinBufferSize(48000,mask,AudioFormat.ENCODING_PCM_16BIT);if(minimum<=0)return null;
        AudioTrack track=new AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).setBufferSizeInBytes(Math.max(minimum,480*channels*2)).build();
        if(track.getState()!=AudioTrack.STATE_INITIALIZED){track.release();return null;}return track;
    }
    static byte[] downmix(byte[] input,int ch){
        if(ch==2)return input;
        ByteBuffer src=ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN),out=ByteBuffer.allocate(input.length/ch*2).order(ByteOrder.LITTLE_ENDIAN);
        float[] x=new float[ch];
        while(src.remaining()>=ch*2){
            for(int i=0;i<ch;i++)x[i]=src.getShort();
            float l=x[0]+.7071f*x[2]+.25f*x[3]+.7071f*x[4],r=x[1]+.7071f*x[2]+.25f*x[3]+.7071f*x[5];
            if(ch>=8){l+=.5f*x[6];r+=.5f*x[7];}
            if(ch==12){l+=.35f*(x[8]+x[10]);r+=.35f*(x[9]+x[11]);}
            out.putShort((short)Math.max(-32768,Math.min(32767,l*.4f)));out.putShort((short)Math.max(-32768,Math.min(32767,r*.4f)));
        }
        return out.array();
    }
    private void play(){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        AudioTrack track=null;int sourceChannels=0,outputChannels=0;long written=0;
        try{
            while(alive.get()){
                Wire.Frame f=audio.poll(100,TimeUnit.MILLISECONDS);if(f==null)continue;
                if(track==null||sourceChannels!=f.channels){
                    if(track!=null){track.pause();track.flush();track.release();}
                    sourceChannels=f.channels;outputChannels=stereo?2:sourceChannels;track=makeTrack(outputChannels);
                    if(track==null){outputChannels=2;track=makeTrack(2);}if(track==null)throw new IllegalStateException("No PCM output");
                    written=0;track.play();status.accept("Audio: source "+sourceChannels+" → output "+outputChannels+" channels (PCM, not Atmos)");
                }
                long played=track.getPlaybackHeadPosition()&0xffffffffL;
                if(written-played>2880){track.pause();track.flush();written=0;track.play();audio.clear();}
                byte[] data=outputChannels==2?downmix(f.data,sourceChannels):f.data;
                int offset=0;
                while(offset<data.length&&alive.get()){int n=track.write(data,offset,data.length-offset,AudioTrack.WRITE_NON_BLOCKING);if(n<0)throw new IllegalStateException("AudioTrack write "+n);if(n==0)break;offset+=n;written+=n/(outputChannels*2);}
            }
        }catch(Exception e){if(alive.get())status.accept("Audio: "+e.getMessage());}
        finally{if(track!=null){try{track.pause();track.flush();}catch(Exception ignored){}track.release();}}
    }
    @Override public void close(){alive.set(false);DatagramSocket s=socket;if(s!=null)s.close();network.interrupt();decoder.interrupt();sound.interrupt();try{network.join(500);decoder.join(500);sound.join(500);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
