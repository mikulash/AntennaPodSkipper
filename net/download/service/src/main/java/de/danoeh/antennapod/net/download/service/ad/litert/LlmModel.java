package de.danoeh.antennapod.net.download.service.ad.litert;

/**
 * Available LLM models for local ad analysis.
 * Model files are downloaded from HuggingFace litert-community.
 */
public enum LlmModel {
    GEMMA3_1B_IT_GPU(
            "gemma3-1b-gpu",
            "Gemma3 1B (GPU)",
            "Gemma3-1B-IT_multi-prefill-seq_q8_ekv2048_gpu.task",
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q8_ekv2048.task",
            true,
            BackendType.GPU,
            2048,
            1.0f,
            64,
            0.95f,
            PromptFormat.GEMMA
    ),
    GEMMA2_2B_IT_GPU(
            "gemma2-2b-gpu",
            "Gemma2 2B (GPU)",
            "Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.task",
            "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.task",
            true,
            BackendType.GPU,
            1280,
            0.9f,
            64,
            0.95f,
            PromptFormat.GEMMA
    ),
    GEMMA3_4B_IT_INT8_GPU(
            "gemma3-4b-gpu",
            "Gemma3 4B (GPU, int8)",
            "gemma3-4b-it-int8-web.task",
            "https://huggingface.co/litert-community/Gemma3-4B-IT/resolve/main/gemma3-4b-it-int8-web.task",
            true,
            BackendType.GPU,
            2048,
            0.9f,
            64,
            0.95f,
            PromptFormat.GEMMA
    ),

    DEEPSEEK_R1_QWEN_1_5B(
            "deepseek-r1-qwen-1.5b",
            "DeepSeek R1 Qwen 1.5B",
            "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task",
            "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task",
            false,
            BackendType.CPU,
            1280,
            0.6f,
            40,
            0.7f,
            PromptFormat.CHATML
    ),
     LLAMA_3_2_3B(
            "llama-3.2-3b",
            "Llama 3.2 3B",
            "Llama-3.2-3B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "https://huggingface.co/litert-community/Llama-3.2-3B-Instruct/resolve/main/Llama-3.2-3B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            true,
            BackendType.CPU,
            1280,
            0.6f,
            64,
            0.9f,
            PromptFormat.LLAMA3
    ),
    PHI_4_MINI(
            "phi-4-mini",
            "Phi-4 Mini",
            "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task",
            "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task",
            false,
            BackendType.CPU,
            1280,
            0.6f,
            40,
            1.0f,
            PromptFormat.PHI
    ),
    QWEN2_5_0_5B(
            "qwen2.5-0.5b",
            "Qwen 2.5 0.5B (Fastest)",
            "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            false,
            BackendType.CPU,
            1280,
            0.95f,
            40,
            1.0f,
            PromptFormat.CHATML
    ),
    QWEN2_5_1_5B(
            "qwen2.5-1.5b",
            "Qwen 2.5 1.5B",
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            false,
            BackendType.GPU,
            1280,
            0.95f,
            40,
            1.0f,
            PromptFormat.CHATML
    ),

    MANUAL_IMPORT(
            "manual_import",
            "Manual Import",
            "manual_import.task",
            null,
            false,
            BackendType.GPU,
            2000,
            0.9f,
            64,
            0.95f,
            PromptFormat.GEMMA
    );

    /**
     * Backend type for LLM inference (decoupled from MediaPipe).
     */
    public enum BackendType {
        CPU,
        GPU
    }

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
                QWEN2_5_0_5B,
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
                GEMMA3_4B_IT_INT8_GPU,
                GEMMA2_2B_IT_GPU,
                QWEN2_5_1_5B,
                PHI_4_MINI
        };
    }
}
