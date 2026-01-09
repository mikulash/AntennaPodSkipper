package de.danoeh.antennapod.net.ai.service.ad.litert;

/**
 * Available LLM models for local ad analysis.
 * Model files are downloaded from HuggingFace in .litertlm format.
 * Requires LiteRT-LM 0.8.1+ and LiteRT 2.1.0+.
 */
public enum LlmModel {
    GEMMA3N_E2B_IT(
            "gemma-3n-e2b",
            "Gemma 3n E2B",
            "gemma-3n-E2B-it-int4.litertlm",
            "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
            "Multimodal model with text, vision and audio input. 4096 context.",
            3655827456L,
            8,
            false,
            4096,
            1.0f,
            64,
            0.95f,
            PromptFormat.GEMMA
    ),
    GEMMA3N_E4B_IT(
            "gemma-3n-e4b",
            "Gemma 3n E4B",
            "gemma-3n-E4B-it-int4.litertlm",
            "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",
            "Larger multimodal model with text, vision and audio. 4096 context.",
            4919541760L,
            12,
            false,
            4096,
            1.0f,
            64,
            0.95f,
            PromptFormat.GEMMA
    ),
    GEMMA3_1B_IT(
            "gemma3-1b",
            "Gemma3 1B (Recommended)",
            "gemma3-1b-it-int4.litertlm",
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
            "Fast and efficient 1B parameter model. Great for ad analysis.",
            584417280L,
            6,
            false,
            1024,
            1.0f,
            64,
            0.95f,
            PromptFormat.GEMMA
    ),
    QWEN2_5_1_5B(
            "qwen2.5-1.5b",
            "Qwen 2.5 1.5B",
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            "Alibaba's instruction-tuned model. 4096 context length.",
            1597931520L,
            6,
            false,
            4096,
            0.7f,
            20,
            0.8f,
            PromptFormat.CHATML
    ),
    PHI_4_MINI(
            "phi-4-mini",
            "Phi-4 Mini",
            "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            "Microsoft's compact but capable model. 4096 context.",
            3910090752L,
            6,
            false,
            4096,
            1.0f,
            64,
            0.95f,
            PromptFormat.PHI
    ),
    DEEPSEEK_R1_QWEN_1_5B(
            "deepseek-r1-qwen-1.5b",
            "DeepSeek R1 Qwen 1.5B",
            "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            "DeepSeek's reasoning model distilled to 1.5B. 4096 context.",
            1833451520L,
            6,
            false,
            4096,
            1.0f,
            64,
            0.95f,
            PromptFormat.CHATML
    ),

    MANUAL_IMPORT(
            "manual_import",
            "Manual Import",
            "manual_import.litertlm",
            null,
            "Manually imported model file.",
            0L,
            6,
            false,
            2048,
            0.9f,
            64,
            0.95f,
            PromptFormat.GEMMA
    );

    public enum PromptFormat {
        GEMMA,      // <start_of_turn>user\n{prompt}<end_of_turn>\n<start_of_turn>model\n
        LLAMA3,     // <|begin_of_text|><|start_header_id|>user<|end_header_id|>\n{prompt}<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n
        CHATML,     // <|im_start|>user\n{prompt}<|im_end|>\n<|im_start|>assistant\n
        PHI         // <|user|>\n{prompt}<|end|>\n<|assistant|>\n
    }

    private final String id;
    private final String displayName;
    private final String filename;
    private final String url;
    private final String description;
    private final long sizeInBytes;
    private final int minDeviceMemoryGb;
    private final boolean needsAuth;
    private final int maxTokens;
    private final float temperature;
    private final int topK;
    private final float topP;
    private final PromptFormat promptFormat;

    LlmModel(String id, String displayName, String filename, String url, String description,
             long sizeInBytes, int minDeviceMemoryGb, boolean needsAuth,
             int maxTokens, float temperature, int topK, float topP,
             PromptFormat promptFormat) {
        this.id = id;
        this.displayName = displayName;
        this.filename = filename;
        this.url = url;
        this.description = description;
        this.sizeInBytes = sizeInBytes;
        this.minDeviceMemoryGb = minDeviceMemoryGb;
        this.needsAuth = needsAuth;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.topK = topK;
        this.topP = topP;
        this.promptFormat = promptFormat;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getFilename() {
        return filename;
    }

    public String getUrl() {
        return url;
    }

    public String getDescription() {
        return description;
    }

    public long getSizeInBytes() {
        return sizeInBytes;
    }

    public int getMinDeviceMemoryGb() {
        return minDeviceMemoryGb;
    }

    public boolean needsAuth() {
        return needsAuth;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public float getTemperature() {
        return temperature;
    }

    public int getTopK() {
        return topK;
    }

    public float getTopP() {
        return topP;
    }

    public PromptFormat getPromptFormat() {
        return promptFormat;
    }

    public String formatPrompt(String userMessage) {
        switch (promptFormat) {
            case GEMMA:
                return "<start_of_turn>user\n" + userMessage + "<end_of_turn>\n<start_of_turn>model\n";
            case LLAMA3:
                return "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n" + userMessage
                        + "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n";
            case CHATML:
                return "<|im_start|>user\n" + userMessage + "<|im_end|>\n<|im_start|>assistant\n";
            case PHI:
                return "<|user|>\n" + userMessage + "<|end|>\n<|assistant|>\n";
            default:
                return userMessage;
        }
    }

    /**
     * Returns a formatted size string (e.g., "1.5 GB").
     */
    public String getFormattedSize() {
        if (sizeInBytes <= 0) return "Unknown";
        double gb = sizeInBytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1.0) {
            return String.format("%.1f GB", gb);
        } else {
            double mb = sizeInBytes / (1024.0 * 1024.0);
            return String.format("%.0f MB", mb);
        }
    }

    public static LlmModel fromId(String id) {
        for (LlmModel model : values()) {
            if (model.id.equals(id)) {
                return model;
            }
        }
        return null;
    }

    /**
     * Returns models that don't require authentication (easier to download).
     */
    public static LlmModel[] getPublicModels() {
        return new LlmModel[]{
                GEMMA3_1B_IT,
                GEMMA3N_E2B_IT,
                GEMMA3N_E4B_IT,
                QWEN2_5_1_5B,
                PHI_4_MINI,
                DEEPSEEK_R1_QWEN_1_5B,
        };
    }

    /**
     * Returns recommended models for ad analysis (good balance of size/quality).
     */
    public static LlmModel[] getRecommendedModels() {
        return new LlmModel[]{
                GEMMA3_1B_IT,
                QWEN2_5_1_5B,
        };
    }
}
