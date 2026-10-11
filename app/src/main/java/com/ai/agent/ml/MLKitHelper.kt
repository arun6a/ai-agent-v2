package com.ai.agent.ml

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device text recognition using Google ML Kit.
 *
 * Runs ENTIRELY on-device — no API call, no internet, free.
 * Used as a fallback when accessibility service can't read screen text
 * (Canvas, images, custom views), but BEFORE calling the cloud VLM API.
 *
 * 3-tier screen reading:
 *   1. Accessibility text (free, instant) — already works
 *   2. ML Kit OCR (free, 0.1s, offline) — THIS
 *   3. VLM API (1 API call, 2-5s, needs internet) — fallback
 */
object MLKitHelper {

    private const val TAG = "MLKitHelper"
    private const val MIN_TEXT_LENGTH = 20  // minimum chars to consider useful
    private const val TIMEOUT_SECONDS = 3L

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Recognize text from a screenshot bitmap.
     * Runs synchronously (blocking) — call from a background thread.
     * Returns recognized text, or empty string if failed/no text found.
     */
    fun recognizeText(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        var resultText = ""
        val latch = CountDownLatch(1)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                resultText = visionText.text
                Log.i(TAG, "ML Kit recognized ${resultText.length} chars")
                latch.countDown()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "ML Kit failed: ${e.message}")
                latch.countDown()
            }

        // Wait for completion (with timeout)
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        return resultText
    }

    /**
     * Check if recognized text is useful enough.
     * Returns true if text has enough content to be meaningful.
     */
    fun hasEnoughText(text: String): Boolean {
        return text.trim().length >= MIN_TEXT_LENGTH
    }

    /**
     * Full hybrid pipeline: try ML Kit first, return result.
     * Returns the recognized text, or null if ML Kit couldn't help
     * (caller should fall back to VLM API).
     *
     * @param bitmap The screenshot to analyze
     * @return Recognized text if useful, null if not (fallback to VLM)
     */
    fun tryRecognize(bitmap: Bitmap): String? {
        val text = recognizeText(bitmap)
        return if (hasEnoughText(text)) {
            Log.i(TAG, "ML Kit succeeded — using on-device text (${text.length} chars)")
            text
        } else {
            Log.i(TAG, "ML Kit found insufficient text (${text.length} chars) — falling back to VLM")
            null
        }
    }
}
