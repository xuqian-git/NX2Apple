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
    private final Consumer<int[]> dimensions;
    private final Consumer<String> overlay;
    private final AudioManager audioManager;
    private final Stats stats=new Stats();
    private final ArrayBlockingQueue<Wire.Frame> video=new ArrayBlockingQueue<>(3),audio=new ArrayBlockingQueue<>(12);
    private final AtomicBoolean alive=new AtomicBoolean(true),needKey=new AtomicBoolean(true);
    private final Thread network,decoder,sound,ticker;
    private volatile DatagramSocket socket;
    private volatile SocketAddress peer;
    private long lastRequest;
    Receiver(Surface surface,long token,boolean stereo,Consumer<String> status,Consumer<int[]> dimensions,Consumer<String> overlay,AudioManager audioManager) {
        this.audioManager=audioManager;this.surface=surface;this.token=token;this.stereo=stereo;this.status=status;this.dimensions=dimensions;this.overlay=overlay;
        network=new Thread(this::receive,"nx-receive");decoder=new Thread(this::decode,"nx-decode");sound=new Thread(this::play,"nx-audio");ticker=new Thread(this::report,"nx-stats");
        network.start();decoder.start();sound.start();ticker.start();
    }
    private void report(){
        long last=System.nanoTime();
        while(alive.get()){
            try{Thread.sleep(1000);}catch(InterruptedException e){return;}
            long now=System.nanoTime();
            if(peer==null){last=now;continue;}
            overlay.accept(stats.tick((now-last)/1e9));last=now;
        }
    }
    private void requestKey(String reason){
        long now=System.nanoTime();if(now-lastRequest<250_000_000L||peer==null)return;lastRequest=now;
        try{byte[] p=Wire.keyRequest(token);socket.send(new DatagramPacket(p,p.length,peer));stats.keyRequest(reason);}catch(Exception ignored){}
    }
    private void receive(){
        Wire wire=new Wire(token);int previousVideo=0,previousAudio=0;boolean seenVideo=false,seenAudio=false;
        try(DatagramSocket s=new DatagramSocket(48000)){
            socket=s;s.setReceiveBufferSize(2*1024*1024);s.setSoTimeout(100);
            byte[] buffer=new byte[1201];long lastVideo=System.nanoTime(),lastPing=0;int lostVideo=0;
            while(alive.get()){
                long tick=System.nanoTime();
                // Clock probe for end-to-end latency. Older senders ignore it.
                if(peer!=null&&tick-lastPing>=500_000_000L){lastPing=tick;try{byte[] q=Wire.ping(token,tick);s.send(new DatagramPacket(q,q.length,peer));}catch(Exception ignored){}}
                DatagramPacket p=new DatagramPacket(buffer,buffer.length);
                try{s.receive(p);}catch(SocketTimeoutException e){
                    long idle=System.nanoTime()-lastVideo;
                    if(idle>500_000_000L){needKey.set(true);requestKey("idle");}
                    if(idle>2_000_000_000L&&peer!=null){peer=null;seenVideo=false;seenAudio=false;video.clear();audio.clear();wire=new Wire(token);lostVideo=0;stats.reset();status.accept("Waiting for sender to reconnect…");}
                    continue;
                }
                if(peer!=null&&!peer.equals(p.getSocketAddress()))continue;
                long now=System.nanoTime();
                if(peer!=null&&p.getLength()==Wire.PONG_SIZE){Wire.Pong o=Wire.pong(buffer,p.getLength(),token);if(o!=null){stats.probe(o,now);continue;}}
                Wire.Frame f=wire.accept(buffer,p.getLength(),now);
                if(wire.droppedVideo!=lostVideo){lostVideo=wire.droppedVideo;stats.expired(wire.lostSeq,wire.lostGot,wire.lostCount,now);}
                if(peer!=null||f!=null)stats.packet(p.getLength());
                if(f==null)continue;
                peer=p.getSocketAddress();
                if(f.type==1){
                    if(seenVideo && Integer.compareUnsigned(f.seq,previousVideo)<=0)continue;
                    if(seenVideo && f.seq!=previousVideo+1){needKey.set(true);stats.gap(previousVideo,f.seq,now);}
                    previousVideo=f.seq;seenVideo=true;lastVideo=now;
                    stats.frame(f,now);
                    if(needKey.get()){if(!f.key()){stats.awaitingKey();requestKey("await");continue;}video.clear();needKey.set(false);}
                    if(!video.offer(f)){video.clear();needKey.set(true);stats.overflow(now);requestKey("overflow");}
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
    // Vendor low-latency switches for decoders that do not advertise FEATURE_LowLatency. Unknown keys are ignored.
    // Qualcomm (verified on a SA8155 car): without them c2.qti.avc.decoder stalls 200-350 ms on every IDR and
    // holds frames for about 50 ms. MediaTek and Exynos keys are the same switch on those decoders (not device-tested).
    private static final String[] VENDOR_LOW_LATENCY={"vendor.qti-ext-dec-low-latency.enable","vendor.qti-ext-dec-picture-order.enable","vendor.low-latency.enable","vendor.rtc-ext-dec-low-latency.enable"};
    private MediaCodec openDecoder(String name,int width,int height,boolean vendor) throws java.io.IOException{
        MediaCodec codec=MediaCodec.createByCodecName(name);
        MediaFormat format=MediaFormat.createVideoFormat("video/avc",width,height);format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,Wire.MAX);format.setInteger(MediaFormat.KEY_PRIORITY,0);format.setInteger(MediaFormat.KEY_FRAME_RATE,60);
        if(codec.getCodecInfo().getCapabilitiesForType("video/avc").isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))format.setInteger(MediaFormat.KEY_LOW_LATENCY,1);
        if(vendor)for(String key:VENDOR_LOW_LATENCY)format.setInteger(key,1);
        try{codec.configure(format,surface,null,0);codec.start();return codec;}
        catch(RuntimeException e){codec.release();throw e;}
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
                        width=f.width;height=f.height;dimensions.accept(new int[]{width,height});String name=hardwareDecoder(width,height);
                        boolean vendor=true;
                        try{codec=openDecoder(name,width,height,true);}
                        catch(RuntimeException e){android.util.Log.w(Stats.TAG,"decoder rejected vendor low-latency keys, retrying without: "+e);vendor=false;codec=openDecoder(name,width,height,false);}
                        boolean low=codec.getCodecInfo().getCapabilitiesForType("video/avc").isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
                        recovery=false;
                        android.util.Log.i(Stats.TAG,"decoder "+name+" "+width+"x"+height+" standard low latency "+low+" vendor keys "+vendor);
                        status.accept(width+"×"+height+" · "+name+" · low latency "+(low?"standard":vendor?"vendor keys":"off"));
                    }
                    if(recovery){if(!f.key()){stats.awaitingKey();continue;}codec.flush();recovery=false;}
                    int index=codec.dequeueInputBuffer(0);
                    if(index<0){needKey.set(true);recovery=true;video.clear();stats.decoderBusy(System.nanoTime());}
                    else{
                        ByteBuffer b=codec.getInputBuffer(index);
                        if(b==null||b.capacity()<f.data.length)throw new IllegalStateException("Decoder input buffer too small");
                        b.clear();b.put(f.data);codec.queueInputBuffer(index,0,f.data.length,f.pts,0);stats.queued(f.pts,System.nanoTime());
                    }
                }
                if(codec!=null){int index;while((index=codec.dequeueOutputBuffer(info,0))>=0){codec.releaseOutputBuffer(index,true);stats.shown(info.presentationTimeUs,System.nanoTime());shown++;}}
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
        AudioTrack track=new AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).setBufferSizeInBytes(Math.max(minimum,2*AUDIO_BACKLOG_FRAMES*channels*2)).build();
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
    // Game audio arrives in bursts of several thousand frames. Above this backlog new blocks are dropped. Flushing the
    // track instead (the old 60 ms rule) fired every 170-250 ms on a car and left almost no audible sound.
    static final int AUDIO_BACKLOG_FRAMES=5760; // 120 ms at 48 kHz
    // The track holds twice that, so a whole burst fits and a non-blocking write is not cut short.
    private static final AudioAttributes GAME_AUDIO=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    private void play(){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        AudioTrack track=null;int sourceChannels=0,outputChannels=0;long written=0,lastPlayed=0;
        // Car audio policies may duck or mute players without focus, so take it with the first audio and hold it until close.
        AudioFocusRequest focus=null;
        try{
            while(alive.get()){
                Wire.Frame f=audio.poll(100,TimeUnit.MILLISECONDS);if(f==null)continue;
                stats.audioBlock();
                if(focus==null){
                    focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(GAME_AUDIO)
                        .setOnAudioFocusChangeListener(change->android.util.Log.w(Stats.TAG,"AUDIO_FOCUS change "+change)).build();
                    android.util.Log.i(Stats.TAG,"audio focus request result "+audioManager.requestAudioFocus(focus));
                }
                if(track==null||sourceChannels!=f.channels){
                    if(track!=null){track.pause();track.flush();track.release();track=null;}
                    sourceChannels=f.channels;outputChannels=stereo?2:sourceChannels;
                    try{track=makeTrack(outputChannels);if(track==null){outputChannels=2;track=makeTrack(2);}}catch(RuntimeException e){android.util.Log.w(Stats.TAG,"AUDIO_TRACK create failed: "+e);track=null;}
                    if(track==null){status.accept("Audio: no PCM output, retrying");Thread.sleep(500);sourceChannels=0;continue;}
                    written=0;lastPlayed=0;track.play();status.accept("Audio: source "+sourceChannels+" → output "+outputChannels+" channels (PCM, not Atmos)");
                    android.util.Log.i(Stats.TAG,"audio track "+sourceChannels+"->"+outputChannels+" channels, buffer "+track.getBufferSizeInFrames()+" frames");
                }
                long played=track.getPlaybackHeadPosition()&0xffffffffL;
                if(played>=lastPlayed)stats.audioHead(played-lastPlayed,written-played);lastPlayed=played;
                if(written-played>AUDIO_BACKLOG_FRAMES){stats.audioDrop();continue;}
                byte[] data=outputChannels==2?downmix(f.data,sourceChannels):f.data;
                int offset=0,n=0;
                while(offset<data.length&&alive.get()){n=track.write(data,offset,data.length-offset,AudioTrack.WRITE_NON_BLOCKING);if(n<=0)break;offset+=n;written+=n/(outputChannels*2);}
                if(n<0){
                    // A dead or invalidated track (route change, audio server restart) is rebuilt with the next block.
                    android.util.Log.w(Stats.TAG,"AUDIO_TRACK write error "+n+", recreating");stats.audioReset();
                    try{track.release();}catch(Exception ignored){}track=null;
                }else if(offset<data.length)stats.audioDrop();
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(Exception e){if(alive.get())status.accept("Audio: "+e.getMessage());}
        finally{if(track!=null){try{track.pause();track.flush();}catch(Exception ignored){}track.release();}if(focus!=null)audioManager.abandonAudioFocusRequest(focus);}
    }
    @Override public void close(){alive.set(false);DatagramSocket s=socket;if(s!=null)s.close();network.interrupt();decoder.interrupt();sound.interrupt();ticker.interrupt();try{network.join(500);decoder.join(500);sound.join(500);ticker.join(500);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
