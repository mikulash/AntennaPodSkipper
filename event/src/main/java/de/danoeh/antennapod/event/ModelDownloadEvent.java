package de.danoeh.antennapod.event;

/**
 * Event posted when transcription model download status changes.
 */
public class ModelDownloadEvent {
    public enum Status {
        STARTED,
        PROGRESS,
        EXTRACTING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private final String modelId;
    private final Status status;
    private final int progress;
    private final long bytesDownloaded;
    private final long totalBytes;
    private final String errorMessage;

    private ModelDownloadEvent(String modelId, Status status, int progress,
                               long bytesDownloaded, long totalBytes, String errorMessage) {
        this.modelId = modelId;
        this.status = status;
        this.progress = progress;
        this.bytesDownloaded = bytesDownloaded;
        this.totalBytes = totalBytes;
        this.errorMessage = errorMessage;
    }

    public static ModelDownloadEvent started(String modelId) {
        return new ModelDownloadEvent(modelId, Status.STARTED, 0, 0, 0, null);
    }

    public static ModelDownloadEvent progress(String modelId, int progress,
                                               long bytesDownloaded, long totalBytes) {
        return new ModelDownloadEvent(modelId, Status.PROGRESS, progress,
                bytesDownloaded, totalBytes, null);
    }

    public static ModelDownloadEvent extracting(String modelId) {
        return new ModelDownloadEvent(modelId, Status.EXTRACTING, -1, 0, 0, null);
    }

    public static ModelDownloadEvent completed(String modelId) {
        return new ModelDownloadEvent(modelId, Status.COMPLETED, 100, 0, 0, null);
    }

    public static ModelDownloadEvent failed(String modelId, String errorMessage) {
        return new ModelDownloadEvent(modelId, Status.FAILED, 0, 0, 0, errorMessage);
    }

    public static ModelDownloadEvent cancelled(String modelId) {
        return new ModelDownloadEvent(modelId, Status.CANCELLED, 0, 0, 0, null);
    }

    public String getModelId() {
        return modelId;
    }

    public Status getStatus() {
        return status;
    }

    public int getProgress() {
        return progress;
    }

    public long getBytesDownloaded() {
        return bytesDownloaded;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
