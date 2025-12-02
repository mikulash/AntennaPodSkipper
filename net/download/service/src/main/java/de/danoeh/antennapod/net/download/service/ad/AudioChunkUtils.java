package de.danoeh.antennapod.net.download.service.ad;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits an audio file into smaller, valid container chunks using {@link MediaExtractor} and
 * {@link MediaMuxer} so that very long podcast episodes can be transcribed in multiple Whisper
 * calls without exceeding request limits.
 */
public final class AudioChunkUtils {
    private static final String TAG = "AudioChunkUtils";
    private static final int BUFFER_SIZE_BYTES = 256 * 1024;

    private AudioChunkUtils() {
        // Utility class
    }

    public static List<Path> createAudioChunks(Context context, String filePath,
                                               long chunkDurationSeconds) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(context, Uri.fromFile(new File(filePath)), null);
        int audioTrackIndex = selectAudioTrack(extractor);
        if (audioTrackIndex < 0) {
            extractor.release();
            throw new IOException("No audio track found in " + filePath);
        }

        extractor.selectTrack(audioTrackIndex);
        MediaFormat format = extractor.getTrackFormat(audioTrackIndex);
        String mimeType = format.getString(MediaFormat.KEY_MIME);
        long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                ? format.getLong(MediaFormat.KEY_DURATION)
                : -1L;
        if (durationUs <= 0) {
            extractor.release();
            throw new IOException("Unable to read duration for " + filePath);
        }

        List<Path> chunkPaths = new ArrayList<>();
        long chunkDurationUs = chunkDurationSeconds * 1_000_000L;
        long startUs = 0;
        while (startUs < durationUs) {
            long endUs = Math.min(durationUs, startUs + chunkDurationUs);
            Path chunkPath = writeChunk(context, extractor, format, mimeType, startUs, endUs);
            chunkPaths.add(chunkPath);
            startUs = endUs;
        }
        extractor.release();
        return chunkPaths;
    }

    private static Path writeChunk(Context context, MediaExtractor extractor, MediaFormat format,
                                   String mimeType, long startUs, long endUs) throws IOException {
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        if ("audio/mpeg".equalsIgnoreCase(mimeType)) {
            Log.w(TAG, "Muxer does not support MIME type " + mimeType + ", writing raw chunk");
            return writeRawChunk(context, extractor, startUs, endUs, ".mp3");
        }
        return writeMuxedChunk(context, extractor, format, startUs, endUs);
    }

    private static Path writeMuxedChunk(Context context, MediaExtractor extractor, MediaFormat format,
                                        long startUs, long endUs) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            File output = File.createTempFile("ad_chunk_", ".m4a", context.getCacheDir());
            MediaMuxer muxer = new MediaMuxer(
                    output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            );
            try {
                int dstTrack = muxer.addTrack(format);
                muxer.start();

                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                ByteBuffer buffer = ByteBuffer
                        .allocateDirect(BUFFER_SIZE_BYTES)
                        .order(ByteOrder.nativeOrder());

                while (true) {
                    bufferInfo.offset = 0;
                    bufferInfo.size = extractor.readSampleData(buffer, 0);
                    long sampleTime = extractor.getSampleTime();

                    if (bufferInfo.size < 0 || sampleTime < 0 || sampleTime > endUs) {
                        break;
                    }

                    // Skip samples that are before the requested startUs
                    if (sampleTime < startUs) {
                        if (!extractor.advance()) {
                            break;
                        }
                        continue;
                    }

                    // Rebase timestamps so the chunk starts at 0
                    bufferInfo.presentationTimeUs = Math.max(0, sampleTime - startUs);

                    int sampleFlags = extractor.getSampleFlags();
                    int codecFlags = 0;
                    if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        codecFlags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    }
                    bufferInfo.flags = codecFlags;

                    muxer.writeSampleData(dstTrack, buffer, bufferInfo);

                    if (!extractor.advance()) {
                        break;
                    }
                }

                muxer.stop();
            } finally {
                muxer.release();
            }

            return output.toPath();
        }
        return null;
    }

    private static Path writeRawChunk(Context context, MediaExtractor extractor, long startUs,
                                      long endUs, String fileExtension) throws IOException {
        File output = File.createTempFile("ad_chunk_", fileExtension, context.getCacheDir());
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE_BYTES);
        try (FileOutputStream fos = new FileOutputStream(output)) {
            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                long sampleTime = extractor.getSampleTime();

                if (size < 0 || sampleTime < 0 || sampleTime > endUs) {
                    break;
                }

                // Skip samples that are before the requested startUs
                if (sampleTime < startUs) {
                    buffer.clear();
                    if (!extractor.advance()) {
                        break;
                    }
                    continue;
                }

                fos.write(buffer.array(), 0, size);
                buffer.clear();

                if (!extractor.advance()) {
                    break;
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return output.toPath();
        }
        return null;
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        Log.w(TAG, "No audio track found");
        return -1;
    }
}
