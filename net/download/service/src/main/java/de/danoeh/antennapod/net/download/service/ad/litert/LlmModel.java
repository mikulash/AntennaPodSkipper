package de.danoeh.antennapod.net.download.service.ad.litert;

/**
 * Available LLM models for local ad analysis.
 * Model files are downloaded from HuggingFace litert-community in .litertlm format.
 * Requires LiteRT-LM 0.8.1+ and LiteRT 2.1.0+.
 */
public enum LlmModel {
    GEMMA3_1B_IT(
            "gemma3-1b",
            "Gemma3 1B (Recommended)",
            "gemma3-1b-it-int4.litertlm",
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
            false,
            BackendType.GPU,
            2048,
            1.0f,
            64,
            0.95f,
            PromptFormat.GEMMA
    ),
    GEMMA3_1B_IT_4K(
            "gemma3-1b-4k",
            "Gemma3 1B (4K Context)",
            "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm",
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm",
            false,
            BackendType.GPU,
            4096,
            1.0f,
            64,
            0.95f,
            PromptFormat.GEMMA
    ),
    QWEN3_0_6B(
            "qwen3-0.6b",
            "Qwen3 0.6B (Fastest)",
            "Qwen3-0.6B.litertlm",
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            false,
            BackendType.CPU,
            2048,
            0.7f,
            40,
            0.9f,
            PromptFormat.QWEN3
    ),
    QWEN2_5_1_5B(
            "qwen2.5-1.5b",
            "Qwen 2.5 1.5B",
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            false,
            BackendType.GPU,
            4096,
            0.95f,
            40,
            1.0f,
            PromptFormat.CHATML
    ),
    DEEPSEEK_R1_QWEN_1_5B(
            "deepseek-r1-qwen-1.5b",
            "DeepSeek R1 Qwen 1.5B",
            "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            false,
            BackendType.CPU,
            4096,
            0.6f,
            40,
            0.7f,
            PromptFormat.CHATML
    ),
    PHI_4_MINI(
            "phi-4-mini",
            "Phi-4 Mini (Large)",
            "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            false,
            BackendType.CPU,
            4096,
            0.6f,
            40,
            1.0f,
            PromptFormat.PHI
    ),

    MANUAL_IMPORT(
            "manual_import",
            "Manual Import",
            "manual_import.litertlm",
            null,
            false,
            BackendType.GPU,
            2048,
            0.9f,
            64,
            0.95f,
            PromptFormat.GEMMA
    );

    /**
     * Backend type for LLM inference.
     */
    public enum BackendType {
        CPU,
        GPU
    }

    public enum PromptFormat {
        GEMMA,      // <start_of_turn>user\n{prompt}<end_of_turn>\n<start_of_turn>model\n
        LLAMA3,     // <|begin_of_text|><|start_header_id|>user<|end_header_id|>\n{prompt}<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n
        CHATML,     // <|im_start|>user\n{prompt}<|im_end|>\n<|im_start|>assistant\n
        PHI,        // <|user|>\n{prompt}<|end|>\n<|assistant|>\n
        QWEN3       // Same as CHATML but with thinking mode support
    }

    private final String id;
    private final String displayName;
    private final String filename;
    private final String url;
    private final boolean needsAuth;
    private final BackendType preferredBackend;
    private final int maxTokens;
    private final float temperature;
    private final int topK;
    private final float topP;
    private final PromptFormat promptFormat;

    LlmModel(String id, String displayName, String filename, String url, boolean needsAuth,
             BackendType preferredBackend, int maxTokens, float temperature, int topK, float topP,
             PromptFormat promptFormat) {
        this.id = id;
        this.displayName = displayName;
        this.filename = filename;
        this.url = url;
        this.needsAuth = needsAuth;
        this.preferredBackend = preferredBackend;
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

    public boolean needsAuth() {
        return needsAuth;
    }

    public BackendType getPreferredBackend() {
        return preferredBackend;
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
            case QWEN3:
                return "<|im_start|>user\n" + userMessage + "<|im_end|>\n<|im_start|>assistant\n";
            case PHI:
                return "<|user|>\n" + userMessage + "<|end|>\n<|assistant|>\n";
            default:
                return userMessage;
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
                QWEN3_0_6B,
                QWEN2_5_1_5B,
                DEEPSEEK_R1_QWEN_1_5B,
                PHI_4_MINI,
        };
    }

    /**
     * Returns recommended models for ad analysis (good balance of size/quality).
     */
    public static LlmModel[] getRecommendedModels() {
        return new LlmModel[]{
                GEMMA3_1B_IT,
                QWEN3_0_6B,
                QWEN2_5_1_5B,
        };
    }
}
