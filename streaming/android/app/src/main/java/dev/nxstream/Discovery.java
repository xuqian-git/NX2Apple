package dev.nxstream;

import android.app.Activity;
import android.net.nsd.*;
import android.os.Build;
import android.media.*;
import android.graphics.Rect;
import org.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bonjour plus unicast discovery on connected LANs, including phone and receiver hotspots. */
final class Discovery implements AutoCloseable {
    static final int PORT=48001;
    private final NsdManager nsd;
    private final Thread thread;
    private volatile DatagramSocket socket;
    private volatile boolean alive=true;
    private volatile boolean registered;
    private final NsdManager.RegistrationListener listener=new NsdManager.RegistrationListener(){
        public void onServiceRegistered(NsdServiceInfo s){registered=true;if(!alive)unregister();}
        public void onRegistrationFailed(NsdServiceInfo s,int e){}
        public void onServiceUnregistered(NsdServiceInfo s){registered=false;}
        public void onUnregistrationFailed(NsdServiceInfo s,int e){}
    };
    Discovery(Activity activity){
        nsd=activity.getSystemService(NsdManager.class);
        Rect bounds=activity.getWindowManager().getCurrentWindowMetrics().getBounds();
        String id=activity.getPreferences(0).getString("receiverID",null);
        if(id==null){id=UUID.randomUUID().toString();activity.getPreferences(0).edit().putString("receiverID",id).apply();}
        byte[] reply=describe(bounds.width(),bounds.height(),id);
        thread=new Thread(()->{
            try(DatagramSocket s=new DatagramSocket(PORT)){
                socket=s;s.setSoTimeout(500);
                NsdServiceInfo service=new NsdServiceInfo();service.setServiceName("NXStream "+Build.MODEL);service.setServiceType("_nxstream._udp.");service.setPort(PORT);
                try{nsd.registerService(service,NsdManager.PROTOCOL_DNS_SD,listener);}catch(Exception ignored){}
                while(alive){
                    byte[] bytes=new byte[64];DatagramPacket p=new DatagramPacket(bytes,bytes.length);
                    try{s.receive(p);}catch(SocketTimeoutException e){continue;}
                    if(p.getLength()==8&&new String(bytes,0,8,StandardCharsets.US_ASCII).equals("NXDISC01"))s.send(new DatagramPacket(reply,reply.length,p.getSocketAddress()));
                }
            }catch(Exception ignored){}finally{unregister();}
        },"nx-discovery");thread.start();
    }
    private static boolean supports(int w,int h){
        MediaFormat format=MediaFormat.createVideoFormat("video/avc",w,h);format.setInteger(MediaFormat.KEY_FRAME_RATE,60);
        for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
            if(info.isEncoder()||!info.isHardwareAccelerated())continue;
            try{if(info.getCapabilitiesForType("video/avc").isFormatSupported(format))return true;}catch(IllegalArgumentException ignored){}
        }
        return false;
    }
    private static byte[] describe(int width,int height,String id){
        JSONObject data=new JSONObject();JSONArray modes=new JSONArray();Set<String> seen=new LinkedHashSet<>();
        // Preserve the display's aspect ratio, reducing size when hardware cannot decode at 60 fps.
        for(double scale:new double[]{1,.875,.75,.625,.5,.375,.25}){
            int w=((int)(width*scale))&~1,h=((int)(height*scale))&~1;
            if(w>=320&&h>=240&&w<=4096&&h<=4096&&(long)w*h<=4096L*2160&&supports(w,h))seen.add(w+"x"+h);
        }
        for(int[] size:new int[][]{{2880,1620},{2560,1440},{1920,1080},{1280,720}})if(supports(size[0],size[1]))seen.add(size[0]+"x"+size[1]);
        for(String mode:seen)modes.put(mode);
        try{data.put("protocol","NXDISC01");data.put("id",id);data.put("name","NXStream "+Build.MODEL);data.put("width",width);data.put("height",height);data.put("modes",modes);data.put("mediaPort",48000);}catch(JSONException ignored){}
        return data.toString().getBytes(StandardCharsets.UTF_8);
    }
    private void unregister(){if(registered){registered=false;try{nsd.unregisterService(listener);}catch(Exception ignored){}}}
    public void close(){alive=false;DatagramSocket s=socket;if(s!=null)s.close();unregister();}
}
