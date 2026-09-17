package com.musicfilter.app;

import java.io.*;
import java.util.*;

/** A CPU-pool sizing heuristic, not an accelerator or a promise of optimal throughput. */
final class CpuExecutionPolicy {
    static final class Choice {
        final int threads;
        final String reason;
        final Map<Integer,Integer> capacities;
        Choice(int threads, String reason, Map<Integer,Integer> capacities) {
            this.threads=threads; this.reason=reason; this.capacities=capacities;
        }
    }
    static Choice detect() {
        int available=Math.max(1,Runtime.getRuntime().availableProcessors());
        Map<Integer,Integer> capacities=new TreeMap<>();
        try {
            // Read known paths directly: Android can allow file reads while denying directory listing.
            for(int cpu=0; cpu<available; cpu++) {
                File entry=new File("/sys/devices/system/cpu/cpu"+cpu);
                File online=new File(entry,"online");
                // Some Android kernels deny per-core online files even when capacity is readable.
                // Runtime availability still bounds the pool; unreadable online state is not zero.
                try { if(online.exists() && readNumber(online)==0) continue; } catch(IOException unavailableOnlineState) { }
                int capacity=readNumber(new File(entry,"cpu_capacity"));
                if(capacity<=0) throw new IOException("Invalid capacity");
                capacities.put(Integer.parseInt(entry.getName().substring(3)),capacity);
            }
            // Incomplete/denied sysfs information must not be mistaken for a small CPU.
            if(capacities.size()<available) throw new IOException("Incomplete capacity map");
            int[] values=new int[capacities.size()]; int i=0;
            for(int capacity:capacities.values()) values[i++]=capacity;
            return new Choice(select(values,available), "kernel CPU capacity >= half of maximum; runtime availability bound; no affinity changes", capacities);
        } catch(Exception unavailable) {
            return new Choice(Math.max(1,available/2), "capacity unavailable ("+unavailable.getClass().getSimpleName()+": "+unavailable.getMessage()+"); conservative half-core fallback", capacities);
        }
    }
    static int select(int[] capacities,int available) {
        int max=0; for(int value:capacities) max=Math.max(max,value);
        if(max<=0) return Math.max(1,available/2);
        int count=0;
        // Keep low-capacity efficiency cores from becoming synchronization stragglers.
        // The relative threshold is a portable heuristic; measured benefit still needs validation.
        for(int value:capacities) if((long)value*2>=max) count++;
        return Math.max(1,Math.min(available,count));
    }
    private static int readNumber(File file) throws IOException {
        try(BufferedReader input=new BufferedReader(new FileReader(file))) {
            return Integer.parseInt(input.readLine().trim());
        }
    }
}
