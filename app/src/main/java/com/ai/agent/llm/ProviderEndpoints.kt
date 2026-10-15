package com.ai.agent.llm

/**
 * v3.3.1: ProviderEndpoints — single source of truth for known provider endpoints.
 *
 * Each popular provider (OpenAI, Groq, Anthropic, etc.) has:
 *   - apiKeyUrl: direct URL to create an API key (opened in browser via "Get API Key" button)
 *   - docsUrl: link to the provider's docs (from models.dev `doc` field)
 *   - chatEndpoint: full URL for POST chat completions
 *   - modelsEndpoint: full URL for GET model discovery
 *   - authScheme: how the API key is sent (Bearer header / x-api-key / query param / none)
 *
 * For the "custom" provider, the user supplies all values manually.
 *
 * Used by:
 *   - AddApiKeyDialog (auto-fill endpoint when user picks a provider)
 *   - ProviderModelsStore.fetchAvailableModels (use modelsEndpoint instead of mangling baseUrl)
 *   - testApiKeyConnection / testSavedModel (correct auth scheme per provider)
 *   - ModelsDevRepository (normalize catalog provider IDs to internal IDs)
 */
object ProviderEndpoints {

    enum class AuthScheme {
        BEARER,          // OpenAI, Groq, OpenRouter, Together, etc. — Authorization: Bearer <key>
        X_API_KEY,       // Anthropic — x-api-key: <key>
        QUERY_PARAM,     // Gemini — ?key=<key>
        NONE,            // Sandbox — no auth header
    }

    data class ProviderEndpoint(
        val providerId: String,            // "openai", "groq", "zai", etc.
        val displayName: String,
        val apiKeyUrl: String,             // direct URL to create API key
        val docsUrl: String,              // provider docs
        val chatEndpoint: String,         // full URL for POST chat completions
        val modelsEndpoint: String,       // full URL for GET /v1/models
        val authScheme: AuthScheme,
        val modelsDevAliases: List<String> = emptyList(),  // models.dev IDs that map to this
        val modelsDevNpm: String? = null,  // match against catalog's `npm` field
    )

    /**
     * Registry of known popular providers.
     *
     * Endpoints verified against each provider's official documentation.
     * The `modelsEndpoint` is what we hit for auto-discovery (/v1/models).
     * The `chatEndpoint` is what LLMClient POSTs to.
     */
    val ALL: List<ProviderEndpoint> = listOf(
        ProviderEndpoint(
            providerId = "openai",
            displayName = "OpenAI",
            apiKeyUrl = "https://platform.openai.com/api-keys",
            docsUrl = "https://platform.openai.com/docs/models",
            chatEndpoint = "https://api.openai.com/v1/chat/completions",
            modelsEndpoint = "https://api.openai.com/v1/models",
            authScheme = AuthScheme.BEARER,
            modelsDevAliases = listOf("openai"),
            modelsDevNpm = "@ai-sdk/openai",
        ),
        ProviderEndpoint(
            providerId = "groq",
            displayName = "Groq",
            apiKeyUrl = "https://console.groq.com/keys",
            docsUrl = "https://console.groq.com/docs/models",
            chatEndpoint = "https://api.groq.com/openai/v1/chat/completions",
            modelsEndpoint = "https://api.groq.com/openai/v1/models",
            authScheme = AuthScheme.BEARER,
            modelsDevAliases = listOf("groq"),
            modelsDevNpm = "@ai-sdk/groq",
        ),
        ProviderEndpoint(
            providerId = "anthropic",
            displayName = "Anthropic (Claude)",
            apiKeyUrl = "https://console.anthropic.com/settings/keys",
            docsUrl = "https://docs.anthropic.com/en/docs/about-claude/models",
            chatEndpoint = "https://api.anthropic.com/v1/messages",
            modelsEndpoint = "https://api.anthropic.com/v1/models",
            authScheme = AuthScheme.X_API_KEY,
            modelsDevAliases = listOf("anthropic"),
            modelsDevNpm = "@ai-sdk/anthropic",
        ),
        ProviderEndpoint(
            providerId = "openrouter",
            displayName = "OpenRouter",
            apiKeyUrl = "https://openrouter.ai/settings/keys",
            docsUrl = "https://openrouter.ai/models",
            chatEndpoint = "https://openrouter.ai/api/v1/chat/completions",
            modelsEndpoint = "https://openrouter.ai/api/v1/models",
            authScheme = AuthScheme.BEARER,
            modelsDevAliases = listOf("openrouter"),
            modelsDevNpm = "@openrouter/ai-sdk-provider",
        ),
        ProviderEndpoint(
            providerId = "together",
            displayName = "Together AI",
            apiKeyUrl = "https://api.together.ai/settings/api-keys",
            docsUrl = "https://docs.together.ai/docs/serverless-models",
            chatEndpoint = "https://api.together.xyz/v1/chat/completions",
            modelsEndpoint = "https://api.together.xyz/v1/models",
            authScheme = AuthScheme.BEARER,
            modelsDevAliases = listOf("togetherai"),
            modelsDevNpm = "@ai-sdk/togetherai",
        ),
        ProviderEndpoint(
            providerId = "mistral",
            displayName = "Mistral AI",
            apiKeyUrl = "https://console.mistral.ai/api-keys",
            docsUrl = "https://docs.mistral.ai/getting-started/models/",
            chatEndpoint = "https://api.mistral.ai/v1/chat/completions",
            modelsEndpoint = "https://api.mistral.ai/v1/models",
            authScheme = AuthScheme.BEARER,
            modelsDevAliases = listOf("mistral"),
            modelsDevNpm = "@ai-sdk/mistral",
        ),
        ProviderEndpoint(
            providerId = "deepinfra",
            displayName = "Deep Infra",
            apiKeyUrl = "https://deepinfra.com/dash/api_keys",
            docsUrl = "https://deepinfra.com/models",
            chatEndpoint = "https://api.deepinfra.com/v1/openai/chat/completions",
            modelsEndpoint = "https://api.deepinfra.com/v1/openai/models",
            authScheme = AuthScheme.BEARER,
            modelsDevAliases = listOf("deepinfra"),
            modelsDevNpm = "@ai-sdk/deepinfra",
        ),
        ProviderEndpoint(
            providerId = "fireworks",
            displayName = "Fireworks AI",
            apiKeyUrl = "https://fireworks.ai/account/api-keys",
            docsUrl = "https://fireworks.ai/docs/",
            chatEndpoint = "https://api.fireworks.ai/inference/v1/chat/completions",
            modelsEndpoint = "https://api.fireworks.ai/inference/v1/models",
            authScheme = AuthScheme.BEARER,
            modelsDevAliases = listOf("fireworks-ai"),
            modelsDevNpm = "@ai-sdk/openai-compatible",
        ),
        ProviderEndpoint(
            providerId = "cerebras",
            displayName = "Cerebras",
            apiKeyUrl = "https://cloud.cerebras.ai/",
            docsUrl = "https://inference-docs.cerebras.ai/models/overview",
            chatEndpoint = "https://api.cerebras.ai/v1/chat/completions",
            modelsEndpoint = "https://api.cerebras.ai/v1/models",
            authScheme = AuthScheme.BEARER,
            modelsDevAliases = listOf("cerebras"),
            modelsDevNpm = "@ai-sdk/cerebras",
        ),
        ProviderEndpoint(
            providerId = "gemini",
            displayName = "Google Gemini",
            apiKeyUrl = "https://aistudio.google.com/app/apikey",
            docsUrl = "https://ai.google.dev/gemini-api/docs/models",
            // Gemini uses a different API shape — query param auth, different endpoint structure.
            // The LLMClient has a dedicated buildGeminiRequest path for this.
            // modelsEndpoint uses ?key=<apiKey> at fetch time.
            chatEndpoint = "https://generativelanguage.googleapis.com/v1beta",
            modelsEndpoint = "https://generativelanguage.googleapis.com/v1beta/models",
            authScheme = AuthScheme.QUERY_PARAM,
            modelsDevAliases = listOf("google"),
            modelsDevNpm = "@ai-sdk/google",
        ),
        ProviderEndpoint(
            providerId = "zai",
            displayName = "Z.AI",
            apiKeyUrl = "https://open.bigmodel.cn/usercenter/apikeys",
            docsUrl = "https://docs.z.ai/guides/overview/pricing",
            chatEndpoint = "https://api.z.ai/api/paas/v4/chat/completions",
            modelsEndpoint = "https://api.z.ai/api/paas/v4/models",
            authScheme = AuthScheme.BEARER,
            modelsDevAliases = listOf("zai", "zhipuai"),
            modelsDevNpm = "@ai-sdk/openai-compatible",
        ),
        ProviderEndpoint(
            providerId = "apikeys_in",
            displayName = "APIKeys.in (Free Gateway)",
            apiKeyUrl = "https://apikeys.in/",
            docsUrl = "https://apikeys.in/",
            chatEndpoint = "https://apikeys.in/v1/chat/completions",
            modelsEndpoint = "https://apikeys.in/v1/models",
            authScheme = AuthScheme.BEARER,
            modelsDevAliases = listOf("apikeys_in", "apikeys-in"),
            modelsDevNpm = null,
        ),
        ProviderEndpoint(
            providerId = "cline",
            displayName = "Cline",
            apiKeyUrl = "https://cline.bot/",
            docsUrl = "https://cline.bot/",
            chatEndpoint = "https://api.cline.bot/api/v1/chat/completions",
            modelsEndpoint = "https://api.cline.bot/api/v1/models",
            authScheme = AuthScheme.BEARER,
            modelsDevAliases = listOf("cline"),
            modelsDevNpm = null,
        ),
    )

    /** Find a provider by its internal ID. */
    fun byId(providerId: String): ProviderEndpoint? =
        ALL.find { it.providerId == providerId }

    /**
     * Find a provider by a models.dev catalog provider key (alias).
     * e.g. "togetherai" → "together", "fireworks-ai" → "fireworks",
     * "zhipuai" → "zai", "google" → "gemini".
     */
    fun byModelsDevAlias(alias: String): ProviderEndpoint? =
        ALL.find { alias in it.modelsDevAliases }

    /**
     * Find a provider by its npm package name (from catalog's `npm` field).
     */
    fun byNpmPackage(npm: String): ProviderEndpoint? =
        ALL.find { it.modelsDevNpm == npm }

    /**
     * Normalize a models.dev catalog provider ID to our internal ID.
     * Returns the input unchanged if no match.
     */
    fun normalizeProviderId(modelsDevId: String): String =
        byModelsDevAlias(modelsDevId)?.providerId ?: modelsDevId

    /**
     * Is this providerId a known popular provider (not custom)?
     */
    fun isPopular(providerId: String): Boolean = byId(providerId) != null
}
