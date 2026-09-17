package com.musicfilter.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.*;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import android.util.Log;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Diagnostic measurements only. Never decides quality, thread priority or device policy. */
final class PerformanceTrace {
    private static final ThreadLocal<PerformanceTrace> CURRENT = new ThreadLocal<>();
    private final Context context;
    private final File folder;
    private final long started = SystemClock.elapsedRealtimeNanos();
    private final long startedCpu = android.os.Process.getElapsedCpuTime();
    private final JSONObject metadata = new JSONObject();
    private final Map<String,long[]> stages = new LinkedHashMap<>();
    private final JSONArray samples = new JSONArray(), chunks = new JSONArray();
    private final Map<Integer,Long> previousTicks = new HashMap<>();
    private long sampledAt, sampledCpu;
    private Scope currentScope;
    private int workerTid;
    private String state = "running";
    private long finishedNs;
    private final long ticksPerSecond;

    PerformanceTrace(Context context) {
        this.context = context.getApplicationContext();
        folder = new File(context.getFilesDir(), "diagnostics");
        folder.mkdirs();
        long ticks = 100;
        try { ticks = Os.sysconf(OsConstants._SC_CLK_TCK); } catch (Exception ignored) { }
        ticksPerSecond = ticks > 0 ? ticks : 100;
        put("schema", 1); put("pipeline_revision", "capacity-cpu-v6");
        try {
            android.content.pm.PackageInfo pkg=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
            put("app_version",pkg.versionName); put("app_version_code",pkg.getLongVersionCode());
        } catch(Exception ignored) { }
        put("android_sdk", Build.VERSION.SDK_INT); put("manufacturer", Build.MANUFACTURER);
        put("device_model", Build.MODEL); put("supported_abis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)));
        put("process_is_64_bit", android.os.Process.is64Bit());
        put("runtime_available_processors", Runtime.getRuntime().availableProcessors());
        put("ort_version", ai.onnxruntime.OrtEnvironment.getEnvironment().getVersion());
        put("model_execution_provider", "CPUExecutionProvider");
        put("model_gpu_npu_used", false); put("thread_policy", "pending CPU capability detection at session creation");
        put("cpu_arena", false); put("memory_pattern", false); put("spinning", false);
        put("chunk_frames", DttNetEngine.CHUNK_SAMPLES); put("chunk_hop_frames", DttNetEngine.OVERLAP_SAMPLES);
        put("sample_rate", DttNetEngine.SAMPLE_RATE);
        put("timing_note", "Stage wall_ms is exclusive; inclusive_ms includes child stages. Codec waits include native worker time. CPU percent is process CPU, 100% equals one fully busy core. Unavailable hardware readings are not zero.");
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        context.getSystemService(ActivityManager.class).getMemoryInfo(memory);
        put("total_ram_bytes", memory.totalMem); put("available_ram_at_start_bytes", memory.availMem);
        put("heap_limit_bytes", Runtime.getRuntime().maxMemory());
    }
    void attach() { CURRENT.set(this); workerTid = android.os.Process.myTid(); put("worker_tid", workerTid); }
    static void detach() { CURRENT.remove(); }
    static PerformanceTrace current() { return CURRENT.get(); }
    synchronized void put(String key, Object value) { try { metadata.put(key,value); } catch (JSONException ignored) { } }
    static void detail(String key, Object value) { PerformanceTrace trace=CURRENT.get(); if(trace!=null) trace.put(key,value); }
    static void model(File file) {
        PerformanceTrace trace=CURRENT.get(); if(trace==null) return;
        try(Scope timing=stage("model_identity"); InputStream input=new BufferedInputStream(new FileInputStream(file))) {
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[65536]; int n;
            while((n=input.read(buffer))!=-1) digest.update(buffer,0,n);
            StringBuilder hex=new StringBuilder();
            for(byte b:digest.digest()) hex.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
            trace.put("model_sha256",hex.toString()); trace.put("model_bytes",file.length());
        } catch(Exception error) { trace.put("model_sha256","unavailable"); }
    }
    String operatorPrefix() { return new File(folder, "ort-first-chunk").getAbsolutePath(); }
    static void operators(String path) {
        detail("operator_profile_file", path);
        try (InputStream input = new FileInputStream(path)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 16 * 1024 * 1024) throw new IOException("Profile too large");
                bytes.write(buffer, 0, count);
            }
            JSONArray events = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            JSONObject totals = new JSONObject();
            for (int i=0;i<events.length();i++) {
                JSONObject event=events.optJSONObject(i);
                if (event==null || !event.optString("name").endsWith("_kernel_time")) continue;
                JSONObject args=event.optJSONObject("args");
                if(args==null) continue;
                String key=args.optString("provider", "unknown")+":"+args.optString("op_name", "unknown");
                totals.put(key, totals.optDouble(key,0)+event.optDouble("dur",0)/1000.0);
            }
            detail("first_chunk_operators_ms",totals);
        } catch (Exception error) { detail("operator_summary_error",error.getClass().getSimpleName()); }
    }
    static Scope stage(String name) { return new Scope(CURRENT.get(), name); }
    static final class Scope implements AutoCloseable {
        final PerformanceTrace trace; final String name; final Scope parent;
        final long wall = SystemClock.elapsedRealtimeNanos(), cpu = Debug.threadCpuTimeNanos();
        long childWall, childCpu; boolean closed;
        Scope(PerformanceTrace trace, String name) {
            this.trace=trace; this.name=name; parent=trace==null ? null : trace.currentScope;
            if(trace!=null) trace.currentScope=this;
        }
        @Override public void close() {
            if(closed || trace==null) return;
            closed=true;
            long w=SystemClock.elapsedRealtimeNanos()-wall, c=Debug.threadCpuTimeNanos()-cpu;
            synchronized(trace) {
                long[] totals=trace.stages.get(name);
                if(totals==null) { totals=new long[5]; trace.stages.put(name,totals); }
                totals[0]++; totals[1]+=Math.max(0,w-childWall); totals[2]+=w;
                totals[3]+=Math.max(0,c-childCpu); totals[4]+=c;
            }
            if(parent!=null) { parent.childWall+=w; parent.childCpu+=c; }
            trace.currentScope=parent;
        }
    }
    static void chunk(long index, long elapsedMs) {
        PerformanceTrace trace=CURRENT.get(); if(trace==null) return;
        synchronized(trace) {
            if(trace.chunks.length()<4096) {
                JSONObject row=new JSONObject();
                try { row.put("index",index); row.put("wall_ms",elapsedMs); row.put("elapsed_ms",(SystemClock.elapsedRealtimeNanos()-trace.started)/1e6); } catch(JSONException ignored) { }
                trace.chunks.put(row);
            }
        }
    }
    synchronized void sample() {
        if(finishedNs!=0) return;
        try {
            long now=SystemClock.elapsedRealtime(), cpu=android.os.Process.getElapsedCpuTime();
            JSONObject row=new JSONObject(); row.put("elapsed_ms",(SystemClock.elapsedRealtimeNanos()-started)/1e6);
            if(sampledAt!=0 && now>sampledAt) row.put("process_cpu_percent",100.0*(cpu-sampledCpu)/(now-sampledAt));
            row.put("java_heap_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
            row.put("native_heap_allocated_bytes",Debug.getNativeHeapAllocatedSize());
            row.put("worker_cgroup",read("/proc/self/task/"+workerTid+"/cgroup"));
            row.put("worker_status",selectedStatus(read("/proc/self/task/"+workerTid+"/status")));
            row.put("process_status",selectedStatus(read("/proc/self/status")));
            PowerManager power=context.getSystemService(PowerManager.class);
            row.put("interactive",power.isInteractive()); row.put("power_save",power.isPowerSaveMode());
            row.put("thermal_status",power.getCurrentThermalStatus());
            if(Build.VERSION.SDK_INT>=30) {
                float headroom=power.getThermalHeadroom(0);
                row.put("thermal_headroom",Float.isFinite(headroom)?(Object)headroom:"unavailable");
            }
            android.content.Intent battery=context.registerReceiver(null,new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if(battery!=null) {
                row.put("battery_temperature_c",battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)/10.0);
                row.put("charging_source",battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0));
            }
            row.put("battery_optimization_exempt",power.isIgnoringBatteryOptimizations(context.getPackageName()));
            ActivityManager.RunningAppProcessInfo importance=new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(importance); row.put("process_importance",importance.importance);
            JSONArray active=new JSONArray(); File[] tasks=new File("/proc/self/task").listFiles();
            Set<Integer> present=new HashSet<>();
            if(tasks!=null) for(File task:tasks) {
                try {
                    int tid=Integer.parseInt(task.getName()); present.add(tid);
                    String stat=read(task.getAbsolutePath()+"/stat"); int end=stat.lastIndexOf(')');
                    String[] fields=stat.substring(end+2).split("\\s+");
                    long ticks=Long.parseLong(fields[11])+Long.parseLong(fields[12]);
                    Long old=previousTicks.put(tid,ticks);
                    if(old!=null && ticks>old && sampledAt!=0) {
                        JSONObject thread=new JSONObject(); thread.put("tid",tid); thread.put("name",stat.substring(stat.indexOf('(')+1,end));
                        thread.put("state",fields[0]); thread.put("cpu_percent",100000.0*(ticks-old)/ticksPerSecond/(now-sampledAt));
                        thread.put("last_cpu",Integer.parseInt(fields[36])); active.put(thread);
                    }
                } catch(Exception ignored) { }
            }
            previousTicks.keySet().retainAll(present); row.put("threads_with_cpu_time",active);
            JSONObject frequencies=new JSONObject();
            for(int i=0;i<Runtime.getRuntime().availableProcessors();i++) frequencies.put(String.valueOf(i),read("/sys/devices/system/cpu/cpu"+i+"/cpufreq/scaling_cur_freq").trim());
            row.put("cpu_frequency_khz",frequencies);
            if(samples.length()>=720) samples.remove(0);
            samples.put(row); sampledAt=now; sampledCpu=cpu;
        } catch(Exception error) { Log.w("MusicFilterPerf","Hardware sample unavailable",error); }
        save();
    }
    private static String read(String path) {
        try(BufferedReader in=new BufferedReader(new FileReader(path))) {
            StringBuilder text=new StringBuilder(); String line;
            while((line=in.readLine())!=null && text.length()<32768) text.append(line).append('\n');
            return text.toString();
        } catch(Exception ignored) { return "unavailable"; }
    }
    private static String selectedStatus(String status) {
        StringBuilder out=new StringBuilder();
        for(String line:status.split("\n")) if(line.startsWith("VmRSS:") || line.startsWith("VmHWM:") || line.startsWith("Threads:") || line.startsWith("Cpus_allowed_list:") || line.startsWith("State:")) out.append(line).append('\n');
        return out.length()==0 ? "unavailable" : out.toString();
    }
    synchronized void finish(String state) { this.state=state; finishedNs=SystemClock.elapsedRealtimeNanos(); save(); }
    synchronized void save() {
        AtomicFile file=new AtomicFile(new File(folder,"latest-performance.json")); FileOutputStream stream=null;
        try {
            JSONObject report=new JSONObject(); report.put("metadata",metadata); report.put("state",state);
            report.put("elapsed_ms",((finishedNs==0 ? SystemClock.elapsedRealtimeNanos() : finishedNs)-started)/1e6);
            report.put("process_cpu_ms",android.os.Process.getElapsedCpuTime()-startedCpu);
            JSONObject timings=new JSONObject();
            for(Map.Entry<String,long[]> entry:stages.entrySet()) {
                long[] v=entry.getValue(); JSONObject value=new JSONObject();
                value.put("calls",v[0]); value.put("wall_ms",v[1]/1e6); value.put("inclusive_ms",v[2]/1e6);
                value.put("worker_cpu_ms",v[3]/1e6); value.put("worker_cpu_inclusive_ms",v[4]/1e6);
                timings.put(entry.getKey(),value);
            }
            report.put("stages",timings); report.put("chunks",chunks); report.put("hardware_samples",samples);
            stream=file.startWrite(); stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream);
        } catch(Exception error) { if(stream!=null) file.failWrite(stream); Log.w("MusicFilterPerf","Could not save diagnostics",error); }
    }
}
