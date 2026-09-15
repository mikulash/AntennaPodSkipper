package de.danoeh.antennapod.net.ai.service.ad;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits an audio file into smaller chunks using {@link MediaExtractor} and
 * {@link MediaMuxer} so that very long podcast episodes can be transcribed in multiple
 * API calls without exceeding request limits.
 */
public final class AudioChunkUtils {
    private static final String TAG = "AudioChunkUtils";
    private static final int BUFFER_SIZE_BYTES = 256 * 1024;
    private static final String MIME_MP3 = "audio/mpeg";
    private static final String MIME_AAC = "audio/mp4a-latm";
    private static final String MIME_MP4 = "audio/mp4";
    private static final String MIME_WEBM = "audio/webm";
    private static final String MIME_WAV = "audio/wav";
    private static final String MIME_WAVE = "audio/x-wav";
    private static final String MIME_OPUS = "audio/opus";
    private static final String MIME_RAW = "audio/raw";
    private static final String MEDIA_FORMAT_PCM_ENCODING = "pcm-encoding";

    private AudioChunkUtils() {
        // Utility class
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
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
        if (!isSupportedTrackMime(mimeType)) {
            extractor.release();
            throw new IOException("Unsupported audio codec for transcription chunks: " + mimeType);
        }
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
        Log.i(TAG, "Created " + chunkPaths.size() + " chunk(s) from " + filePath
                + " (" + (durationUs / 1_000_000L) + "s total)");
        return chunkPaths;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private static Path writeChunk(Context context, MediaExtractor extractor, MediaFormat format,
                                   String mimeType, long startUs, long endUs) throws IOException {
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        if (MIME_MP3.equalsIgnoreCase(mimeType)) {
            return writeRawChunk(context, extractor, startUs, endUs, ".mp3");
        }
        if (MIME_OPUS.equalsIgnoreCase(mimeType) || MIME_WEBM.equalsIgnoreCase(mimeType)) {
            return writeMuxedChunk(context, extractor, format, startUs, endUs,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, ".webm");
        }
        if (MIME_RAW.equalsIgnoreCase(mimeType)
                || MIME_WAV.equalsIgnoreCase(mimeType)
                || MIME_WAVE.equalsIgnoreCase(mimeType)) {
            return writeWavChunk(context, extractor, format, startUs, endUs);
        }
        return writeMuxedChunk(context, extractor, format, startUs, endUs,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, ".m4a");
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private static Path writeMuxedChunk(Context context, MediaExtractor extractor, MediaFormat format,
                                        long startUs, long endUs, int outputFormat,
                                        String fileExtension) throws IOException {
        File output = File.createTempFile("ad_chunk_", fileExtension, context.getCacheDir());
        MediaMuxer muxer = new MediaMuxer(
                output.getAbsolutePath(),
                outputFormat
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

    @RequiresApi(api = Build.VERSION_CODES.O)
    private static Path writeWavChunk(Context context, MediaExtractor extractor, MediaFormat format,
                                      long startUs, long endUs) throws IOException {
        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int pcmEncoding = format.containsKey(MEDIA_FORMAT_PCM_ENCODING)
                ? format.getInteger(MEDIA_FORMAT_PCM_ENCODING)
                : AudioFormat.ENCODING_PCM_16BIT;
        int bitsPerSample;
        int wavFormat;
        switch (pcmEncoding) {
            case AudioFormat.ENCODING_PCM_8BIT:
                bitsPerSample = 8;
                wavFormat = 1;
                break;
            case AudioFormat.ENCODING_PCM_FLOAT:
                bitsPerSample = 32;
                wavFormat = 3;
                break;
            case AudioFormat.ENCODING_PCM_16BIT:
                bitsPerSample = 16;
                wavFormat = 1;
                break;
            default:
                throw new IOException("Unsupported PCM encoding for WAV transcription chunk: " + pcmEncoding);
        }

        File output = File.createTempFile("ad_chunk_", ".wav", context.getCacheDir());
        long dataSize = 0;
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE_BYTES);
        try (RandomAccessFile wav = new RandomAccessFile(output, "rw")) {
            wav.setLength(0);
            writeWavHeader(wav, wavFormat, channelCount, sampleRate, bitsPerSample, 0);
            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                long sampleTime = extractor.getSampleTime();
                if (size < 0 || sampleTime < 0 || sampleTime > endUs) {
                    break;
                }
                if (sampleTime >= startUs) {
                    wav.write(buffer.array(), 0, size);
                    dataSize += size;
                }
                buffer.clear();
                if (!extractor.advance()) {
                    break;
                }
            }
            if (dataSize > Integer.MAX_VALUE) {
                throw new IOException("WAV transcription chunk is too large");
            }
            wav.seek(0);
            writeWavHeader(wav, wavFormat, channelCount, sampleRate, bitsPerSample, (int) dataSize);
        }
        return output.toPath();
    }

    static void writeWavHeader(RandomAccessFile output, int format, int channelCount,
                                       int sampleRate, int bitsPerSample, int dataSize) throws IOException {
        output.writeBytes("RIFF");
        writeLittleEndianInt(output, 36 + dataSize);
        output.writeBytes("WAVE");
        output.writeBytes("fmt ");
        writeLittleEndianInt(output, 16);
        writeLittleEndianShort(output, format);
        writeLittleEndianShort(output, channelCount);
        writeLittleEndianInt(output, sampleRate);
        writeLittleEndianInt(output, sampleRate * channelCount * bitsPerSample / 8);
        writeLittleEndianShort(output, channelCount * bitsPerSample / 8);
        writeLittleEndianShort(output, bitsPerSample);
        output.writeBytes("data");
        writeLittleEndianInt(output, dataSize);
    }

    private static void writeLittleEndianInt(RandomAccessFile output, int value) throws IOException {
        output.writeByte(value);
        output.writeByte(value >>> 8);
        output.writeByte(value >>> 16);
        output.writeByte(value >>> 24);
    }

    private static void writeLittleEndianShort(RandomAccessFile output, int value) throws IOException {
        output.writeByte(value);
        output.writeByte(value >>> 8);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
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
        return output.toPath();
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

    static boolean isSupportedTrackMime(String mime) {
        if (mime == null) {
            return false;
        }
        String normalized = mime.toLowerCase(Locale.US);
        return normalized.startsWith(MIME_MP3)
                || normalized.startsWith(MIME_AAC)
                || normalized.startsWith(MIME_MP4)
                || normalized.startsWith(MIME_WEBM)
                || normalized.startsWith(MIME_WAV)
                || normalized.startsWith(MIME_WAVE)
                || normalized.startsWith(MIME_OPUS)
                || normalized.startsWith(MIME_RAW);
    }

    static String getChunkFileExtension(String mime) {
        if (mime == null) {
            return "";
        }
        if (MIME_MP3.equalsIgnoreCase(mime)) {
            return ".mp3";
        }
        if (MIME_OPUS.equalsIgnoreCase(mime) || MIME_WEBM.equalsIgnoreCase(mime)) {
            return ".webm";
        }
        if (MIME_RAW.equalsIgnoreCase(mime)
                || MIME_WAV.equalsIgnoreCase(mime)
                || MIME_WAVE.equalsIgnoreCase(mime)) {
            return ".wav";
        }
        if (MIME_AAC.equalsIgnoreCase(mime) || MIME_MP4.equalsIgnoreCase(mime)) {
            return ".m4a";
        }
        return "";
    }
}
