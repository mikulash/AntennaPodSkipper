package de.danoeh.antennapod.net.ai.service.ad.transcription;

import java.io.File;
import java.io.IOException;

/**
 * Interface for managing local audio transcription models.
 * Implementations handle model download, loading, and audio-to-text transcription.
 */
public interface TranscriptionManager {
    /**
     * Checks if the specified model is downloaded and ready to use.
     */
    boolean isModelDownloaded(String modelName);

    /**
     * Gets the minimum memory required to load the specified model in bytes.
     */
    long getMinMemoryRequired(String modelId);

    /**
     * Checks if there is enough available memory to load the specified model.
     */
    boolean hasEnoughMemory(String modelName);

    /**
     * Loads the specified model into memory for transcription.
     * @throws IOException if the model cannot be loaded
     */
    void loadModel(String modelName) throws IOException;

    /**
     * Transcribes an audio chunk and returns the result in VTT format.
     *
     * @param audioFile The audio file to transcribe
     * @param offsetSeconds The time offset for this chunk in seconds
     * @return VTT-formatted transcription
     * @throws IOException if transcription fails
     */
    String transcribeChunk(File audioFile, double offsetSeconds) throws IOException;

    /**
     * Unloads the currently loaded model from memory.
     */
    void unloadModel();
}
