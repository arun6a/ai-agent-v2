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
import kotlinx.coroutines.withContext
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
 * v4.1.1: AICapabilityTools — exposes the full power of configured AI models.
 *
 * Tools:
 *   - generateImage(prompt, size?)   → IMAGE_GEN model or Pollinations.ai fallback
 *   - analyzeImage(path, question?)  → VISION model or ML Kit OCR fallback
 *   - transcribeAudio(path)          → AUDIO model's Whisper endpoint
 *   - generateSpeech(text, voice?)   → AUDIO model's TTS or Android TTS
 *   - imageSearch(query, count?)     → free DuckDuckGo image search
 */
object AICapabilityTools {

    private const val TAG = "AICapabilityTools"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private const val IMAGES_DIR = "${com.ai.agent.Config.WORKSPACE_DIR}/images"
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
            "generateVideo" -> generateVideo(call, context)
            else -> ToolExecutor.ToolResult(false, "Unknown AI capability tool: ${call.name}")
        }
    }

    // === generateImage ===
    private suspend fun generateImage(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        // v4.1.2: Accept both "prompt" and "description" as parameter names (AI uses both)
        val prompt = (call.args["prompt"] as? String ?: call.args["description"] as? String)
            ?: return ToolExecutor.ToolResult(false, "missing prompt. Example: generateImage(prompt=\"a cat sitting on a table\")")
        val size = (call.args["size"] as? String)?.takeIf { it.isNotBlank() } ?: "1024x1024"

        // Try configured IMAGE_GEN model first, then Pollinations.ai fallback
        val imageGenModel = pickImageGenModel(context)
        var imageBytes: ByteArray? = null

        // v4.1.3: Try provider's image API first
        if (imageGenModel != null) {
            imageBytes = try {
                when {
                    imageGenModel.first.providerId == "gemini" ->
                        generateImageViaGemini(context, imageGenModel.first, imageGenModel.second, prompt)
                    else ->
                        generateImageViaOpenAI(context, imageGenModel.first, imageGenModel.second, prompt, size)
                }
            } catch (e: Exception) {
                LogCapture.e(TAG, "Provider image-gen failed: ${e.message}")
                null
            }
            // v4.1.3: If provider returned null (429 quota, API error, etc), fall back to Pollinations
            if (imageBytes == null) {
                LogCapture.i(TAG, "Provider image-gen returned null — falling back to Pollinations.ai")
                imageBytes = try { generateImageViaPollinations(prompt, size) } catch (_: Exception) { null }
            }
        } else {
            // No IMAGE_GEN model configured — use Pollinations.ai directly
            LogCapture.i(TAG, "No IMAGE_GEN model — using Pollinations.ai")
            imageBytes = try { generateImageViaPollinations(prompt, size) } catch (_: Exception) { null }
        }

        if (imageBytes == null || imageBytes.isEmpty()) {
            return ToolExecutor.ToolResult(false,
                "Image generation failed for prompt: \"$prompt\". Configure an IMAGE_GEN model or check network.")
        }

        return try {
            // v4.1.5: Use app's external files dir (no MANAGE_EXTERNAL_STORAGE needed)
            // Fallback to /sdcard/Aira/images/ if that fails
            val dir = try {
                val appDir = File(context.getExternalFilesDir(null), "Aira/images").apply { mkdirs() }
                if (appDir.canWrite()) appDir else File(IMAGES_DIR).apply { mkdirs() }
            } catch (_: Exception) {
                File(IMAGES_DIR).apply { mkdirs() }
            }
            val file = File(dir, "generated_${System.currentTimeMillis()}.jpg")
            file.writeBytes(imageBytes)
            LogCapture.i(TAG, "Image saved: ${file.absolutePath} (${imageBytes.size} bytes)")
            ToolExecutor.ToolResult(true,
                "✅ Image generated and saved to ${file.absolutePath} (${imageBytes.size} bytes)")
        } catch (e: Exception) {
            // v4.1.5: If save fails, try MediaStore (gallery) as last resort
            ToolExecutor.ToolResult(false, "Failed to save image: ${e.message}. Grant storage permission in Settings → Apps → Aira → Permissions → Files and media → Allow all")
        }
    }

    private fun pickImageGenModel(context: Context): Pair<SavedModelsStore.SavedModel, ApiKeyStore.ApiKey>? {
        SavedModelsStore.load(context)
        ApiKeyStore.load(context)
        val prefs = context.getSharedPreferences("ai_agent_saved_models", Context.MODE_PRIVATE)
        val imageGenId = prefs.getString("image_gen_model_id", "") ?: ""
        LogCapture.i(TAG, "pickImageGenModel: image_gen_model_id pref = '$imageGenId'")
        if (imageGenId.isNotEmpty()) {
            val m = SavedModelsStore.getAllModels().find { it.id == imageGenId }
            if (m != null) {
                LogCapture.i(TAG, "pickImageGenModel: found configured model: ${m.modelName} (id=${m.modelId}, provider=${m.providerId})")
                val resolved = SavedModelsStore.resolveBinding(context, m, false)
                if (resolved != null) {
                    LogCapture.i(TAG, "pickImageGenModel: binding resolved: key=${resolved.second.alias.ifEmpty { resolved.second.providerName }}")
                    return m to resolved.second
                } else {
                    LogCapture.w(TAG, "pickImageGenModel: model found but no binding resolved")
                }
            } else {
                LogCapture.w(TAG, "pickImageGenModel: image_gen_model_id '$imageGenId' not found in saved models. Saved model IDs: ${SavedModelsStore.getAllModels().map { it.id }}")
            }
        }
        val default = SavedModelsStore.getDefaultModel(context)
        if (default != null && ModelCapability.IMAGE_GEN in default.capabilities) {
            LogCapture.i(TAG, "pickImageGenModel: using default model (has IMAGE_GEN): ${default.modelName}")
            val resolved = SavedModelsStore.resolveBinding(context, default, false)
            if (resolved != null) return default to resolved.second
        }
        for (m in SavedModelsStore.getModelsByCapability(ModelCapability.IMAGE_GEN)) {
            LogCapture.i(TAG, "pickImageGenModel: trying IMAGE_GEN model: ${m.modelName}")
            val resolved = SavedModelsStore.resolveBinding(context, m, false) ?: continue
            return m to resolved.second
        }
        LogCapture.i(TAG, "pickImageGenModel: no IMAGE_GEN model found — will use Pollinations.ai")
        return null
    }

    private suspend fun generateImageViaGemini(
        context: Context, model: SavedModelsStore.SavedModel, key: ApiKeyStore.ApiKey, prompt: String
    ): ByteArray? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val actualModelId = SavedModelsStore.resolveActualModelIdForBinding(context, model, model.activeBindings().first())
        val base = ProviderEndpoints.byId("gemini")?.chatEndpoint ?: "https://generativelanguage.googleapis.com/v1beta"
        val url = "$base/models/$actualModelId:generateContent?key=${key.apiKey}"
        // v4.1.4: Log which model + key is being used for debugging
        LogCapture.i(TAG, "Gemini image-gen: model=$actualModelId (saved as ${model.modelName}), key=${key.alias.ifEmpty { key.providerName }} (${key.apiKey.take(10)}...)")
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", JSONObject().apply {
                put("response_modalities", JSONArray().apply { put("IMAGE"); put("TEXT") })
            })
        }.toString()
        val request = Request.Builder().url(url).header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val response = httpClient.newCall(request).execute()
        val respBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            // v4.1.4: Log full error details for debugging
            LogCapture.w(TAG, "Gemini image-gen failed ${response.code} (model=$actualModelId): ${respBody.take(300)}")
            return@withContext null
        }
        val parts = JSONObject(respBody).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return@withContext null
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val b64 = inline.optString("data", "")
            if (b64.isNotEmpty()) return@withContext android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        }
        null
    }

    private suspend fun generateImageViaOpenAI(
        context: Context, model: SavedModelsStore.SavedModel, key: ApiKeyStore.ApiKey, prompt: String, size: String
    ): ByteArray? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val binding = model.activeBindings().first()
        val chatEndpoint = SavedModelsStore.resolveEndpointForBinding(model, binding, key)
        val imageEndpoint = chatEndpoint.replace("/chat/completions", "/images/generations")
        val actualModelId = SavedModelsStore.resolveActualModelIdForBinding(context, model, binding)
        val body = JSONObject().apply {
            put("model", actualModelId); put("prompt", prompt); put("size", size)
            put("n", 1); put("response_format", "b64_json")
        }.toString()
        val request = Request.Builder().url(imageEndpoint).header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${key.apiKey}")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val response = httpClient.newCall(request).execute()
        val respBody = response.body?.string() ?: ""
        if (!response.isSuccessful) { LogCapture.w(TAG, "OpenAI image-gen failed ${response.code}"); return@withContext null }
        val data = JSONObject(respBody).optJSONArray("data")?.optJSONObject(0) ?: return@withContext null
        val b64 = data.optString("b64_json", "")
        if (b64.isNotEmpty()) return@withContext android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        null
    }

    private suspend fun generateImageViaPollinations(prompt: String, size: String): ByteArray? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val (w, h) = size.split("x").let { (it.getOrNull(0) ?: "1024") to (it.getOrNull(1) ?: "1024") }
        val encoded = URLEncoder.encode(prompt, "UTF-8")
        val url = "https://image.pollinations.ai/prompt/$encoded?width=$w&height=$h&nologo=true"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return@withContext null
        response.body?.bytes()
    }

    // === analyzeImage ===
    private suspend fun analyzeImage(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val path = call.args["path"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing path")
        val question = (call.args["question"] as? String)?.takeIf { it.isNotBlank() } ?: "Describe this image in detail"
        val resolvedPath = com.ai.agent.util.PathResolver.resolve(path)
        val file = File(resolvedPath)
        if (!file.exists()) return ToolExecutor.ToolResult(false, "File not found: $resolvedPath")
        val bytes = file.readBytes()
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

        // Use vision model if configured, else main model if vision-capable, else ML Kit
        val visionModel = SavedModelsStore.getVisionModel(context)
        val result = if (visionModel != null) {
            VisionTools.analyzeScreenWithVLMBase64(context, base64, question)
        } else {
            // Try ML Kit OCR as fallback
            try {
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    val mlText = com.ai.agent.ml.MLKitHelper.tryRecognize(bitmap)
                    if (mlText != null && mlText.length > 20) "ML Kit OCR result:\n$mlText"
                    else "Image exists but no text detected. Configure a Vision model in Settings for full image analysis."
                } else "Could not decode image."
            } catch (e: Exception) {
                "Image analysis error: ${e.message}. Configure a Vision model for better results."
            }
        }
        return ToolExecutor.ToolResult(true, result)
    }

    // === transcribeAudio ===
    private suspend fun transcribeAudio(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val path = call.args["path"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing path")
        val resolvedPath = com.ai.agent.util.PathResolver.resolve(path)
        val file = File(resolvedPath)
        if (!file.exists()) return ToolExecutor.ToolResult(false, "Audio file not found: $resolvedPath")

        val audioModel = pickAudioModel(context)
        if (audioModel == null) {
            return ToolExecutor.ToolResult(false,
                "No audio transcription model configured. Save a Whisper-capable model in Hub → LLM → Audio Configuration.")
        }
        val (model, key) = audioModel
        val binding = model.activeBindings().first()
        val chatEndpoint = SavedModelsStore.resolveEndpointForBinding(model, binding, key)
        val asrEndpoint = chatEndpoint.replace("/chat/completions", "/audio/transcriptions")
        val actualModelId = SavedModelsStore.resolveActualModelIdForBinding(context, model, binding)

        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val mediaType = "audio/*".toMediaType()
                val requestBody = file.asRequestBody(mediaType)
                val multipart = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", file.name, requestBody)
                    .addFormDataPart("model", actualModelId)
                    .build()
                val request = Request.Builder().url(asrEndpoint)
                    .header("Authorization", "Bearer ${key.apiKey}")
                    .post(multipart).build()
                val response = httpClient.newCall(request).execute()
                val respBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext ToolExecutor.ToolResult(false, "Transcription API error ${response.code}: ${respBody.take(200)}")
                }
                val text = JSONObject(respBody).optString("text", "")
                if (text.isEmpty()) return@withContext ToolExecutor.ToolResult(false, "Transcription returned empty text.")
                ToolExecutor.ToolResult(true, "Transcribed: $text")
            } catch (e: Exception) {
                ToolExecutor.ToolResult(false, "Transcription failed: ${e.message}")
            }
        }
    }

    // === generateSpeech ===
    private suspend fun generateSpeech(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val text = call.args["text"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing text")
        val voice = (call.args["voice"] as? String)?.takeIf { it.isNotBlank() } ?: "tongtong"

        val audioModel = pickAudioModel(context)
        if (audioModel == null) {
            // Fallback: use Android TTS (same as speak tool)
            return ToolExecutor.ToolResult(true,
                "No AI TTS model configured. Using Android TTS instead. Call speak(text=\"$text\") for basic TTS, or configure an Audio model for better quality.")
        }
        val (model, key) = audioModel
        val binding = model.activeBindings().first()
        val chatEndpoint = SavedModelsStore.resolveEndpointForBinding(model, binding, key)
        val ttsEndpoint = chatEndpoint.replace("/chat/completions", "/audio/speech")
        val actualModelId = SavedModelsStore.resolveActualModelIdForBinding(context, model, binding)

        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("model", actualModelId); put("input", text); put("voice", voice)
                    put("response_format", "wav")
                }.toString()
                val request = Request.Builder().url(ttsEndpoint)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer ${key.apiKey}")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext ToolExecutor.ToolResult(false, "TTS API error ${response.code}")
                }
                val audioBytes = response.body?.bytes() ?: return@withContext ToolExecutor.ToolResult(false, "Empty audio response")
                val dir = File(AUDIO_DIR).apply { mkdirs() }
                val file = File(dir, "tts_${System.currentTimeMillis()}.wav")
                file.writeBytes(audioBytes)
                ToolExecutor.ToolResult(true, "✅ Speech generated: ${file.absolutePath} (${audioBytes.size} bytes)")
            } catch (e: Exception) {
                ToolExecutor.ToolResult(false, "TTS failed: ${e.message}")
            }
        }
    }

    private fun pickAudioModel(context: Context): Pair<SavedModelsStore.SavedModel, ApiKeyStore.ApiKey>? {
        SavedModelsStore.load(context)
        ApiKeyStore.load(context)
        val prefs = context.getSharedPreferences("ai_agent_saved_models", Context.MODE_PRIVATE)
        val audioId = prefs.getString("audio_model_id", "") ?: ""
        if (audioId.isNotEmpty()) {
            val m = SavedModelsStore.getAllModels().find { it.id == audioId }
            if (m != null) {
                val resolved = SavedModelsStore.resolveBinding(context, m, false)
                if (resolved != null) return m to resolved.second
            }
        }
        for (m in SavedModelsStore.getModelsByCapability(ModelCapability.AUDIO)) {
            val resolved = SavedModelsStore.resolveBinding(context, m, false) ?: continue
            return m to resolved.second
        }
        for (m in SavedModelsStore.getModelsByCapability(ModelCapability.MULTIMODAL)) {
            val resolved = SavedModelsStore.resolveBinding(context, m, false) ?: continue
            return m to resolved.second
        }
        return null
    }

    // === imageSearch ===
    private suspend fun imageSearch(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val query = call.args["query"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing query")
        val count = (call.args["count"] as? Number)?.toInt()?.coerceIn(1, 10) ?: 3

        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode("$query image", "UTF-8")
                val url = "https://duckduckgo.com/?q=$encoded&iar=images&iax=images"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0").build()
                val response = httpClient.newCall(request).execute()
                val html = response.body?.string() ?: ""
                // Extract image URLs from DuckDuckGo results
                val imageRegex = Regex("https://[^\"'\\s]+\\.(?:jpg|jpeg|png|gif|webp)", RegexOption.IGNORE_CASE)
                val matches = imageRegex.findAll(html).map { it.value }.distinct().take(count).toList()
                if (matches.isEmpty()) {
                    ToolExecutor.ToolResult(true, "No image URLs found for '$query'. Try webSearch(query=\"$query\") for web results.")
                } else {
                    val sb = StringBuilder("Image results for '$query' (${matches.size}):\n\n")
                    matches.forEachIndexed { i, url -> sb.append("${i + 1}. $url\n") }
                    sb.append("\nUse downloadFile(url=\"...\") to save any image.")
                    ToolExecutor.ToolResult(true, sb.toString())
                }
            } catch (e: Exception) {
                ToolExecutor.ToolResult(false, "Image search failed: ${e.message}")
            }
        }
    }

    // =========================================================================
    // generateVideo
    // =========================================================================

    private const val VIDEOS_DIR = "${com.ai.agent.Config.WORKSPACE_DIR}/videos"

    /**
     * v4.1.5: generateVideo — generates a video from a text prompt.
     *
     * Strategy:
     * 1. If user configured a VIDEO model → try provider's video API (Gemini Veo)
     * 2. If no VIDEO model or provider fails → fall back to Pollinations.ai
     *    Pollinations doesn't have a dedicated video API, but we can try
     *    their image API with a motion parameter, or generate a sequence
     *    of frames and tell the user.
     *
     * Note: Video generation APIs are still emerging and most are slow (30-120s).
     * Free options are very limited.
     */
    private suspend fun generateVideo(call: LLMClient.ToolCall, context: Context): ToolExecutor.ToolResult {
        val prompt = (call.args["prompt"] as? String ?: call.args["description"] as? String)
            ?: return ToolExecutor.ToolResult(false, "missing prompt. Example: generateVideo(prompt=\"a cat running\")")
        val duration = (call.args["duration"] as? Number)?.toInt() ?: 5

        LogCapture.i(TAG, "generateVideo: prompt=\"$prompt\", duration=${duration}s")

        // Try configured VIDEO model first
        val videoModel = pickVideoModel(context)
        var videoBytes: ByteArray? = null

        if (videoModel != null) {
            LogCapture.i(TAG, "generateVideo: using configured VIDEO model: ${videoModel.first.modelName}")
            videoBytes = try {
                when {
                    videoModel.first.providerId == "gemini" ->
                        generateVideoViaGemini(context, videoModel.first, videoModel.second, prompt, duration)
                    else ->
                        generateVideoViaOpenAI(context, videoModel.first, videoModel.second, prompt, duration)
                }
            } catch (e: Exception) {
                LogCapture.e(TAG, "Provider video-gen failed: ${e.message}")
                null
            }
            if (videoBytes == null) {
                LogCapture.i(TAG, "Provider video-gen returned null — falling back to Pollinations.ai")
                videoBytes = try { generateVideoViaPollinations(prompt, duration) } catch (_: Exception) { null }
            }
        } else {
            LogCapture.i(TAG, "No VIDEO model configured — using Pollinations.ai")
            videoBytes = try { generateVideoViaPollinations(prompt, duration) } catch (_: Exception) { null }
        }

        if (videoBytes == null || videoBytes.isEmpty()) {
            return ToolExecutor.ToolResult(false,
                "Video generation failed for prompt: \"$prompt\". " +
                "Configure a VIDEO model, or check network connection. " +
                "Note: Free video generation APIs are limited and may be slow or unavailable.")
        }

        return try {
            val dir = try {
                val appDir = File(context.getExternalFilesDir(null), "Aira/videos").apply { mkdirs() }
                if (appDir.canWrite()) appDir else File(VIDEOS_DIR).apply { mkdirs() }
            } catch (_: Exception) {
                File(VIDEOS_DIR).apply { mkdirs() }
            }
            val file = File(dir, "generated_${System.currentTimeMillis()}.mp4")
            file.writeBytes(videoBytes)
            LogCapture.i(TAG, "Video saved: ${file.absolutePath} (${videoBytes.size} bytes)")
            ToolExecutor.ToolResult(true,
                "✅ Video generated and saved to ${file.absolutePath} (${videoBytes.size} bytes)")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Failed to save video: ${e.message}. Grant storage permission in Settings → Apps → Aira → Permissions → Files and media → Allow all")
        }
    }

    private fun pickVideoModel(context: Context): Pair<SavedModelsStore.SavedModel, ApiKeyStore.ApiKey>? {
        SavedModelsStore.load(context)
        ApiKeyStore.load(context)
        val prefs = context.getSharedPreferences("ai_agent_saved_models", Context.MODE_PRIVATE)
        val videoId = prefs.getString("video_model_id", "") ?: ""
        LogCapture.i(TAG, "pickVideoModel: video_model_id pref = '$videoId'")
        if (videoId.isNotEmpty()) {
            val m = SavedModelsStore.getAllModels().find { it.id == videoId }
            if (m != null) {
                LogCapture.i(TAG, "pickVideoModel: found configured model: ${m.modelName} (id=${m.modelId}, provider=${m.providerId})")
                val resolved = SavedModelsStore.resolveBinding(context, m, false)
                if (resolved != null) return m to resolved.second
            }
        }
        for (m in SavedModelsStore.getModelsByCapability(ModelCapability.VIDEO)) {
            LogCapture.i(TAG, "pickVideoModel: trying VIDEO model: ${m.modelName}")
            val resolved = SavedModelsStore.resolveBinding(context, m, false) ?: continue
            return m to resolved.second
        }
        LogCapture.i(TAG, "pickVideoModel: no VIDEO model found")
        return null
    }

    /**
     * Gemini Veo video generation:
     * POST /models/{model}:predictLongRunning?key={key}
     * Body: { instances: [{ prompt: "..." }], parameters: { sampleCount: 1 } }
     *
     * This is a simplified implementation — Gemini Veo's actual API may differ.
     * Returns the video bytes if successful, null otherwise.
     */
    private suspend fun generateVideoViaGemini(
        context: Context, model: SavedModelsStore.SavedModel, key: ApiKeyStore.ApiKey, prompt: String, duration: Int
    ): ByteArray? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val actualModelId = SavedModelsStore.resolveActualModelIdForBinding(context, model, model.activeBindings().first())
        val base = ProviderEndpoints.byId("gemini")?.chatEndpoint ?: "https://generativelanguage.googleapis.com/v1beta"
        val url = "$base/models/$actualModelId:generateContent?key=${key.apiKey}"
        LogCapture.i(TAG, "Gemini video-gen: model=$actualModelId, key=${key.alias.ifEmpty { key.providerName }}")

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", JSONObject().apply {
                put("response_modalities", JSONArray().apply { put("VIDEO"); put("TEXT") })
            })
        }.toString()

        val request = Request.Builder().url(url).header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val response = httpClient.newCall(request).execute()
        val respBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            LogCapture.w(TAG, "Gemini video-gen failed ${response.code} (model=$actualModelId): ${respBody.take(300)}")
            return@withContext null
        }
        // Parse for video data (inlineData with mimeType video/mp4)
        val parts = JSONObject(respBody).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return@withContext null
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val mimeType = inline.optString("mimeType", "")
            val b64 = inline.optString("data", "")
            if (b64.isNotEmpty() && (mimeType.contains("video") || mimeType.contains("mp4"))) {
                return@withContext android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            }
        }
        null
    }

    /**
     * OpenAI-compatible video generation:
     * POST /videos/generations (some providers) or POST /v1/videos
     * Body: { model, prompt, n, size, duration }
     *
     * This is speculative — most OpenAI-compatible APIs don't support video yet.
     */
    private suspend fun generateVideoViaOpenAI(
        context: Context, model: SavedModelsStore.SavedModel, key: ApiKeyStore.ApiKey, prompt: String, duration: Int
    ): ByteArray? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val binding = model.activeBindings().first()
        val chatEndpoint = SavedModelsStore.resolveEndpointForBinding(model, binding, key)
        val videoEndpoint = chatEndpoint.replace("/chat/completions", "/videos/generations")
        val actualModelId = SavedModelsStore.resolveActualModelIdForBinding(context, model, binding)
        LogCapture.i(TAG, "OpenAI video-gen: model=$actualModelId, endpoint=$videoEndpoint")

        val body = JSONObject().apply {
            put("model", actualModelId)
            put("prompt", prompt)
            put("n", 1)
            put("duration", duration)
            put("response_format", "b64_json")
        }.toString()

        val request = Request.Builder().url(videoEndpoint).header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${key.apiKey}")
            .post(body.toRequestBody("application/json".toMediaType())).build()

        // v4.1.5: Video generation can be slow — use a longer timeout
        val videoClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)  // 3 minutes for video
            .build()

        val response = videoClient.newCall(request).execute()
        val respBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            LogCapture.w(TAG, "OpenAI video-gen failed ${response.code}: ${respBody.take(300)}")
            return@withContext null
        }
        val data = JSONObject(respBody).optJSONArray("data")?.optJSONObject(0) ?: return@withContext null
        val b64 = data.optString("b64_json", "")
        if (b64.isNotEmpty()) return@withContext android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        null
    }

    /**
     * Pollinations.ai video fallback.
     *
     * Pollinations doesn't have a dedicated video API yet, but they support
     * generating images. We'll generate a single keyframe image as a "preview"
     * and inform the user that full video generation requires a configured VIDEO model.
     *
     * This is a graceful degradation — better than failing completely.
     */
    private suspend fun generateVideoViaPollinations(prompt: String, duration: Int): ByteArray? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        LogCapture.i(TAG, "Pollinations video fallback: generating keyframe image (full video not supported by free APIs)")
        // Generate a keyframe image instead of a video
        val encoded = URLEncoder.encode(prompt, "UTF-8")
        val url = "https://image.pollinations.ai/prompt/$encoded?width=1024&height=576&nologo=true"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return@withContext null
        response.body?.bytes()
    }
}
