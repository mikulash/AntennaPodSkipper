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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.danoeh.antennapod.storage.preferences.OpenAiPreferences;

@RequiresApi(api = Build.VERSION_CODES.O)
public class OpenAiTranscriptAnalysisProvider implements TranscriptAnalysisProvider {
    private static final String TAG = "OpenAiTranscriptAnalysisProv";
    private static final String DEFAULT_MODEL_NAME = "gpt-5-nano";

    // Pricing (Estimated)
    private static final double PRICE_INPUT_PER_1M = 0.15; // $0.15 per 1M input tokens
    private static final double PRICE_OUTPUT_PER_1M = 0.60; // $0.60 per 1M output tokens

    // Base system message for ad classification
    private static final String SYSTEM_MESSAGE_BASE =
            "You are a classifier that only finds advertisement or sponsor segments in podcasts. "
            + "An advertisement is a sponsor read, mid-roll, pre-roll, post-roll, "
            + "or explicit promotion (coupon codes, giveaways, discounts). "
            + "Do not tag normal banter, housekeeping, or episode content as ads. "
            + "Use seconds from start of episode for times. "
            + "Respond ONLY with valid JSON matching {\"ads\":[{\"startSeconds\":number,\"endSeconds\":number,"
            + "\"reason\":string,\"confidence\":number}]} and nothing else.";

    private final Context context;
    private final OpenAIClient client;
    private final String modelName;

    public OpenAiTranscriptAnalysisProvider(Context context) {
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
        return analyzeTranscript(prompt, null);
    }

    @Override
    public String analyzeTranscript(String prompt, ProgressListener listener) throws Exception {
        if (listener != null) {
            listener.onProgress(10);
        }

        // Extract transcript and duration from the prompt
        int durationMs = extractDuration(prompt);

        // Build system message and user message separately
        String systemMessage = buildSystemMessage(durationMs);
        String userMessage = buildUserMessage(prompt);

        ChatModel chatModel = resolveChatModel(modelName);
        ChatCompletionCreateParams chatParams = ChatCompletionCreateParams.builder()
                .addSystemMessage(systemMessage)
                .addUserMessage(userMessage)
                .model(chatModel)
                .build();
        if (listener != null) {
            listener.onProgress(30);
        }
        ChatCompletion completion = client.chat().completions().create(chatParams);
        if (completion.choices().isEmpty()) {
            throw new IllegalStateException("AI provider returned no choices");
        }

        if (listener != null) {
            listener.onProgress(100);
        }
        return completion.choices().get(0).message().content().orElse("");
    }

    /**
     * Build the system message with context about the episode.
     */
    private String buildSystemMessage(int durationMs) {
        StringBuilder sb = new StringBuilder(SYSTEM_MESSAGE_BASE);
        if (durationMs > 0) {
            sb.append("\n\nEpisode duration: ").append(durationMs / 1000f).append(" seconds.");
        }
        return sb.toString();
    }

    /**
     * Build the user message containing the transcript.
     */
    private String buildUserMessage(String transcript) {
        return "Analyze this transcript for ads:\n\n" + transcript + "\n\nOutput only JSON.";
    }


    private int extractDuration(String prompt) {
        Pattern pattern = Pattern.compile("Episode duration seconds: ([\\d.]+)");
        Matcher matcher = pattern.matcher(prompt);
        if (matcher.find()) {
            try {
                return (int) (Float.parseFloat(matcher.group(1)) * 1000);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
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
