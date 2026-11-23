package com.varikaagency.download;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.varikaagency.download.core.HlsTask;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Convertit les vidéos HLS (.ts, ou image + son séparés) en un seul .mp4, sans réencodage,
 * avec les outils intégrés d'Android (MediaExtractor + MediaMuxer). En cas d'échec,
 * le moteur garde le fichier .ts (lisible avec VLC).
 */
final class MediaRemuxer implements HlsTask.Remuxer {

    @Override public File remux(File video, File audio, File wanted) {
        MediaMuxer muxer = null;
        List<MediaExtractor> extractors = new ArrayList<MediaExtractor>();
        try {
            muxer = new MediaMuxer(wanted.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            List<int[]> map = new ArrayList<int[]>();        // {extracteur, piste source, piste mp4}
            boolean haveAudio = false;
            File[] inputs = audio == null ? new File[]{video} : new File[]{video, audio};
            for (int e = 0; e < inputs.length; e++) {
                MediaExtractor ex = new MediaExtractor();
                ex.setDataSource(inputs[e].getPath());
                extractors.add(ex);
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime == null) continue;
                    boolean isVideo = mime.startsWith("video/"), isAudio = mime.startsWith("audio/");
                    if (e == 0 && (isVideo || (isAudio && audio == null)) || (e == 1 && isAudio && !haveAudio)) {
                        map.add(new int[]{e, i, muxer.addTrack(f)});
                        if (isAudio) haveAudio = true;
                    }
                }
            }
            if (map.isEmpty()) throw new IllegalStateException("aucune piste lisible");
            muxer.start();
            ByteBuffer buf = ByteBuffer.allocate(8 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int[] m : map) {
                MediaExtractor ex = extractors.get(m[0]);
                for (int i = 0; i < ex.getTrackCount(); i++) ex.unselectTrack(i);
                ex.selectTrack(m[1]);
                ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                long first = -1;
                while (true) {
                    int size = ex.readSampleData(buf, 0);
                    if (size < 0) break;
                    long time = ex.getSampleTime();
                    if (first < 0) first = time;
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = Math.max(0, time - first);
                    info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    muxer.writeSampleData(m[2], buf, info);
                    ex.advance();
                }
            }
            muxer.stop();
            return wanted;
        } catch (Exception e) {
            if (wanted.exists() && !wanted.delete()) wanted.deleteOnExit();
            return null;
        } finally {
            for (MediaExtractor ex : extractors) ex.release();
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (RuntimeException ignored) {
                    // déjà libéré
                }
            }
        }
    }
}
