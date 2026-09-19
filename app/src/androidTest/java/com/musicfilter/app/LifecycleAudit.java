package com.musicfilter.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import java.io.*;
import java.lang.reflect.*;

/** Fast on-device checks for state ownership; full media is covered by FullVideoAudit. */
final class LifecycleAudit {
    private final Instrumentation instrumentation;
    private final Context context;
    private Activity activity;

    LifecycleAudit(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        context = instrumentation.getTargetContext();
    }
    private void log(String text) { Bundle b=new Bundle();b.putString("stream",text+"\n");instrumentation.sendStatus(0,b); }
    private void ui(Runnable action) { instrumentation.runOnMainSync(action);instrumentation.waitForIdleSync(); }
    private Object field(String name) throws Exception { Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity); }
    private void set(String name,Object value) throws Exception { Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value); }
    private void call(String name,Class<?>[] types,Object... values) throws Exception { Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,values); }
    private void open(String action) { activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrumentation.waitForIdleSync(); }
    private void select(Uri uri) { ui(()->{try{call("onActivityResult",new Class[]{int.class,int.class,Intent.class},1,Activity.RESULT_OK,new Intent().setData(uri));}catch(Exception e){throw new RuntimeException(e);}}); }
    private void assertTrue(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private static void byteFile(File file) throws Exception { try(FileOutputStream out=new FileOutputStream(file)){out.write(1);} }

    void run() throws Exception {
        assertTrue(!ProcessingState.isRunning(context),"Refusing to disturb an active user job");
        ProcessingState.clearInactive(context);
        File first=new File(context.getFilesDir(),"lifecycle-A.mp4"),second=new File(context.getFilesDir(),"lifecycle-B.mp4");
        byteFile(first);byteFile(second);
        Uri a=Uri.fromFile(first),b=Uri.fromFile(second);
        open("com.musicfilter.app.TEST");

        ProcessingState.write(context,"cancelled-a","CANCELLED",a.toString(),"cancelled",10,null,1);
        ui(()->{try{call("onActivityResult",new Class[]{int.class,int.class,Intent.class},1,Activity.RESULT_CANCELED,null);}catch(Exception e){throw new RuntimeException(e);}});
        assertTrue("cancelled-a".equals(ProcessingState.read(context).getString("jobId","")),"Picker cancellation changed job state");
        log("PASS picker cancellation preserves the current selection/state");

        select(b);
        assertTrue(ProcessingState.read(context).getString("jobId","").isEmpty(),"Successful B selection retained A state");
        assertTrue(b.equals(field("selectedVideo")),"Successful B selection was not displayed");
        log("PASS successful B selection clears A terminal state");

        ProcessingState.write(context,"new-b","RUNNING",b.toString(),"working",20,null,1);
        boolean stale=ProcessingState.writeIfCurrent(context,"old-a","FAILED",a.toString(),"STALE",30,null,1);
        ProcessingState.Snapshot current=ProcessingState.read(context);
        assertTrue(!stale&&"new-b".equals(current.getString("jobId",""))&&b.toString().equals(current.getString("source","")),"Stale A callback replaced B");
        assertTrue(ProcessingState.writeIfCurrent(context,"new-b","FAILED",b.toString(),"B failed",30,null,1),"Current B callback was rejected");
        log("PASS stale callbacks cannot replace the current job identity");

        ProcessingState.write(context,"failed-a","FAILED",a.toString(),"failed",0,null,1);
        ProcessingState.Snapshot failed=ProcessingState.read(context);
        set("selectedVideo",b);set("fresh",false);
        ui(()->{try{call("retryJob",new Class[]{ProcessingState.Snapshot.class},failed);}catch(Exception e){throw new RuntimeException(e);}});
        assertTrue(a.equals(field("selectedVideo")),"Retry used mutable B instead of failed A");
        assertTrue(a.toString().equals(context.getSharedPreferences("interface",Context.MODE_PRIVATE).getString("selected","")),"Retry did not persist failed A");
        log("PASS Retry is bound to the failed job source");

        ui(()->activity.finishAndRemoveTask());
        ProcessingState.write(context,"old-terminal","CANCELLED",a.toString(),"cancelled",0,null,1);
        open(Intent.ACTION_MAIN);
        assertTrue(ProcessingState.read(context).getString("jobId","").isEmpty(),"Fresh launcher retained terminal job");
        assertTrue(field("selectedVideo")==null,"Fresh launcher retained the last video");
        log("PASS fresh launcher opens default Home and forgets terminal media");
        ui(()->activity.finish());
    }

    static void execute(Instrumentation instrumentation) {
        Bundle result=new Bundle();
        try { new LifecycleAudit(instrumentation).run();result.putString("stream","PASS lifecycle state audit\n");instrumentation.finish(Activity.RESULT_OK,result); }
        catch(Throwable error){result.putString("stream",android.util.Log.getStackTraceString(error));instrumentation.finish(Activity.RESULT_CANCELED,result);}
    }
}
