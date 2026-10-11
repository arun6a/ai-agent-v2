package com.ai.agent.llm

import android.content.Context
import android.content.SharedPreferences

/**
 * AI Provider configuration — lets the user pick which AI service to use.
 *
 * Supported providers:
 * - OpenRouter: Free models (Nemotron 120B, Cohere, etc.). OpenAI-compatible. https://openrouter.ai
 * - Gemini: Google's AI. Free tier available. https://aistudio.google.com
 * - Z.ai Proxy: Uses the sandbox proxy (no API key needed, but sandbox must be running)
 * - Custom: User enters any OpenAI-compatible endpoint + key + model
 *
 * The config is stored in SharedPreferences so it persists across app restarts.
 */
object AIProvider {

    private const val PREFS = "ai_agent_provider"
    private const val KEY_PROVIDER = "provider"          // "openrouter" | "gemini" | "zai" | "custom"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_MODEL = "model"
    private const val KEY_PROXY_BASE_URL = "proxy_base_url"  // Sandbox base URL for vision/web-search proxy
    private const val KEY_BROWSER_MODE = "browser_mode"  // "in_app" | "chrome" | "auto"

    // === Provider presets ===
    data class ProviderInfo(
        val id: String,
        val name: String,
        val description: String,
        val defaultEndpoint: String,
        val needsApiKey: Boolean,
        val models: List<ModelInfo>
    )

    data class ModelInfo(
        val id: String,
        val name: String,
        val description: String
    )

    val PROVIDERS = listOf(
        ProviderInfo(
            id = "openrouter",
            name = "OpenRouter (Free)",
            description = "Free models including Nemotron 120B, Cohere, and more. " +
                    "Get a free API key at openrouter.ai/keys. No daily quota.",
            defaultEndpoint = "https://openrouter.ai/api/v1/chat/completions",
            needsApiKey = true,
            models = listOf(
                ModelInfo("nvidia/nemotron-3-super-120b-a12b:free", "Nemotron 120B (Best)", "120B params, very capable, supports tool calling. Free."),
                ModelInfo("google/gemma-4-31b-it:free", "Gemma 4 31B (Vision!)", "31B Google model. VISION-capable — can analyze screenshots. Free."),
                ModelInfo("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free", "Nemotron Omni 30B (Vision+Audio)", "30B NVIDIA. VISION + AUDIO. Free."),
                ModelInfo("cohere/north-mini-code:free", "Cohere North Mini", "Good for coding tasks. Free."),
                ModelInfo("liquid/lfm-2.5-2.6b:free", "Liquid LFM 2.5 2.6B", "Very fast, small. Free.")
            )
        ),
        ProviderInfo(
            id = "groq",
            name = "Groq (Ultra Fast)",
            description = "Ultra-fast LLM inference on custom hardware (LPU). " +
                    "Free tier: 30 req/min, 14400/day. Get key at console.groq.com/keys",
            defaultEndpoint = "https://api.groq.com/openai/v1/chat/completions",
            needsApiKey = true,
            models = listOf(
                ModelInfo("llama-3.3-70b-versatile", "Llama 3.3 70B (Recommended)", "Meta's 70B model. Smart, capable. Free tier."),
                ModelInfo("llama-3.1-8b-instant", "Llama 3.1 8B Instant", "Very fast, good for chat. Free tier."),
                ModelInfo("llama-3.2-11b-vision-preview", "Llama 3.2 11B Vision (Vision!)", "VISION-capable — can analyze screenshots. Free."),
                ModelInfo("llama-3.2-90b-vision-preview", "Llama 3.2 90B Vision", "Most capable Groq vision model. Free."),
                ModelInfo("mixtral-8x7b-32768", "Mixtral 8x7B", "MoE model, fast. 32k context. Free."),
                ModelInfo("qwen-2.5-72b", "Qwen 2.5 72B", "Great for coding + multilingual. Free."),
                ModelInfo("deepseek-r1-distill-llama-70b", "DeepSeek R1 (Reasoning)", "Reasoning model. Free.")
            )
        ),
        ProviderInfo(
            id = "openai",
            name = "OpenAI (Paid)",
            description = "GPT-4o, GPT-4o-mini, o1 models. Paid. Get key at platform.openai.com/api-keys",
            defaultEndpoint = "https://api.openai.com/v1/chat/completions",
            needsApiKey = true,
            models = listOf(
                ModelInfo("gpt-4o", "GPT-4o", "Most capable OpenAI model. Paid."),
                ModelInfo("gpt-4o-mini", "GPT-4o mini (Cheap)", "Fast, cheap, capable. Paid."),
                ModelInfo("o1-mini", "o1 mini (Reasoning)", "Reasoning model. Paid."),
                ModelInfo("gpt-4-vision-preview", "GPT-4 Vision", "Vision-capable. Paid.")
            )
        ),
        ProviderInfo(
            id = "anthropic",
            name = "Anthropic (Paid)",
            description = "Claude models — excellent quality. Paid. Get key at console.anthropic.com",
            defaultEndpoint = "https://api.anthropic.com/v1/messages",
            needsApiKey = true,
            models = listOf(
                ModelInfo("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet (Best)", "Most capable Claude. Excellent quality. Paid."),
                ModelInfo("claude-3-5-haiku-20241022", "Claude 3.5 Haiku (Fast)", "Fast, cheap. Paid."),
                ModelInfo("claude-3-opus-20240229", "Claude 3 Opus", "Legacy flagship. Paid.")
            )
        ),
        ProviderInfo(
            id = "cerebras",
            name = "Cerebras (Ultra Fast)",
            description = "Ultra-fast inference on CS-3 wafer-scale hardware. Free tier. Get key at cerebras.ai",
            defaultEndpoint = "https://api.cerebras.ai/v1/chat/completions",
            needsApiKey = true,
            models = listOf(
                ModelInfo("llama-3.3-70b", "Llama 3.3 70B (Fast)", "70B on Cerebras — fastest 70B. Free."),
                ModelInfo("llama-3.1-8b", "Llama 3.1 8B (Ultra Fast)", "Extremely fast. Free.")
            )
        ),
        ProviderInfo(
            id = "mistral",
            name = "Mistral AI",
            description = "Mistral models — native function calling. Free tier (La Plateforme). Get key at console.mistral.ai",
            defaultEndpoint = "https://api.mistral.ai/v1/chat/completions",
            needsApiKey = true,
            models = listOf(
                ModelInfo("mistral-large-latest", "Mistral Large (Best)", "Most capable Mistral. Free tier."),
                ModelInfo("mistral-small-latest", "Mistral Small (Fast)", "Fast, cheap. Free tier."),
                ModelInfo("codestral-latest", "Codestral", "Code generation specialist. Free."),
                ModelInfo("pixtral-large-latest", "Pixtral Large (Vision!)", "Vision-capable. Free tier.")
            )
        ),
        ProviderInfo(
            id = "deepinfra",
            name = "DeepInfra (Cheap)",
            description = "Many open-source models, cheap. Free credit on signup. Get key at deepinfra.com",
            defaultEndpoint = "https://api.deepinfra.com/v1/openai/chat/completions",
            needsApiKey = true,
            models = listOf(
                ModelInfo("meta-llama/Llama-3.3-70B-Instruct", "Llama 3.3 70B", "70B model. Cheap."),
                ModelInfo("meta-llama/Meta-Llama-3.1-405B-Instruct", "Llama 3.1 405B", "Most capable. Cheap."),
                ModelInfo("Qwen/Qwen2.5-72B-Instruct", "Qwen 2.5 72B", "Great for coding. Cheap."),
                ModelInfo("deepseek-ai/DeepSeek-R1", "DeepSeek R1 (Reasoning)", "Reasoning model. Cheap.")
            )
        ),
        ProviderInfo(
            id = "fireworks",
            name = "Fireworks AI",
            description = "Fast inference, many models. Free trial. Get key at fireworks.ai",
            defaultEndpoint = "https://api.fireworks.ai/inference/v1/chat/completions",
            needsApiKey = true,
            models = listOf(
                ModelInfo("accounts/fireworks/models/llama-v3p3-70b-instruct", "Llama 3.3 70B", "70B on Fireworks. Free trial."),
                ModelInfo("accounts/fireworks/models/qwen2p5-72b-instruct", "Qwen 2.5 72B", "Coding + multilingual. Free trial."),
                ModelInfo("accounts/fireworks/models/deepseek-r1", "DeepSeek R1 (Reasoning)", "Reasoning. Free trial.")
            )
        ),
        ProviderInfo(
            id = "together",
            name = "Together AI",
            description = "Many open-source models (Llama, Qwen, Mistral). " +
                    "$5 free credit on signup. Get key at api.together.xyz/settings/api-keys",
            defaultEndpoint = "https://api.together.xyz/v1/chat/completions",
            needsApiKey = true,
            models = listOf(
                ModelInfo("meta-llama/Llama-3.3-70B-Instruct-Turbo", "Llama 3.3 70B Turbo", "Fast, capable. Paid."),
                ModelInfo("meta-llama/Meta-Llama-3.1-405B-Instruct-Turbo", "Llama 3.1 405B", "Most capable. Paid."),
                ModelInfo("Qwen/Qwen2.5-72B-Instruct-Turbo", "Qwen 2.5 72B", "Great for coding. Paid."),
                ModelInfo("meta-llama/Llama-Vision-Free", "Llama Vision (Free)", "VISION-capable. Free.")
            )
        ),
        ProviderInfo(
            id = "gemini",
            name = "Google Gemini",
            description = "Google's AI. Free tier: 15 req/min, 1500/day. " +
                    "Get API key at aistudio.google.com/apikey",
            defaultEndpoint = "https://generativelanguage.googleapis.com/v1beta",
            needsApiKey = true,
            models = listOf(
                ModelInfo("gemini-3.8-flash", "Gemini 3.8 Flash (Latest)", "Newest Gemini. Fast, capable, vision. Free tier."),
                ModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash", "Fast, capable. Free tier."),
                ModelInfo("gemini-2.0-flash", "Gemini 2.0 Flash (Legacy)", "Older but stable. May be deprecated. Free tier."),
                ModelInfo("gemini-1.5-pro", "Gemini 1.5 Pro", "Most capable, slower. Free tier.")
            )
        ),
        ProviderInfo(
            id = "zai_direct",
            name = "Z.ai Direct (Official API)",
            description = "Calls Z.ai's official API DIRECTLY from your phone — no sandbox needed. " +
                    "Works anywhere, anytime (even when sandbox is offline). OpenAI-compatible. " +
                    "Get an API key at z.ai (API Keys section). Supports native function calling. " +
                    "Many GLM models available including the new GLM-5.3 flagship and GLM-5V vision.",
            defaultEndpoint = "https://api.z.ai/api/paas/v4/chat/completions",
            needsApiKey = true,
            models = listOf(
                // === GLM-5.x series (newest, smartest) ===
                ModelInfo("glm-5.3", "GLM-5.3 (Flagship, Newest)", "Z.ai's NEW flagship. Best reasoning, coding, agentic. Most capable."),
                ModelInfo("glm-5.3-flash", "GLM-5.3 Flash (Fast)", "Fast version of 5.3. Good balance of speed + capability."),
                ModelInfo("glm-5.3-flashx", "GLM-5.3 FlashX (Fastest)", "Ultra-fast variant. Best for simple tasks."),
                ModelInfo("glm-5.2", "GLM-5.2", "Previous flagship. Still very capable."),
                ModelInfo("glm-5.1", "GLM-5.1", "Solid all-rounder."),
                ModelInfo("glm-5", "GLM-5", "First GLM-5. Stable, capable."),
                // === GLM-4.x series (mature, stable) ===
                ModelInfo("glm-4.7", "GLM-4.7", "Latest 4.x. Reliable, fast."),
                ModelInfo("glm-4.6", "GLM-4.6 (Battle-tested)", "Proven flagship. Most tested, stable. Supports tools."),
                ModelInfo("glm-4.5", "GLM-4.5", "Capable, balanced."),
                ModelInfo("glm-4.5-air", "GLM-4.5 Air (Light)", "Lightweight variant. Fast."),
                ModelInfo("glm-4.5-flash", "GLM-4.5 Flash (Fast)", "Fast, cheap. Good for simple tasks."),
                ModelInfo("glm-4.5-x", "GLM-4.5 X", "Extended context variant."),
                // === Vision (VLM) ===
                ModelInfo("glm-5v-turbo", "GLM-5V Turbo (Vision!)", "NEW vision model. Analyze screenshots, UI. Fast."),
                ModelInfo("glm-4.5v", "GLM-4.5V (Vision)", "Vision-capable. Can analyze screenshots."),
                ModelInfo("glm-4.1v", "GLM-4.1V (Vision, Legacy)", "Older vision model. May be deprecated.")
            )
        ),
        ProviderInfo(
            id = "zai",
            name = "Z.ai Proxy (Sandbox)",
            description = "Uses the sandbox proxy. No API key needed, but the sandbox " +
                    "dev server must be running. GLM-4.6 model. 300 requests/day. " +
                    "Only works while sandbox is active.",
            defaultEndpoint = "https://preview-chat-1855dd56-e3a8-4ac4-a21c-824fbab8e552.space-z.ai/api/llm/proxy",
            needsApiKey = false,
            models = listOf(
                ModelInfo("glm-4.6", "GLM-4.6", "Z.ai's flagship model. Smart, supports tools."),
                ModelInfo("glm-4-flash", "GLM-4 Flash", "Faster, less capable.")
            )
        ),
        ProviderInfo(
            id = "custom",
            name = "Custom (OpenAI-compatible)",
            description = "Use any OpenAI-compatible API. Enter endpoint, API key, and model name. " +
                    "Works with: OpenAI, Ollama, LM Studio, vLLM, etc.",
            defaultEndpoint = "",
            needsApiKey = true,
            models = listOf(
                ModelInfo("gpt-4o-mini", "GPT-4o mini", "OpenAI's cheap model. Requires OpenAI API key."),
                ModelInfo("llama-3.3-70b", "Llama 3.3 70B", "Meta's Llama. Use with any OpenAI-compatible server."),
                ModelInfo("custom", "Custom Model", "Enter your model name in the model field.")
            )
        )
    )

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun getProviderId(context: Context): String {
        return getPrefs(context).getString(KEY_PROVIDER, "zai") ?: "zai"
    }

    fun setProvider(context: Context, providerId: String) {
        getPrefs(context).edit().putString(KEY_PROVIDER, providerId).apply()
    }

    fun getApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_API_KEY, key).apply()
    }

    fun getEndpoint(context: Context): String {
        val providerId = getProviderId(context)
        val default = PROVIDERS.find { it.id == providerId }?.defaultEndpoint ?: ""
        return getPrefs(context).getString(KEY_ENDPOINT, default) ?: default
    }

    fun setEndpoint(context: Context, endpoint: String) {
        getPrefs(context).edit().putString(KEY_ENDPOINT, endpoint).apply()
    }

    fun getModel(context: Context): String {
        val providerId = getProviderId(context)
        val default = PROVIDERS.find { it.id == providerId }?.models?.firstOrNull()?.id ?: "glm-4.6"
        return getPrefs(context).getString(KEY_MODEL, default) ?: default
    }

    fun setModel(context: Context, model: String) {
        getPrefs(context).edit().putString(KEY_MODEL, model).apply()
    }

    fun getCurrentProvider(context: Context): ProviderInfo {
        val id = getProviderId(context)
        return PROVIDERS.find { it.id == id } ?: PROVIDERS.first { it.id == "zai" }
    }

    /**
     * Check if the current provider is fully configured (has API key if needed).
     */
    fun isConfigured(context: Context): Boolean {
        val provider = getCurrentProvider(context)
        if (!provider.needsApiKey) return true
        return getApiKey(context).isNotEmpty()
    }

    // === Sandbox proxy base URL ===
    // Used by tools (webSearch, analyzeScreen) that need a sandbox-side proxy
    // because OpenRouter doesn't have free vision and we need Z.ai's VLM.
    // The URL is configurable so users can point to their own sandbox without rebuilding.
    //
    // Default: current Z.ai Code sandbox URL.
    // Set via setProxyBaseUrl() (e.g., from Settings).
    val DEFAULT_PROXY_BASE_URL = "https://preview-chat-1855dd56-e3a8-4ac4-a21c-824fbab8e552.space-z.ai"

    fun getProxyBaseUrl(context: Context): String {
        return getPrefs(context).getString(KEY_PROXY_BASE_URL, DEFAULT_PROXY_BASE_URL)
            ?: DEFAULT_PROXY_BASE_URL
    }

    fun setProxyBaseUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_PROXY_BASE_URL, url.trimEnd('/')).apply()
    }

    /** Full URL for the LLM proxy endpoint (POST /api/llm/proxy). */
    fun getLlmProxyUrl(context: Context): String =
        getProxyBaseUrl(context).trimEnd('/') + "/api/llm/proxy"

    /** Full URL for the vision endpoint (POST /api/llm/vision). */
    fun getVisionUrl(context: Context): String =
        getProxyBaseUrl(context).trimEnd('/') + "/api/llm/vision"

    /** Full URL for the web-search endpoint (POST /api/web-search). */
    fun getWebSearchUrl(context: Context): String =
        getProxyBaseUrl(context).trimEnd('/') + "/api/web-search"

    // === Search Method (v5.1.1) ===
    // Controls which search engine webSearch() uses:
    // "duckduckgo" → DuckDuckGo HTML (default, works directly, no proxy)
    // "google" → Google search (may get blocked by anti-bot)
    // "bing" → Bing search
    // "proxy" → sandbox proxy (needs sandbox running)
    private const val KEY_SEARCH_METHOD = "search_method"
    val DEFAULT_SEARCH_METHOD = "duckduckgo"

    fun getSearchMethod(context: Context): String {
        return getPrefs(context).getString(KEY_SEARCH_METHOD, DEFAULT_SEARCH_METHOD) ?: DEFAULT_SEARCH_METHOD
    }

    fun setSearchMethod(context: Context, method: String) {
        getPrefs(context).edit().putString(KEY_SEARCH_METHOD, method).apply()
    }

    // === Browser Mode ===
    // Controls which browser the AI uses:
    // "in_app" → visible in-app WebView (no accessibility needed, user can see + intervene)
    // "chrome" → Chrome app via Intent (needs accessibility, uses Chrome login state)
    // "auto" → try in-app first, suggest Chrome for logged-in sites
    val DEFAULT_BROWSER_MODE = "auto"

    fun getBrowserMode(context: Context): String {
        return getPrefs(context).getString(KEY_BROWSER_MODE, DEFAULT_BROWSER_MODE) ?: DEFAULT_BROWSER_MODE
    }

    fun setBrowserMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_BROWSER_MODE, mode).apply()
    }

    // === Prompt Mode (v6.3.0) ===
    // "dynamic" → auto-selects relevant tools per task (default, saves tokens)
    // "smart" → full prompt (all tools + tips + examples, ~11K tokens)
    // "balanced" → trimmed prompt (core tools + key rules, ~1.5K tokens)
    // "fast" → minimal prompt (tool names + 8 rules, ~750 tokens)
    // "custom" → user-edited prompt from storage
    private const val KEY_PROMPT_MODE = "prompt_mode"
    val DEFAULT_PROMPT_MODE = "smart"  // v6.3.2: dynamic disabled by default, user can enable

    fun getPromptMode(context: Context): String {
        return getPrefs(context).getString(KEY_PROMPT_MODE, DEFAULT_PROMPT_MODE) ?: DEFAULT_PROMPT_MODE
    }

    fun setPromptMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_PROMPT_MODE, mode).apply()
    }
}
