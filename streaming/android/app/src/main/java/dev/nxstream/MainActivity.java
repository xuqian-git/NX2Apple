package dev.nxstream;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.media.*;
import java.net.*;
import java.util.*;

public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView surface;
    private TextView status;
    private LinearLayout controls;
    private Discovery discovery;
    private CheckBox stereo,showStats;
    private TextView stats;
    private Receiver receiver;
    private boolean ready;
    private int videoWidth=16,videoHeight=9;
    private final ArrayDeque<String> messages=new ArrayDeque<>();
    private String ipAddresses(){
        StringBuilder b=new StringBuilder();
        try{for(NetworkInterface n:Collections.list(NetworkInterface.getNetworkInterfaces()))for(InetAddress a:Collections.list(n.getInetAddresses()))if(a instanceof Inet4Address&&!a.isLoopbackAddress())b.append(a.getHostAddress()).append("  ");}catch(Exception ignored){}
        return b.toString();
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root=new FrameLayout(this);surface=new SurfaceView(this);surface.getHolder().addCallback(this);
        // Preserve 16:9 on receivers whose actual viewport has a different shape.
        root.addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->fitSurface());
        controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);controls.setPadding(24,16,24,16);controls.setBackgroundColor(0xdd161616);
        TextView title=new TextView(this);title.setText("NXStream prototype · UDP 48000\nReceiver IP: "+ipAddresses());controls.addView(title);
        stereo=new CheckBox(this);stereo.setText("Force stereo (use if car multichannel is silent)");controls.addView(stereo);
        stats=new TextView(this);stats.setTypeface(android.graphics.Typeface.MONOSPACE);stats.setTextSize(11);stats.setTextColor(0xffffffff);stats.setBackgroundColor(0x99000000);stats.setPadding(12,8,12,8);stats.setVisibility(View.GONE);
        showStats=new CheckBox(this);showStats.setText("Show latency and stall statistics on the picture");showStats.setChecked(getPreferences(0).getBoolean("showStats",true));
        showStats.setOnCheckedChangeListener((v,on)->{getPreferences(0).edit().putBoolean("showStats",on).apply();if(!on)stats.setVisibility(View.GONE);});controls.addView(showStats);
        TextView capability=new TextView(this);StringBuilder caps=new StringBuilder("Audio devices (reported, not verified):\n");
        for(AudioDeviceInfo d:((AudioManager)getSystemService(AUDIO_SERVICE)).getDevices(AudioManager.GET_DEVICES_OUTPUTS))caps.append(d.getProductName()).append(" channels ").append(Arrays.toString(d.getChannelCounts())).append('\n');
        capability.setText(caps);controls.addView(capability);
        Button start=new Button(this);start.setText("Start receiving / restart");start.setOnClickListener(v->start());controls.addView(start);
        status=new TextView(this);status.setText("Open Streaming in the iPhone app settings and select this receiver.\nTrusted LAN only; this prototype does not encrypt media.");controls.addView(status);
        Button hide=new Button(this);hide.setText("Hide controls (tap picture to show)");hide.setOnClickListener(v->controls.setVisibility(View.GONE));controls.addView(hide);
        ScrollView scroll=new ScrollView(this);scroll.addView(controls);root.addView(scroll,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        root.addView(stats,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START));
        surface.setOnClickListener(v->controls.setVisibility(View.VISIBLE));setContentView(root);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    private void fitSurface(){
        View parent=(View)surface.getParent();int w=parent.getWidth(),h=parent.getHeight();
        int sw=Math.min(w,(int)((long)h*videoWidth/videoHeight)),sh=(int)((long)sw*videoHeight/videoWidth);
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)surface.getLayoutParams();
        if(p.width!=sw||p.height!=sh){p.width=sw;p.height=sh;surface.setLayoutParams(p);}
    }
    private void start(){
        if(!ready)return;
        if(receiver!=null)receiver.close();messages.clear();
        receiver=new Receiver(surface.getHolder().getSurface(),1L,stereo.isChecked(),message->runOnUiThread(()->{if(status!=null){messages.addLast(message);while(messages.size()>4)messages.removeFirst();status.setText(String.join("\n",messages));}}),size->runOnUiThread(()->{videoWidth=size[0];videoHeight=size[1];fitSurface();}),
            text->runOnUiThread(()->{if(stats!=null&&showStats.isChecked()){stats.setText(text);stats.setVisibility(View.VISIBLE);}}));status.setText("Listening on UDP 48000…");
    }
    @Override public void surfaceCreated(SurfaceHolder h){ready=true;start();}
    @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int height){}
    @Override public void surfaceDestroyed(SurfaceHolder h){ready=false;if(receiver!=null){receiver.close();receiver=null;}}
    @Override protected void onStart(){super.onStart();discovery=new Discovery(this);if(ready)start();}
    @Override protected void onStop(){super.onStop();if(discovery!=null){discovery.close();discovery=null;}if(receiver!=null){receiver.close();receiver=null;}}
}
