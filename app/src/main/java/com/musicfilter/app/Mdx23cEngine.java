package com.musicfilter.app;

import java.io.*;
import java.nio.*;
import java.util.Collections;
import ai.onnxruntime.*;

/** Exact-config first-block MDX23C runner. Long-audio overlap processing comes next. */
final class Mdx23cEngine {
    private static final int RATE=44100, N=8192, HOP=1024, FREQ=4096, FRAMES=256, SAMPLES=261120;
    private Mdx23cEngine() {}
    static void processFirstBlock(File dir, File input, File output) throws Exception {
        float[][] mix=Wav.readStereo(input, SAMPLES);
        Wav.writeStereo(output, processBlock(dir, mix));
    }
    static float[][] processBlock(File dir, float[][] mix) throws Exception {
        try (Runner runner = new Runner(dir)) { return runner.process(mix); }
    }
    static final class Runner implements AutoCloseable {
        private final OrtSession.SessionOptions options;
        private final OrtSession session;
        Runner(File dir) throws Exception {
            options = new OrtSession.SessionOptions();
            session = OrtEnvironment.getEnvironment().createSession(new File(dir,"MDX23C-8KFFT-InstVoc_HQ.onnx").getAbsolutePath(), options);
        }
        float[][] process(float[][] mix) throws Exception {
        if (mix.length != 2 || mix[0].length != SAMPLES || mix[1].length != SAMPLES) throw new IllegalArgumentException("MDX23C needs exactly 261120 stereo samples.");
        float[] tensor=stft(mix);
        OrtEnvironment env=OrtEnvironment.getEnvironment();
        try (OnnxTensor in=OnnxTensor.createTensor(env, FloatBuffer.wrap(tensor), new long[]{1,4,FREQ,FRAMES});
             OrtSession.Result result=session.run(Collections.singletonMap("input",in))) {
            FloatBuffer out=((OnnxTensor)result.get(0)).getFloatBuffer();
            float[] values=new float[2*4*FREQ*FRAMES]; out.get(values);
            return istftVocals(values);
        }
    }
        @Override public void close() throws Exception { session.close(); options.close(); }
    }
    private static float[] stft(float[][] x) {
        float[] out=new float[4*FREQ*FRAMES], re=new float[N], im=new float[N];
        for(int ch=0;ch<2;ch++) for(int frame=0;frame<FRAMES;frame++) {
            int start=frame*HOP-N/2;
            for(int i=0;i<N;i++){ re[i]=sample(x[ch],start+i)*window(i); im[i]=0; }
            fft(re,im,false);
            int base=(ch*2)*FREQ*FRAMES+frame;
            for(int f=0;f<FREQ;f++){ out[base+f*FRAMES]=re[f]; out[base+FREQ*FRAMES+f*FRAMES]=im[f]; }
        }
        return out;
    }
    private static float[][] istftVocals(float[] value) {
        float[][] audio=new float[2][SAMPLES], weight=new float[2][SAMPLES];
        float[] re=new float[N], im=new float[N];
        for(int ch=0;ch<2;ch++) for(int frame=0;frame<FRAMES;frame++) {
            int base=ch*2*FREQ*FRAMES+frame;
            for(int f=0;f<FREQ;f++){ re[f]=value[base+f*FRAMES]; im[f]=value[base+FREQ*FRAMES+f*FRAMES]; }
            re[FREQ]=im[FREQ]=0;
            for(int f=FREQ+1;f<N;f++){ re[f]=re[N-f]; im[f]=-im[N-f]; }
            fft(re,im,true); int start=frame*HOP-N/2;
            for(int i=0;i<N;i++){ int p=start+i; if(p>=0&&p<SAMPLES){ float w=window(i); audio[ch][p]+=re[i]*w; weight[ch][p]+=w*w; } }
        }
        for(int ch=0;ch<2;ch++) for(int i=0;i<SAMPLES;i++) audio[ch][i]/=Math.max(weight[ch][i],1e-8f);
        return audio;
    }
    private static float sample(float[] a,int i){ if(i<0)i=-i; if(i>=a.length)i=2*a.length-2-i; return a[Math.max(0,Math.min(a.length-1,i))]; }
    private static float window(int i){ return (float)(.5-.5*Math.cos(2*Math.PI*i/N)); }
    private static void fft(float[] re,float[] im,boolean inverse){
        for(int i=1,j=0;i<N;i++){int bit=N>>1;for(; (j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){float t=re[i];re[i]=re[j];re[j]=t;t=im[i];im[i]=im[j];im[j]=t;}}
        for(int len=2;len<=N;len<<=1){double a=2*Math.PI/len*(inverse?1:-1),wr0=Math.cos(a),wi0=Math.sin(a);for(int i=0;i<N;i+=len){double wr=1,wi=0;for(int j=0;j<len/2;j++){int u=i+j,v=u+len/2;double vr=re[v]*wr-im[v]*wi,vi=re[v]*wi+im[v]*wr;re[v]=(float)(re[u]-vr);im[v]=(float)(im[u]-vi);re[u]+=(float)vr;im[u]+=(float)vi;double nwr=wr*wr0-wi*wi0;wi=wr*wi0+wi*wr0;wr=nwr;}}}
        if(inverse)for(int i=0;i<N;i++){re[i]/=N;im[i]/=N;}
    }
    static final class Wav {
        static float[][] readStereo(File f,int max) throws Exception { try(RandomAccessFile r=new RandomAccessFile(f,"r")){r.seek(44);int count=(int)Math.min(max,(r.length()-44)/4);float[][] a=new float[2][max];for(int i=0;i<count;i++){a[0][i]=readLeShort(r)/32768f;a[1][i]=readLeShort(r)/32768f;}return a;} }
        private static short readLeShort(RandomAccessFile r) throws Exception { int lo=r.readUnsignedByte(),hi=r.readUnsignedByte();return (short)((hi<<8)|lo); }
        static void writeStereo(File f,float[][] a)throws Exception{f.getParentFile().mkdirs();try(DataOutputStream o=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))){int d=a[0].length*4;o.writeBytes("RIFF");le(o,36+d);o.writeBytes("WAVEfmt ");le(o,16);les(o,(short)1);les(o,(short)2);le(o,RATE);le(o,RATE*4);les(o,(short)4);les(o,(short)16);o.writeBytes("data");le(o,d);for(int i=0;i<a[0].length;i++){les(o,(short)(Math.max(-1,Math.min(1,a[0][i]))*32767));les(o,(short)(Math.max(-1,Math.min(1,a[1][i]))*32767));}}}
        static void le(DataOutputStream o,int v)throws Exception{o.write(v);o.write(v>>8);o.write(v>>16);o.write(v>>24);} static void les(DataOutputStream o,short v)throws Exception{o.write(v);o.write(v>>8);}
    }
}
