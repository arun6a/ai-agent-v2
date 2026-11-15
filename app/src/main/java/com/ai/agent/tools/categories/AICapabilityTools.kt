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
            val dir = File(IMAGES_DIR).apply { mkdirs() }
            val file = File(dir, "generated_${System.currentTimeMillis()}.jpg")
            file.writeBytes(imageBytes)
            LogCapture.i(TAG, "Image saved: ${file.absolutePath} (${imageBytes.size} bytes)")
            ToolExecutor.ToolResult(true,
                "✅ Image generated and saved to ${file.absolutePath} (${imageBytes.size} bytes)")
        } catch (e: Exception) {
            ToolExecutor.ToolResult(false, "Failed to save image: ${e.message}")
        }
    }

    private fun pickImageGenModel(context: Context): Pair<SavedModelsStore.SavedModel, ApiKeyStore.ApiKey>? {
        SavedModelsStore.load(context)
        ApiKeyStore.load(context)
        val prefs = context.getSharedPreferences("ai_agent_saved_models", Context.MODE_PRIVATE)
        val imageGenId = prefs.getString("image_gen_model_id", "") ?: ""
        if (imageGenId.isNotEmpty()) {
            val m = SavedModelsStore.getAllModels().find { it.id == imageGenId }
            if (m != null) {
                val resolved = SavedModelsStore.resolveBinding(context, m, false)
                if (resolved != null) return m to resolved.second
            }
        }
        val default = SavedModelsStore.getDefaultModel(context)
        if (default != null && ModelCapability.IMAGE_GEN in default.capabilities) {
            val resolved = SavedModelsStore.resolveBinding(context, default, false)
            if (resolved != null) return default to resolved.second
        }
        for (m in SavedModelsStore.getModelsByCapability(ModelCapability.IMAGE_GEN)) {
            val resolved = SavedModelsStore.resolveBinding(context, m, false) ?: continue
            return m to resolved.second
        }
        return null
    }

    private suspend fun generateImageViaGemini(
        context: Context, model: SavedModelsStore.SavedModel, key: ApiKeyStore.ApiKey, prompt: String
    ): ByteArray? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val actualModelId = SavedModelsStore.resolveActualModelIdForBinding(context, model, model.activeBindings().first())
        val base = ProviderEndpoints.byId("gemini")?.chatEndpoint ?: "https://generativelanguage.googleapis.com/v1beta"
        val url = "$base/models/$actualModelId:generateContent?key=${key.apiKey}"
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
        if (!response.isSuccessful) { LogCapture.w(TAG, "Gemini image-gen failed ${response.code}"); return@withContext null }
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
}
