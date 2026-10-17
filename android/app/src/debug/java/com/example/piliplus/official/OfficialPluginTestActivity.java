package com.example.piliplus.official;

import android.app.Activity;
import android.content.*;
import android.os.*;
import io.flutter.plugin.common.*;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Controlled Binder ownership and deferred-open checks; real Flutter rendering is checked separately. */
public final class OfficialPluginTestActivity extends Activity {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final List<ServiceConnection> bindings=new ArrayList<>();
    private final List<Peer> peers=new ArrayList<>();
    private final ComponentName component=new ComponentName("owned.fixture","Playback");
    private OfficialPlayerPlugin plugin;
    private boolean finished;
    private String scenario;
    private int errors,openErrors,oldMessages;
    private final class Peer {
        int messages,opens;
        final Messenger messenger=new Messenger(new Handler(Looper.getMainLooper(),message->{
            if(message.what==OfficialPlaybackService.TERMINATE){
                if(scenario.equals("command_timeout"))bindings.get(0).onServiceDisconnected(component);
                return true;
            }
            Bundle data=message.getData();messages++;
            if("open".equals(data.getString("op"))) {
                opens++;
                if(scenario.equals("command_timeout") || scenario.equals("native_exit")) {
                    Bundle stage=new Bundle();stage.putLong("session",data.getLong("session"));stage.putString("event","preparation_stage");stage.putString("stage","graph_adapter");
                    Message event=Message.obtain(null,OfficialPlaybackService.EVENT);event.setData(stage);
                    try{message.replyTo.send(event);}catch(RemoteException e){finishResult(false,"stage_failed");}
                    ui.postDelayed(()->{
                        try {
                            if(scenario.equals("native_exit")){bindings.get(0).onServiceDisconnected(component);return;}
                            Map<?,?> pending=(Map<?,?>)field("pending");
                            Object operation=pending.values().iterator().next();
                            Field timeout=operation.getClass().getDeclaredField("timeout");timeout.setAccessible(true);((Runnable)timeout.get(operation)).run();
                        }catch(Exception e){finishResult(false,e.toString());}
                    },100);
                    return true;
                }
            }
            Bundle reply=new Bundle();reply.putLong("session",data.getLong("session"));reply.putBoolean("ok",true);
            Message out=Message.obtain(null,OfficialPlaybackService.REPLY,message.arg1,0);out.setData(reply);
            try{message.replyTo.send(out);}catch(RemoteException e){finishResult(false,"reply_failed");}
            return true;
        }));
    }
    private Object field(String name)throws Exception {Field f=OfficialPlayerPlugin.class.getDeclaredField(name);f.setAccessible(true);return f.get(plugin);}
    private void field(String name,Object value)throws Exception {Field f=OfficialPlayerPlugin.class.getDeclaredField(name);f.setAccessible(true);f.set(plugin,value);}
    private long session()throws Exception{return (Long)field("session");}
    private void call(String name,Object args,Consumer<Object> success){
        String arch=System.getProperty("os.arch");
        try{
            System.setProperty("os.arch","aarch64");
            plugin.onMethodCall(new MethodCall(name,args),new MethodChannel.Result(){
                public void success(Object value){success.accept(value);}
                public void error(String code,String message,Object details){
                    if(name.equals("open") && (scenario.equals("command_timeout") || scenario.equals("native_exit"))) {
                        String expected=(scenario.equals("command_timeout")?"timeout_open":"unexpected_exit")+"/graph_adapter/exit_record_unavailable";
                        ui.post(()->{try{finishResult(code.equals("native_process_died") && expected.equals(details) && session()==0 && errors==1,"death_classification:"+details);}catch(Exception e){finishResult(false,e.toString());}});
                    } else if(name.equals("open") && ((scenario.equals("cancel_wait") && code.equals("disposed")) || (scenario.equals("surface_timeout") && code.equals("surface_timeout"))))openErrors++;
                    else finishResult(false,name+":"+code);
                }
                public void notImplemented(){finishResult(false,"not_implemented");}
            });
        }catch(Exception e){finishResult(false,e.toString());}
        finally{if(arch==null)System.clearProperty("os.arch");else System.setProperty("os.arch",arch);}
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);scenario=getIntent().getStringExtra("scenario");
        if(!Arrays.asList("stale_disconnect","stale_binding_died","stale_null","stale_connect","current_disconnect","cancel_wait","surface_timeout","audio_without_view","command_timeout","native_exit").contains(scenario)){finish();return;}
        try{
            plugin=new OfficialPlayerPlugin();
            field("context",new ContextWrapper(this){
                @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){bindings.add(connection);peers.add(new Peer());return true;}
                @Override public void unbindService(ServiceConnection connection){}
            });
            field("sink",new EventChannel.EventSink(){
                public void success(Object event){if(event instanceof Map && "error".equals(((Map<?,?>)event).get("event")))errors++;}
                public void error(String code,String message,Object details){errors++;}
                public void endOfStream(){}
            });
            ui.postDelayed(()->finishResult(false,"test_timeout"),5000);
            call("create",null,this::created);
            bindings.get(0).onServiceConnected(component,peers.get(0).messenger.getBinder());
        }catch(Exception e){finishResult(false,e.toString());}
    }
    private void created(Object raw){
        try{
            long token=((Number)((Map<?,?>)raw).get("session")).longValue();
            if(field("surface")!=null || field("hostView")!=null)throw new IllegalStateException("Unexpected output before mount");
            if(scenario.equals("command_timeout") || scenario.equals("native_exit")){
                call("open",Map.of("session",token,"audioOnly",true,"source","owned-fixture"),ignored->finishResult(false,"unexpected_open_success"));return;
            }
            if(scenario.equals("audio_without_view")){
                call("open",Map.of("session",token,"audioOnly",true,"source","owned-fixture"),ignored->finishResult(peers.get(0).opens==1 && errors==0,"audio_opened_without_surface"));return;
            }
            if(scenario.equals("cancel_wait") || scenario.equals("surface_timeout")){
                call("open",Map.of("session",token,"source","owned-fixture"),ignored->finishResult(false,"opened_without_surface"));
                Object waiting=field("waitingOpen");if(waiting==null || peers.get(0).opens!=0)throw new IllegalStateException("Not waiting");
                if(scenario.equals("surface_timeout")){
                    Field timeout=waiting.getClass().getDeclaredField("timeout");timeout.setAccessible(true);((Runnable)timeout.get(waiting)).run();
                    finishResult(openErrors==1 && field("waitingOpen")==null && peers.get(0).opens==0,"timeout_completed_once");
                }else call("dispose",Map.of("session",token),ignored->{try{finishResult(openErrors==1 && session()==0 && field("waitingOpen")==null && peers.get(0).opens==0,"cancel_completed_once");}catch(Exception e){finishResult(false,e.toString());}});
                return;
            }
            call("dispose",Map.of("session",token),ignored->{
                oldMessages=peers.get(0).messages;
                call("create",null,this::createdAgain);
                switch(scenario){
                    case "stale_disconnect":bindings.get(0).onServiceDisconnected(component);break;
                    case "stale_binding_died":bindings.get(0).onBindingDied(component);break;
                    case "stale_null":bindings.get(0).onNullBinding(component);break;
                    case "stale_connect":bindings.get(0).onServiceConnected(component,peers.get(0).messenger.getBinder());break;
                }
                bindings.get(1).onServiceConnected(component,peers.get(1).messenger.getBinder());
            });
        }catch(Exception e){finishResult(false,e.toString());}
    }
    private void createdAgain(Object ignored){
        if(scenario.equals("current_disconnect"))bindings.get(1).onServiceDisconnected(component);
        ui.postDelayed(()->{try{boolean dead=scenario.equals("current_disconnect");finishResult(peers.get(0).messages==oldMessages && (dead?session()==0&&errors==1:session()!=0&&errors==0),"session_ownership");}catch(Exception e){finishResult(false,e.toString());}},100);
    }
    private void finishResult(boolean passed,String reason){
        if(finished)return;finished=true;
        try(FileWriter out=new FileWriter(new File(getFilesDir(),"official-plugin-check.json"))){out.write(new JSONObject().put("scenario",scenario).put("passed",passed).put("reason",reason).toString());}
        catch(Exception e){throw new IllegalStateException(e);}
        if(plugin!=null)for(String name:new String[]{"terminate","cleanup"})try{Method m=OfficialPlayerPlugin.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(plugin);}catch(Exception ignored){}
        finish();
    }
}
