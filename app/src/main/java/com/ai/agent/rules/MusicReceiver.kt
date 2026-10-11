package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import com.ai.agent.storage.AgentDatabase

/**
 * Music/audio playback trigger (v6.1.0).
 * - music_started: when audio becomes noisy (headphones unplugged while playing)
 * - audio_becoming_noisy: headphones unplugged
 */
class MusicReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MusicReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                Log.i(TAG, "Audio becoming noisy (headphones unplugged while playing)")
                triggerRules(context, "headphones_unplugged_while_playing")
            }
            "com.android.music.playstatechanged" -> {
                val playing = intent.getBooleanExtra("playing", false)
                if (playing) {
                    Log.i(TAG, "Music started playing")
                    triggerRules(context, "music_started")
                } else {
                    Log.i(TAG, "Music stopped")
                    triggerRules(context, "music_stopped")
                }
            }
        }
    }

    private fun triggerRules(context: Context, triggerType: String) {
        try {
            val db = AgentDatabase(context)
            val rules = db.getRules(enabledOnly = true).filter { it.triggerType == triggerType }
            for (rule in rules) {
                RuleTriggerReceiver.triggerRuleAction(context, rule)
                db.logAction("Music rule fired: ${rule.name} ($triggerType)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger rules", e)
        }
    }
}
