package com.ai.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Manages speech-to-text (STT) and text-to-speech (TTS).
 * Supports English (en-IN) and Tamil (ta-IN).
 */
class VoiceManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var currentLang = "en-IN"
    private var isListening = false

    var onPartialResult: ((String) -> Unit)? = null
    var onFinalResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onListeningStateChanged: ((Boolean) -> Unit)? = null
    var onSpeakingStateChanged: ((Boolean) -> Unit)? = null

    init {
        initTTS()
    }

    private fun initTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.d("VoiceManager", "TTS initialized")
            } else {
                Log.e("VoiceManager", "TTS init failed: $status")
            }
        }
    }

    fun setLanguage(lang: String) {
        currentLang = lang
        tts?.language = Locale(lang)
    }

    fun startListening() {
        if (isListening) return

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.let { onPartialResult?.invoke(it) }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    isListening = false
                    onListeningStateChanged?.invoke(false)
                    if (text.isNotEmpty()) {
                        onFinalResult?.invoke(text)
                    }
                }
                override fun onError(error: Int) {
                    isListening = false
                    onListeningStateChanged?.invoke(false)
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        else -> "Error $error"
                    }
                    onError?.invoke(msg)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            recognizer?.startListening(intent)
            isListening = true
            onListeningStateChanged?.invoke(true)
        } catch (e: Exception) {
            Log.e("VoiceManager", "Failed to start listening", e)
            isListening = false
            onError?.invoke("Failed to start: ${e.message}")
        }
    }

    fun stopListening() {
        if (isListening) {
            recognizer?.stopListening()
            isListening = false
            onListeningStateChanged?.invoke(false)
        }
    }

    fun speak(text: String, lang: String? = null) {
        val language = lang ?: currentLang
        tts?.language = Locale(language)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "msg_${System.currentTimeMillis()}")
        onSpeakingStateChanged?.invoke(true)
    }

    fun stopSpeaking() {
        tts?.stop()
        onSpeakingStateChanged?.invoke(false)
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
