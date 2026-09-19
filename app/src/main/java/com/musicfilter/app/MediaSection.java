package com.musicfilter.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Keyframe-safe MP4 section remux. Encoded audio/video are copied without transcoding. */
final class MediaSection {
    static final class Result {
        final File file; final long startUs; final long endUs;
        Result(File file, long startUs, long endUs) { this.file=file; this.startUs=startUs; this.endUs=endUs; }
    }
    private MediaSection() {}

    static Result remux(Context context, Uri source, File output, long requestedStartUs, long requestedEndUs, boolean preserveRequestedDuration) throws Exception {
        if (requestedStartUs < 0 || requestedEndUs <= requestedStartUs) throw new IOException("Invalid selected section");
        File parent=output.getParentFile(); if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Could not create section folder");
        MediaExtractor extractor=new MediaExtractor(); MediaMuxer muxer=null; boolean started=false,complete=false;
        try {
            extractor.setDataSource(context,source,null);
            int video=-1; long durationUs=0;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);
                if(video<0&&mime!=null&&mime.startsWith("video/"))video=i;
                if(f.containsKey(MediaFormat.KEY_DURATION))durationUs=Math.max(durationUs,f.getLong(MediaFormat.KEY_DURATION));
            }
            if(video<0)throw new IOException("Selected file has no video track");
            long endUs=Math.min(requestedEndUs,durationUs>0?durationUs:requestedEndUs);
            extractor.selectTrack(video);extractor.seekTo(requestedStartUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long actualStartUs=extractor.getSampleTime();extractor.unselectTrack(video);
            if(actualStartUs<0)throw new IOException("Could not locate section start");
            if(preserveRequestedDuration)endUs=Math.min(endUs,actualStartUs+(requestedEndUs-requestedStartUs));
            muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Map<Integer,Integer> tracks=new HashMap<>();int capacity=1024*1024;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime==null||(!mime.startsWith("audio/")&&!mime.startsWith("video/")))continue;
                if(f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))capacity=Math.max(capacity,f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                if(i==video&&f.containsKey(MediaFormat.KEY_ROTATION)){int rotation=f.getInteger(MediaFormat.KEY_ROTATION);if(rotation==90||rotation==180||rotation==270)muxer.setOrientationHint(rotation);}
                tracks.put(i,muxer.addTrack(f));extractor.selectTrack(i);
            }
            if(tracks.size()<2)throw new IOException("Selected section requires audio and video");
            muxer.start();started=true;extractor.seekTo(actualStartUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            ByteBuffer buffer=ByteBuffer.allocateDirect(capacity);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long lastUs=0;boolean wrote=false;
            while(true){int inputTrack=extractor.getSampleTrackIndex();long timeUs=extractor.getSampleTime();if(inputTrack<0||timeUs<0||timeUs>=endUs)break;Integer outputTrack=tracks.get(inputTrack);if(outputTrack==null||timeUs<actualStartUs){extractor.advance();continue;}buffer.clear();int size=extractor.readSampleData(buffer,0);if(size<0)break;info.set(0,size,timeUs-actualStartUs,extractor.getSampleFlags());muxer.writeSampleData(outputTrack,buffer,info);lastUs=Math.max(lastUs,info.presentationTimeUs);wrote=true;extractor.advance();}
            if(!wrote)throw new IOException("Selected section is empty");
            muxer.stop();started=false;complete=true;return new Result(output,actualStartUs,actualStartUs+lastUs);
        } finally {extractor.release();if(muxer!=null){if(started)try{muxer.stop();}catch(Exception ignored){}muxer.release();}if(!complete&&output.exists())output.delete();}
    }
}
