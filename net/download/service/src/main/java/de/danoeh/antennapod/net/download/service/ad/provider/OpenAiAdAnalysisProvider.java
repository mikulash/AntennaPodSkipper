package de.danoeh.antennapod.net.download.service.ad.provider;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.completions.CompletionUsage;

import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;

@RequiresApi(api = Build.VERSION_CODES.O)
public class OpenAiAdAnalysisProvider implements AdAnalysisProvider {
    private static final String TAG = "OpenAiAdAnalysisProv";
    private static final String DEFAULT_MODEL_NAME = "gpt-5-nano";
    
    // Pricing (Estimated)
    private static final double PRICE_INPUT_PER_1M = 0.15; // $0.15 per 1M input tokens
    private static final double PRICE_OUTPUT_PER_1M = 0.60; // $0.60 per 1M output tokens

    private final Context context;
    private final OpenAIClient client;
    private final String modelName;

    public OpenAiAdAnalysisProvider(Context context) {
        this.context = context;
        String apiKey = OpenAiPreferences.getApiKey(context);
        if (TextUtils.isEmpty(apiKey)) {
            throw new IllegalStateException("Missing OpenAI API key");
        }
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
        String storedModel = OpenAiPreferences.getModel(context);
        this.modelName = TextUtils.isEmpty(storedModel) ? DEFAULT_MODEL_NAME : storedModel;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public String analyzeTranscript(String prompt) throws Exception {
        ChatModel chatModel = resolveChatModel(modelName);
        ChatCompletionCreateParams chatParams = ChatCompletionCreateParams.builder()
                .addUserMessage(prompt)
                .model(chatModel)
                .build();
        ChatCompletion completion = client.chat().completions().create(chatParams);
        if (completion.choices().isEmpty()) {
            throw new IllegalStateException("AI provider returned no choices");
        }
        Log.d(TAG, "analyzeTranscript usage: " + completion.usage());
        
        completion.usage().ifPresent(this::trackTokenUsage);

        return completion.choices().get(0).message().content().orElse("");
    }

    private void trackTokenUsage(CompletionUsage usage) {
        long input = usage.promptTokens();
        long output = usage.completionTokens();
        long total = usage.totalTokens();

        OpenAiPreferences.addAnalysisTokens(context, total);

        double cost = (input / 1_000_000.0 * PRICE_INPUT_PER_1M)
                + (output / 1_000_000.0 * PRICE_OUTPUT_PER_1M);
        OpenAiPreferences.addCost(context, cost);
    }

    private ChatModel resolveChatModel(String selectedModel) {
        if (TextUtils.isEmpty(selectedModel)) {
            return ChatModel.GPT_5_NANO;
        }
        switch (selectedModel) {
            case "gpt-5.1": return ChatModel.GPT_5_1;
            case "gpt-5-mini": return ChatModel.GPT_5_MINI;
            case "gpt-5-nano": return ChatModel.GPT_5_NANO;
            default:
                try {
                    return ChatModel.of(selectedModel);
                } catch (Exception e) {
                    Log.w(TAG, "Unknown model " + selectedModel + ", falling back to default", e);
                    return ChatModel.GPT_5_NANO;
                }
        }
    }

    @Override
    public void close() {
    }
}
