package com.ai.agent.tools.categories

import android.content.Context
import android.util.Log
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.ApiKeyStore
import com.ai.agent.llm.LLMClient
import com.ai.agent.llm.ModelCapability
import com.ai.agent.llm.ProviderEndpoints
import com.ai.agent.llm.SavedModelsStore
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import com.ai.agent.util.LogCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * v4.1.0: AICapabilityTools — exposes the full power of configured AI models to
 * the agent. Five new tools that route to the right provider API based on
 * what the user has configured in SavedModelsStore.
 *
 * Tools:
 *   - generateImage(prompt, size?)   → uses IMAGE_GEN model if configured, else Pollinations.ai fallback
 *   - analyzeImage(path, question?)  → uses VISION model (or main model if vision-capable), else ML Kit OCR
 *   - transcribeAudio(path)          → uses AUDIO model's Whisper-compatible endpoint if configured
 *   - generateSpeech(text, voice?)   → uses AUDIO model's TTS endpoint if configured, else Android TTS
 *   - imageSearch(query, count?)     → free DuckDuckGo image search (no API key needed)
 *
 * Design principles:
 *   - Never breaks the agent loop — every failure degrades gracefully (returns
 *     a helpful message instead of throwing).
 *   - Reuses existing infrastructure: SavedModelsStore for routing, ApiKeyStore
 *     for credentials, ProviderEndpoints for known endpoints.
 *   - Only external API without a key is Pollinations.ai (free image generation).
 *   - No Z.ai proxy references — calls provider APIs directly.
 */
object AICapabilityTools {

    private const val TAG = "AICapabilityTools"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Where generated images get saved. */
    private const val IMAGES_DIR = "${com.ai.agent.Config.WORKSPACE_DIR}/images"
    /** Where generated TTS audio gets saved. */
    private const val AUDIO_DIR = "${com.ai.agent.Config.WORKSPACE_DIR}/audio"

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "generateImage" -> generateImage(call, context)
            "analyzeImage" -> analyzeImage(call, context)
            "transcribeAudio" -> transcribeAudio(call, context)
            "generateSpeech" -> generateSpeech(call, context)
            "imageSearch" -> imageSearch(call, context)
            else -> ToolExecutor.ToolResult(false, "Unknown AI capability tool: ${call.name}")
        }
    }

    // =========================================================================
    // generateImage
    // =========================================================================

    private suspend fun generateImage(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val prompt = call.args["prompt"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing prompt")
        val size = (call.args["size"] as? String)?.takeIf { it.isNotBlank() } ?: "1024x1024"

        // Try the configured IMAGE_GEN model first. If the default saved model
        // has IMAGE_GEN capability, route to its provider's image-generation
        // endpoint. Otherwise fall back to Pollinations.ai (free, no key needed).
        val imageGenModel = pickImageGenModel(context)

        val imageBytes: ByteArray? = try {
            when {
                imageGenModel != null && imageGenModel.first.providerId == "gemini" -> {
                    generateImageViaGemini(context, imageGenModel.first, imageGenModel.second, prompt)
                }
                imageGenModel != null -> {
                    generateImageViaOpenAI(context, imageGenModel.first, imageGenModel.second, prompt, size)
                }
                else -> {
                    LogCapture.i(TAG, "No IMAGE_GEN model configured — using Pollinations.ai fallback")
                    generateImageViaPollinations(prompt, size)
                }
            }
        } catch (e: Exception) {
            LogCapture.e(TAG, "generateImage failed: ${e.message}")
            Log.e(TAG, "generateImage failed", e)
            // Last-resort fallback — Pollinations always works.
            try {
                generateImageViaPollinations(prompt, size)
            } catch (e2: Exception) {
                null
            }
        }

        if (imageBytes == null || imageBytes.isEmpty()) {
            return ToolExecutor.ToolResult(
                false,
                "Image generation failed for prompt: \"$prompt\". " +
                    "Configure an IMAGE_GEN model in Hub → LLM, or check your network connection."
            )
        }

        // Save to /storage/emulated/0/Aira/images/generated_{timestamp}.jpg
        return try {
            val dir = File(IMAGES_DIR).apply { mkdirs() }
            val file = File(dir, "generated_${System.currentTimeMillis()}.jpg")
            file.writeBytes(imageBytes)
            LogCapture.i(TAG, "Image saved: ${file.absolutePath} (${imageBytes.size} bytes)")
            ToolExecutor.ToolResult(
                true,
                "✅ Image generated and saved to ${file.absolutePath} (${imageBytes.size} bytes)"
            )
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Failed to save image: ${e.message}")
        }
    }

    /**
     * Returns (SavedModel, ApiKey) if the default model OR any saved model has
     * IMAGE_GEN capability AND has a resolvable binding. Returns null if the
     * user hasn't configured an image-gen model.
     *
     * v4.1.0: Prefers the explicitly-selected image-gen model (from FutureSectionsPage)
     * stored under "image_gen_model_id" in the saved-models prefs.
     */
    private fun pickImageGenModel(context: Context): Pair<SavedModelsStore.SavedModel, ApiKeyStore.ApiKey>? {
        SavedModelsStore.load(context)
        ApiKeyStore.load(context)
        // v4.1.0: Prefer the explicitly-selected image-gen model from the Future sections UI.
        val prefs = context.getSharedPreferences("ai_agent_saved_models", Context.MODE_PRIVATE)
        val imageGenId = prefs.getString("image_gen_model_id", "") ?: ""
        if (imageGenId.isNotEmpty()) {
            val m = SavedModelsStore.getAllModels().find { it.id == imageGenId }
            if (m != null) {
                val resolved = SavedModelsStore.resolveBinding(context, m, false)
                if (resolved != null) return m to resolved.second
            }
        }
        // Otherwise prefer the default model if it has IMAGE_GEN.
        val default = SavedModelsStore.getDefaultModel(context)
        if (default != null && ModelCapability.IMAGE_GEN in default.capabilities) {
            val resolved = SavedModelsStore.resolveBinding(context, default, false)
            if (resolved != null) return default to resolved.second
        }
        // Otherwise search all saved models for one with IMAGE_GEN.
        val candidates = SavedModelsStore.getModelsByCapability(ModelCapability.IMAGE_GEN)
        for (m in candidates) {
            val resolved = SavedModelsStore.resolveBinding(context, m, false) ?: continue
            return m to resolved.second
        }
        return null
    }

    /**
     * Gemini image generation: POST /models/{model}:generateContent?key={key}
     * Body includes generationConfig.response_modalities = ["IMAGE","TEXT"] and
     * a parts array with the text prompt.
     */
    private suspend fun generateImageViaGemini(
        context: Context,
        model: SavedModelsStore.SavedModel,
        key: ApiKeyStore.ApiKey,
        prompt: String,
    ): ByteArray? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val actualModelId = SavedModelsStore.resolveActualModelIdForBinding(context, model, model.activeBindings().first())
        val base = ProviderEndpoints.byId("gemini")?.chatEndpoint
            ?: "https://generativelanguage.googleapis.com/v1beta"
        val url = "$base/models/$actualModelId:generateContent?key=${key.apiKey}"
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", JSONObject().apply {
                put("response_modalities", JSONArray().apply {
                    put("IMAGE")
                    put("TEXT")
                })
            })
        }.toString()

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val respBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            LogCapture.w(TAG, "Gemini image-gen failed ${response.code}: ${respBody.take(200)}")
            return@withContext null
        }
        // Parse: candidates[0].content.parts[] — find a part with inlineData.data (base64 image)
        val json = JSONObject(respBody)
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return@withContext null
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val inline = part.optJSONObject("inlineData") ?: continue
            val b64 = inline.optString("data", "")
            if (b64.isNotEmpty()) {
                return@withContext android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            }
        }
        null
    }

    /**
     * OpenAI-compatible image generation: POST /images/generations
     * Body: {model, prompt, size, n:1, response_format:"b64_json"}
     */
    private suspend fun generateImageViaOpenAI(
        context: Context,
        model: SavedModelsStore.SavedModel,
        key: ApiKeyStore.ApiKey,
        prompt: String,
        size: String,
    ): ByteArray? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val binding = model.activeBindings().first()
        val chatEndpoint = SavedModelsStore.resolveEndpointForBinding(model, binding, key)
        // /v1/chat/completions → /v1/images/generations
        val imageEndpoint = chatEndpoint.replace("/chat/completions$", "/images/generations")
            .replace("/chat/completions", "/images/generations")
        val actualModelId = SavedModelsStore.resolveActualModelIdForBinding(context, model, binding)

        val body = JSONObject().apply {
            put("model", actualModelId)
            put("prompt", prompt)
            put("size", size)
            put("n", 1)
            put("response_format", "b64_json")
        }.toString()

        val request = Request.Builder()
            .url(imageEndpoint)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${key.apiKey}")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val respBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            LogCapture.w(TAG, "OpenAI image-gen failed ${response.code}: ${respBody.take(200)}")
            return@withContext null
        }
        val json = JSONObject(respBody)
        val data = json.optJSONArray("data")?.optJSONObject(0) ?: return@withContext null
        val b64 = data.optString("b64_json", "")
        if (b64.isNotEmpty()) return@withContext android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        // Some providers return a URL instead.
        val url = data.optString("url", "")
        if (url.isNotEmpty()) {
            return@withContext downloadBytes(url)
        }
        null
    }

    /**
     * Pollinations.ai — free, no API key, always works.
     * GET https://image.pollinations.ai/prompt/{encoded-prompt}?width=1024&height=1024&nologo=true
     */
    private suspend fun generateImageViaPollinations(prompt: String, size: String): ByteArray? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val (w, h) = parseSize(size, 1024, 1024)
            val encoded = URLEncoder.encode(prompt, "UTF-8")
            val url = "https://image.pollinations.ai/prompt/$encoded?width=$w&height=$h&nologo=true"
            LogCapture.i(TAG, "Pollinations: $url")
            downloadBytes(url)
        }

    /**
     * Parse "WxH" → (W, H) with fallback defaults.
     */
    private fun parseSize(spec: String, defaultW: Int, defaultH: Int): Pair<Int, Int> {
        val m = Regex("(\\d+)\\s*[xX]\\s*(\\d+)").find(spec) ?: return defaultW to defaultH
        val w = m.groupValues[1].toIntOrNull() ?: defaultW
        val h = m.groupValues[2].toIntOrNull() ?: defaultH
        // Cap to reasonable sizes for Pollinations.
        return (if (w in 64..2048) w else defaultW) to (if (h in 64..2048) h else defaultH)
    }

    private fun downloadBytes(url: String): ByteArray? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36")
            .build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) response.body?.bytes() else null
    } catch (e: Exception) {
        Log.w(TAG, "downloadBytes failed: ${e.message}")
        null
    }

    // =========================================================================
    // analyzeImage
    // =========================================================================

    private suspend fun analyzeImage(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val path = call.args["path"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing path")
        val question = (call.args["question"] as? String)?.takeIf { it.isNotBlank() }
            ?: "Describe this image in detail"

        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return ToolExecutor.ToolResult(false, "Image file not found: $path")
        }

        // Read image → base64.
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return ToolExecutor.ToolResult(false, "Failed to read image: ${e.message}")
        }
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

        // v4.1.0: Reuse VisionTools' routing — it picks the configured vision
        // model first, falls back to the main chat model if it's vision-capable,
        // and returns "" on failure (so we fall back to ML Kit OCR).
        val vlmResult = try {
            VisionTools.analyzeScreenWithBase64VLM(context, base64, question)
        } catch (e: Exception) {
            LogCapture.e(TAG, "VLM analyzeImage failed: ${e.message}")
            ""
        }

        if (vlmResult.isNotBlank() && !vlmResult.startsWith("VLM error", ignoreCase = true)
            && !vlmResult.startsWith("Vision error", ignoreCase = true)) {
            return ToolExecutor.ToolResult(true, vlmResult)
        }

        // Fallback: ML Kit OCR (on-device, free).
        LogCapture.i(TAG, "VLM unavailable — falling back to ML Kit OCR for $path")
        return try {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return ToolExecutor.ToolResult(false, "Could not decode image: $path")
            val ocrText = com.ai.agent.ml.MLKitHelper.tryRecognize(bitmap)
            if (ocrText.isNullOrBlank()) {
                ToolExecutor.ToolResult(
                    true,
                    "No vision model configured and ML Kit found no text in the image. " +
                        "Configure a VISION model in Hub → LLM → Vision to analyze images."
                )
            } else {
                ToolExecutor.ToolResult(true, "ML Kit OCR result:\n$ocrText")
            }
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Image analysis failed: ${e.message}")
        }
    }

    // =========================================================================
    // transcribeAudio
    // =========================================================================

    private suspend fun transcribeAudio(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val path = call.args["path"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing path")
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return ToolExecutor.ToolResult(false, "Audio file not found: $path")
        }

        // Find an audio-capable model + binding for the Whisper endpoint.
        val audioModel = pickAudioModel(context)
        if (audioModel == null) {
            return ToolExecutor.ToolResult(
                false,
                "No audio transcription model configured. Save audio files and use them via speak() for TTS. " +
                    "Add an AUDIO-capable model in Hub → LLM → Audio to enable transcription."
            )
        }
        val (model, key) = audioModel
        val binding = model.activeBindings().first()
        val chatEndpoint = SavedModelsStore.resolveEndpointForBinding(model, binding, key)
        // /v1/chat/completions → /v1/audio/transcriptions
        val transcribeEndpoint = chatEndpoint.replace("/chat/completions$", "/audio/transcriptions")
            .replace("/chat/completions", "/audio/transcriptions")
        val actualModelId = SavedModelsStore.resolveActualModelIdForBinding(context, model, binding)

        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val mediaType = guessAudioMime(path)
                val fileBody = file.asRequestBody(mediaType.toMediaType())
                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, fileBody)
                    .addFormDataPart("model", actualModelId)
                    .build()

                val request = Request.Builder()
                    .url(transcribeEndpoint)
                    .header("Authorization", "Bearer ${key.apiKey}")
                    .post(multipart)
                    .build()

                val response = httpClient.newCall(request).execute()
                val respBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    LogCapture.w(TAG, "Transcription failed ${response.code}: ${respBody.take(200)}")
                    return@withContext ToolExecutor.ToolResult(
                        false,
                        "Transcription API error ${response.code}: ${respBody.take(200)}"
                    )
                }
                val json = JSONObject(respBody)
                val text = json.optString("text", "")
                if (text.isBlank()) {
                    ToolExecutor.ToolResult(false, "Transcription returned empty text.")
                } else {
                    LogCapture.i(TAG, "Transcribed ${file.name} → ${text.length} chars")
                    ToolExecutor.ToolResult(true, text)
                }
            } catch (e: Exception) {
                LogCapture.e(TAG, "transcribeAudio failed: ${e.message}")
                ToolExecutor.ToolResult(false, "Transcription failed: ${e.message}")
            }
        }
    }

    private fun guessAudioMime(path: String): String {
        val p = path.lowercase()
        return when {
            p.endsWith(".mp3") -> "audio/mpeg"
            p.endsWith(".m4a") -> "audio/mp4"
            p.endsWith(".aac") -> "audio/aac"
            p.endsWith(".ogg") -> "audio/ogg"
            p.endsWith(".flac") -> "audio/flac"
            p.endsWith(".webm") -> "audio/webm"
            else -> "audio/wav"
        }
    }

    // =========================================================================
    // generateSpeech
    // =========================================================================

    private suspend fun generateSpeech(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val text = call.args["text"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing text")
        val voice = (call.args["voice"] as? String)?.takeIf { it.isNotBlank() }

        // Try the configured audio model's TTS endpoint first.
        val audioModel = pickAudioModel(context)
        if (audioModel != null) {
            val result = generateSpeechViaApi(context, audioModel.first, audioModel.second, text, voice)
            if (result != null) return result
            // API TTS failed — fall through to Android TTS.
            LogCapture.w(TAG, "API TTS failed — falling back to Android TTS")
        }
        // Fall back to Android TTS — synthesize to file.
        return generateSpeechViaAndroidTts(context, text)
    }

    /**
     * OpenAI-compatible TTS: POST /audio/speech
     * Body: {model, input, voice, response_format:"wav"}
     */
    private suspend fun generateSpeechViaApi(
        context: Context,
        model: SavedModelsStore.SavedModel,
        key: ApiKeyStore.ApiKey,
        text: String,
        voice: String?,
    ): ToolExecutor.ToolResult? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val binding = model.activeBindings().first()
            val chatEndpoint = SavedModelsStore.resolveEndpointForBinding(model, binding, key)
            val ttsEndpoint = chatEndpoint.replace("/chat/completions$", "/audio/speech")
                .replace("/chat/completions", "/audio/speech")
            val actualModelId = SavedModelsStore.resolveActualModelIdForBinding(context, model, binding)

            val body = JSONObject().apply {
                put("model", actualModelId)
                put("input", text)
                put("voice", voice ?: "alloy")
                put("response_format", "wav")
            }.toString()

            val request = Request.Builder()
                .url(ttsEndpoint)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${key.apiKey}")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                LogCapture.w(TAG, "TTS API failed ${response.code}: ${(response.body?.string() ?: "").take(200)}")
                return@withContext null
            }
            val audioBytes = response.body?.bytes() ?: return@withContext null
            if (audioBytes.isEmpty()) return@withContext null

            val dir = File(AUDIO_DIR).apply { mkdirs() }
            val file = File(dir, "tts_${System.currentTimeMillis()}.wav")
            file.writeBytes(audioBytes)
            LogCapture.i(TAG, "TTS saved: ${file.absolutePath} (${audioBytes.size} bytes)")
            ToolExecutor.ToolResult(true, "✅ Speech generated: ${file.absolutePath}")
        } catch (e: Exception) {
            LogCapture.e(TAG, "TTS API failed: ${e.message}")
            null
        }
    }

    /**
     * Android TTS fallback — same engine the `speak` tool uses, but synthesize
     * to a WAV file instead of speaking aloud.
     */
    private suspend fun generateSpeechViaAndroidTts(
        context: Context,
        text: String,
    ): ToolExecutor.ToolResult = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val dir = File(AUDIO_DIR).apply { mkdirs() }
            val file = File(dir, "tts_${System.currentTimeMillis()}.wav")
            val ready = CompletableDeferred<Boolean>()
            val tts = android.speech.tts.TextToSpeech(context) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    ready.complete(true)
                } else {
                    ready.complete(false)
                }
            }
            val ok = withTimeoutOrNull(2000) { ready.await() } == true
            if (!ok) {
                return@withContext ToolExecutor.ToolResult(false, "Android TTS failed to initialize")
            }
            tts.language = java.util.Locale("en", "IN")
            // synthesizeToFile writes a WAV (16kHz mono PCM) on Android.
            val utteranceId = "tts_${System.currentTimeMillis()}"
            val synthesized = tts.synthesizeToFile(text, null, file, utteranceId)
            if (synthesized != android.speech.tts.TextToSpeech.SUCCESS) {
                return@withContext ToolExecutor.ToolResult(false, "TTS synthesis rejected the request")
            }
            // Wait for synthesis to complete (max 10s — short text only takes ~1s).
            val done = CompletableDeferred<Boolean>()
            val listener = object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { done.complete(true) }
                override fun onError(utteranceId: String?) { done.complete(false) }
                @Deprecated("deprecated in API 21")
                override fun onError(utteranceId: String?, errorCode: Int) { done.complete(false) }
            }
            tts.setOnUtteranceProgressListener(listener)
            val success = withTimeoutOrNull(10000) { done.await() } == true
            tts.stop()
            tts.shutdown()
            if (!success || !file.exists() || file.length() == 0L) {
                return@withContext ToolExecutor.ToolResult(false, "TTS synthesis failed or timed out")
            }
            LogCapture.i(TAG, "Android TTS saved: ${file.absolutePath} (${file.length()} bytes)")
            ToolExecutor.ToolResult(true, "✅ Speech generated: ${file.absolutePath}")
        } catch (e: Exception) {
            LogCapture.e(TAG, "Android TTS failed: ${e.message}")
            ToolExecutor.ToolResult(false, "TTS error: ${e.message}")
        }
    }

    /**
     * Returns (SavedModel, ApiKey) for an AUDIO-capable model with a resolvable
     * binding, or null if none configured.
     */
    private fun pickAudioModel(context: Context): Pair<SavedModelsStore.SavedModel, ApiKeyStore.ApiKey>? {
        SavedModelsStore.load(context)
        ApiKeyStore.load(context)
        val prefs = context.getSharedPreferences("ai_agent_saved_models", Context.MODE_PRIVATE)
        val audioId = prefs.getString("audio_model_id", "") ?: ""
        // Prefer the explicitly-selected audio model.
        if (audioId.isNotEmpty()) {
            val m = SavedModelsStore.getAllModels().find { it.id == audioId }
            if (m != null) {
                val resolved = SavedModelsStore.resolveBinding(context, m, false)
                if (resolved != null) return m to resolved.second
            }
        }
        // Otherwise search all saved models with AUDIO capability.
        for (m in SavedModelsStore.getModelsByCapability(ModelCapability.AUDIO)) {
            val resolved = SavedModelsStore.resolveBinding(context, m, false) ?: continue
            return m to resolved.second
        }
        // MULTIMODAL models often support audio too.
        for (m in SavedModelsStore.getModelsByCapability(ModelCapability.MULTIMODAL)) {
            val resolved = SavedModelsStore.resolveBinding(context, m, false) ?: continue
            return m to resolved.second
        }
        return null
    }

    // =========================================================================
    // imageSearch
    // =========================================================================

    /**
     * Free image search via DuckDuckGo. No API key needed.
     * GET https://duckduckgo.com/?q={query}&iar=images&iax=images
     * Parses the HTML response for direct image URLs.
     *
     * Returns a numbered list of image URLs with captions.
     */
    private suspend fun imageSearch(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val query = call.args["query"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing query")
        val count = ((call.args["count"] as? Number)?.toInt() ?: 3).coerceIn(1, 10)

        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode("$query image", "UTF-8")
                val searchUrl = "https://duckduckgo.com/?q=$encoded&iar=images&iax=images"
                val request = Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36")
                    .build()
                val response = httpClient.newCall(request).execute()
                val html = response.body?.string() ?: ""
                if (html.isBlank()) {
                    return@withContext ToolExecutor.ToolResult(false, "Empty response from DuckDuckGo")
                }

                // DuckDuckGo image results embed URLs in various formats.
                // Pattern 1: u="https://tse...mm.com" (thumbnail) + original URL nearby.
                // Pattern 2: <img class="result__img" src="...">
                val urlPattern = Regex("""https://tse\d*\.mm\.bing\.net/th[^"'\s<>]+""")
                val urls = urlPattern.findAll(html).map { it.value }.distinct().take(count).toList()

                if (urls.isEmpty()) {
                    // Fallback: also try direct image hosts in the HTML.
                    val altPattern = Regex("""https?://[^"'\s<>]+\.(?:jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE)
                    val altUrls = altPattern.findAll(html).map { it.value }.distinct().take(count).toList()
                    if (altUrls.isEmpty()) {
                        return@withContext ToolExecutor.ToolResult(
                            false,
                            "No images found for '$query'. Try webSearch(\"$query images\") + downloadFile as a fallback."
                        )
                    }
                    val sb = StringBuilder("Found ${altUrls.size} image(s) for '$query':\n\n")
                    altUrls.forEachIndexed { i, u -> sb.append("${i + 1}. $u\n") }
                    sb.append("\nUse downloadFile(url=\"...\") to save any of these images.")
                    return@withContext ToolExecutor.ToolResult(true, sb.toString())
                }

                val sb = StringBuilder("Found ${urls.size} image(s) for '$query':\n\n")
                urls.forEachIndexed { i, u -> sb.append("${i + 1}. $u\n") }
                sb.append("\nUse downloadFile(url=\"...\") to save any of these images.")
                ToolExecutor.ToolResult(true, sb.toString())
            } catch (e: Exception) {
                LogCapture.e(TAG, "imageSearch failed: ${e.message}")
                ToolExecutor.ToolResult(false, "Image search failed: ${e.message}. Try webSearch + downloadFile as a fallback.")
            }
        }
    }
}
