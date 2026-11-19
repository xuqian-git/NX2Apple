package dev.nxstream;

import android.util.Log;
import java.util.*;

/**
 * Receiver diagnostics. Counters cover the current one-second window; tick() renders the overlay
 * text, writes one logcat line (tag NXStats) and starts a new window. Stall causes are also logged
 * the moment they happen so a freeze can be matched to what preceded it.
 *
 * Latency uses the sender's capture timestamp. The sender clock is mapped onto System.nanoTime()
 * with the lowest-round-trip recent NXPG/NXPO probe, so the error is at most half that round trip.
 */
final class Stats {
    static final String TAG="NXStats";
    static final long FREEZE_NS=100_000_000L;

    private static final class Window {
        long sum, max; int n;
        void add(long v){sum+=v;if(n==0||v>max)max=v;n++;}
        String avg(){return n==0?"-":String.valueOf(Math.round(sum/(double)n/1000));}
        String peak(){return n==0?"-":String.valueOf(Math.round(max/1000.0));}
        void reset(){sum=max=0;n=0;}
    }

    // Clock mapping: offsetUs = sender micros - receiver micros at the same instant.
    private final long[] probeRtt=new long[16], probeOffset=new long[16];
    private int probes;
    private long offsetUs, rttUs;
    private boolean clock;

    // Current window.
    private long packets, bytes, framesRx, keyframes, shown, keyRequests, gaps, expired, overflow, busy, awaitingKey, freezes, maxGapNs;
    private final Window e2e=new Window(), net=new Window(), queue=new Window(), decode=new Window();
    private String keyReasons="";
    private long audioBlocks, audioHead, audioResets, audioDrops, audioBacklog;
    // Session totals.
    private long totalFreezes, totalFreezeMs, totalPackets;
    private long lastShownNs;
    private String lastCause="none";
    private long lastCauseNs;

    // Sender counters from the latest probe reply, and loss estimated between replies.
    private Wire.Pong pong;
    private long pongPackets;
    private long lossPackets, sentPackets, sendErrors, skipped, encodeMax;
    private boolean senderStats;

    private final Map<Long,Long> arrival=new LinkedHashMap<>(), queued=new LinkedHashMap<>();

    synchronized void reset(){
        probes=0;clock=false;senderStats=false;pong=null;lastShownNs=0;totalPackets=0;arrival.clear();queued.clear();
        lastCause="none";
    }

    /** Called for every datagram accepted from the current peer. */
    synchronized void packet(int size){packets++;totalPackets++;bytes+=size;}

    synchronized void probe(Wire.Pong p,long nowNs){
        long sent=p.echo, rtt=(nowNs-sent)/1000;
        if(rtt<0||rtt>1_000_000)return;
        int i=probes++%probeRtt.length;
        probeRtt[i]=rtt;probeOffset[i]=p.senderMicros-(sent+nowNs)/2000;
        int best=0,count=Math.min(probes,probeRtt.length);
        for(int k=1;k<count;k++)if(probeRtt[k]<probeRtt[best])best=k;
        offsetUs=probeOffset[best];rttUs=rtt;clock=true;
        if(pong!=null){
            long sentDelta=(p.packetsSent-pong.packetsSent)&0xffffffffL, recvDelta=totalPackets-pongPackets;
            sentPackets+=sentDelta;lossPackets+=Math.max(0,sentDelta-recvDelta);
            sendErrors+=(p.sendErrors-pong.sendErrors)&0xffffffffL;skipped+=(p.skipped-pong.skipped)&0xffffffffL;
            if(p.sendErrors!=pong.sendErrors)cause("SEND_ERROR sender send() failed "+((p.sendErrors-pong.sendErrors)&0xffffffffL)+"x errno "+p.lastErrno,nowNs);
        }
        encodeMax=Math.max(encodeMax,p.encodeMaxMicros&0xffffffffL);
        pong=p;pongPackets=totalPackets;senderStats=true;
    }

    /** A complete video access unit left reassembly. */
    synchronized void frame(Wire.Frame f,long nowNs){
        framesRx++;
        if(f.key())keyframes++;
        if(clock)net.add(nowNs/1000+offsetUs-f.pts);
        arrival.put(f.pts,nowNs);trim(arrival);
    }
    synchronized void queued(long pts,long nowNs){
        Long a=arrival.remove(pts);
        if(a!=null)queue.add((nowNs-a)/1000);
        queued.put(pts,nowNs);trim(queued);
    }
    synchronized void shown(long pts,long nowNs){
        shown++;
        Long q=queued.remove(pts);
        if(q!=null)decode.add((nowNs-q)/1000);
        if(clock)e2e.add(nowNs/1000+offsetUs-pts);
        if(lastShownNs!=0){
            long gap=nowNs-lastShownNs;
            if(gap>maxGapNs)maxGapNs=gap;
            if(gap>=FREEZE_NS){
                freezes++;totalFreezes++;totalFreezeMs+=gap/1_000_000;
                long since=(nowNs-lastCauseNs)/1_000_000;
                Log.w(TAG,"FREEZE "+gap/1_000_000+" ms; last cause "+(lastCauseNs==0?"none":lastCause+" ("+since+" ms before resume)"));
            }
        }
        lastShownNs=nowNs;
    }
    private static void trim(Map<Long,Long> m){Iterator<Long> it=m.keySet().iterator();while(m.size()>64&&it.hasNext()){it.next();it.remove();}}

    private void cause(String what,long nowNs){lastCause=what;lastCauseNs=nowNs;Log.w(TAG,what);}
    synchronized void gap(int previous,int seq,long nowNs){gaps++;cause("SEQ_GAP video "+previous+" -> "+seq+" ("+(seq-previous-1)+" missing)",nowNs);}
    synchronized void expired(int seq,int got,int count,long nowNs){expired++;cause("INCOMPLETE video seq "+seq+" got "+got+"/"+count+" fragments",nowNs);}
    synchronized void overflow(long nowNs){overflow++;cause("QUEUE_OVERFLOW video queue full",nowNs);}
    synchronized void decoderBusy(long nowNs){busy++;cause("DECODER_BUSY no input buffer; flushing for keyframe",nowNs);}
    synchronized void awaitingKey(){awaitingKey++;}
    synchronized void audioBlock(){audioBlocks++;}
    synchronized void audioHead(long advanced,long backlog){audioHead+=advanced;audioBacklog=Math.max(audioBacklog,backlog);}
    synchronized void audioReset(){audioResets++;}
    synchronized void audioDrop(){audioDrops++;}
    synchronized void keyRequest(String reason){keyRequests++;if(!keyReasons.contains(reason))keyReasons+=(keyReasons.isEmpty()?"":",")+reason;}

    /** Ends the window. Returns the overlay text. */
    synchronized String tick(double seconds){
        double mbps=bytes*8/seconds/1e6;
        String sender;
        if(!senderStats)sender="sender: no stats (older iPhone build)";
        else sender=String.format(Locale.ROOT,"sender: send errors %d (errno %d) · busy skips %d · capture→encoded max %.1f ms",
            sendErrors,pong.lastErrno,skipped,encodeMax/1000.0);
        String latency=clock
            ?String.format(Locale.ROOT,"E2E %s ms (max %s) = capture→rx %s + queue %s + decode %s",e2e.avg(),e2e.peak(),net.avg(),queue.avg(),decode.avg())
            :"E2E n/a (sender does not answer clock probes)";
        String text=latency+"\n"
            +String.format(Locale.ROOT,"RTT %.1f ms · rx %d fps · shown %d fps · %.1f Mbps · net loss %d/%d pkts",rttUs/1000.0,framesRx,shown,mbps,lossPackets,sentPackets)+"\n"
            +String.format(Locale.ROOT,"freezes %d (longest frame gap %d ms) · session %d / %.1f s",freezes,maxGapNs/1_000_000,totalFreezes,totalFreezeMs/1000.0)+"\n"
            +String.format(Locale.ROOT,"incomplete %d · seq gaps %d · overflow %d · decoder busy %d · dropped awaiting key %d · key req %d%s",
                expired,gaps,overflow,busy,awaitingKey,keyRequests,keyReasons.isEmpty()?"":" ("+keyReasons+")")+"\n"
            +sender;
        Log.i(TAG,String.format(Locale.ROOT,
            "e2e=%s/%s net=%s/%s queue=%s decode=%s/%s rtt=%.1f rx=%d key=%d shown=%d mbps=%.1f loss=%d/%d freeze=%d maxgap=%d incomplete=%d gaps=%d overflow=%d busy=%d awaitkey=%d keyreq=%d%s snd_err=%s errno=%s snd_skip=%s enc_max=%s audio_blocks=%d audio_played=%d audio_backlog_max=%d audio_resets=%d audio_drops=%d",
            e2e.avg(),e2e.peak(),net.avg(),net.peak(),queue.avg(),decode.avg(),decode.peak(),rttUs/1000.0,framesRx,keyframes,shown,mbps,lossPackets,sentPackets,
            freezes,maxGapNs/1_000_000,expired,gaps,overflow,busy,awaitingKey,keyRequests,keyReasons.isEmpty()?"":"("+keyReasons+")",
            senderStats?String.valueOf(sendErrors):"-",senderStats?String.valueOf(pong.lastErrno):"-",
            senderStats?String.valueOf(skipped):"-",senderStats?String.format(Locale.ROOT,"%.1f",encodeMax/1000.0):"-",
            audioBlocks,audioHead,audioBacklog,audioResets,audioDrops));
        audioBlocks=audioHead=audioResets=audioDrops=audioBacklog=0;
        packets=bytes=framesRx=keyframes=shown=keyRequests=gaps=expired=overflow=busy=awaitingKey=freezes=maxGapNs=0;
        lossPackets=sentPackets=sendErrors=skipped=encodeMax=0;keyReasons="";
        e2e.reset();net.reset();queue.reset();decode.reset();
        return text;
    }
}
