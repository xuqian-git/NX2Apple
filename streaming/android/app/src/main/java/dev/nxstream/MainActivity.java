package dev.nxstream;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.media.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;

public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView surface;
    private TextView status;
    private LinearLayout controls;
    private EditText token;
    private CheckBox stereo;
    private Receiver receiver;
    private boolean ready;
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
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{int w=r-l,h=b-t;int sw=Math.min(w,h*16/9),sh=sw*9/16;FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)surface.getLayoutParams();if(p.width!=sw||p.height!=sh){p.width=sw;p.height=sh;surface.setLayoutParams(p);}});
        controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);controls.setPadding(24,16,24,16);controls.setBackgroundColor(0xdd161616);
        TextView title=new TextView(this);title.setText("NXStream prototype · UDP 48000\nReceiver IP: "+ipAddresses());controls.addView(title);
        token=new EditText(this);token.setSingleLine(true);token.setHint("16-digit hex session token");
        String saved=getPreferences(0).getString("token",null);if(saved==null){long v;do{v=new SecureRandom().nextLong();}while(v==0);saved=String.format(Locale.ROOT,"%016x",v);}token.setText(saved);controls.addView(token);
        stereo=new CheckBox(this);stereo.setText("Force stereo (use if car multichannel is silent)");controls.addView(stereo);
        TextView capability=new TextView(this);StringBuilder caps=new StringBuilder("Audio devices (reported, not verified):\n");
        for(AudioDeviceInfo d:((AudioManager)getSystemService(AUDIO_SERVICE)).getDevices(AudioManager.GET_DEVICES_OUTPUTS))caps.append(d.getProductName()).append(" channels ").append(Arrays.toString(d.getChannelCounts())).append('\n');
        capability.setText(caps);controls.addView(capability);
        Button start=new Button(this);start.setText("Start receiving / restart");start.setOnClickListener(v->start());controls.addView(start);
        status=new TextView(this);status.setText("Select 1920×1080, 2560×1440 or 2880×1620 in the iPhone env.txt.\nTrusted LAN only; this prototype does not encrypt media.");controls.addView(status);
        Button hide=new Button(this);hide.setText("Hide controls (tap picture to show)");hide.setOnClickListener(v->controls.setVisibility(View.GONE));controls.addView(hide);
        ScrollView scroll=new ScrollView(this);scroll.addView(controls);root.addView(scroll,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        surface.setOnClickListener(v->controls.setVisibility(View.VISIBLE));setContentView(root);
    }
    private void start(){
        if(!ready)return;
        String s=token.getText().toString().trim();if(!s.matches("[0-9a-fA-F]{16}")||s.equals("0000000000000000")){status.setText("Enter exactly 16 hex digits, not all zero.");return;}
        if(receiver!=null)receiver.close();getPreferences(0).edit().putString("token",s).apply();
        receiver=new Receiver(surface.getHolder().getSurface(),Long.parseUnsignedLong(s,16),stereo.isChecked(),message->runOnUiThread(()->{if(status!=null)status.setText(message);}));status.setText("Listening on UDP 48000…");
    }
    @Override public void surfaceCreated(SurfaceHolder h){ready=true;}
    @Override public void surfaceChanged(SurfaceHolder h,int f,int w,int height){}
    @Override public void surfaceDestroyed(SurfaceHolder h){ready=false;if(receiver!=null){receiver.close();receiver=null;}}
    @Override protected void onStop(){super.onStop();if(receiver!=null){receiver.close();receiver=null;}}
}
