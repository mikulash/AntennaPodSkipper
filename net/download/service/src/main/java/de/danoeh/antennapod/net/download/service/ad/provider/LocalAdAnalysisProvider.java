package de.danoeh.antennapod.net.download.service.ad.provider;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions;

import java.io.File;
import java.io.IOException;

import de.danoeh.antennapod.net.download.service.ad.litert.LiteRtLLMManager;
import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;

@RequiresApi(api = Build.VERSION_CODES.O)
public class LocalAdAnalysisProvider implements AdAnalysisProvider {
    private static final String TAG = "LocalAdAnalysisProv";
    private static final String MODEL_NAME_PREFIX = "local-litert+";

    private final Context context;
    private final LiteRtLLMManager llmManager;
    private final String litertModelName;
    private LlmInference llmInference;

    public LocalAdAnalysisProvider(Context context) throws IOException {
        this.context = context;
        this.llmManager = new LiteRtLLMManager(context);
        this.litertModelName = OpenAiPreferences.getLocalAdAnalysisModel(context);

        if (!llmManager.isModelDownloaded(litertModelName)) {
            throw new IOException("Local analysis model not downloaded: " + litertModelName);
        }

        initializeLlmInference();
    }

    private void initializeLlmInference() {
        Log.i(TAG, "Initializing LiteRT LLM Inference with model: " + litertModelName);
        File modelFile = llmManager.getModelPath(litertModelName);

        LlmInferenceOptions options = LlmInferenceOptions.builder()
                .setModelPath(modelFile.getAbsolutePath())
                .setMaxTokens(1024)
                .build();

        this.llmInference = LlmInference.createFromOptions(context, options);
    }

    @Override
    public String getModelName() {
        return MODEL_NAME_PREFIX + litertModelName;
    }

    @Override
    public String analyzeTranscript(String prompt) throws Exception {
        if (llmInference == null) {
            throw new IllegalStateException("LLM engine not initialized");
        }
        Log.i(TAG, "Running LiteRT analysis...");

        String formattedPrompt = formatPromptForModel(prompt);
        String result = llmInference.generateResponse(formattedPrompt);
        Log.d(TAG, "LiteRT result: " + result);
        return result;
    }

    private String formatPromptForModel(String rawPrompt) {
        // Gemma
        if (litertModelName.contains("gemma")) {
            return "<start_of_turn>user\n" + rawPrompt + "<end_of_turn>\n<start_of_turn>model\n";
        }
        // Qwen
        if (litertModelName.contains("qwen") || litertModelName.equals(OpenAiPreferences.MANUAL_MODEL_ID)) {
             return "<|im_start|>user\n" + rawPrompt + "<|im_end|>\n<|im_start|>assistant\n";
        }
        return rawPrompt;
    }

    @Override
    public void close() {
        // LlmInference auto-cleaning usually sufficient, but placeholder for future
    }
}
