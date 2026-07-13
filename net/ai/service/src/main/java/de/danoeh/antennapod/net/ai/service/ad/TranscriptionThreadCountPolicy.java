package de.danoeh.antennapod.net.ai.service.ad;

/**
 * Chooses conservative parallelism for memory-heavy transcription work.
 */
public final class TranscriptionThreadCountPolicy {
    private static final long MEMORY_PER_THREAD_BYTES = 64L * 1024L * 1024L;
    private static final long SAFE_BUFFER_BYTES = 200L * 1024L * 1024L;

    private TranscriptionThreadCountPolicy() {
    }

    public static int chooseThreadCount(int availableProcessors, long maxMemoryBytes, long usedMemoryBytes) {
        long availableMemory = maxMemoryBytes - usedMemoryBytes;
        long usableForWorkers = availableMemory - SAFE_BUFFER_BYTES;
        int maxThreadsByMemory = (int) (usableForWorkers / MEMORY_PER_THREAD_BYTES);
        return Math.max(1, Math.min(Math.max(1, availableProcessors), maxThreadsByMemory));
    }
}
