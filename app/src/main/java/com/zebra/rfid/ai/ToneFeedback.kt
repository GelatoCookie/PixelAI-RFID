package com.zebra.rfid.ai

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Audible feedback for reader connect/disconnect and newly seen tags.
 */
class ToneFeedback {

    private val handler = Handler(Looper.getMainLooper())
    private var toneGenerator: ToneGenerator? = null

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to initialize ToneGenerator", e)
        }
    }

    /** Ascending two-beep sound when connected. */
    fun playConnected() {
        playTwoTone(ToneGenerator.TONE_CDMA_CALLDROP_LITE, ToneGenerator.TONE_SUP_INTERCEPT_ABBREV)
    }

    /** Descending two-beep sound when disconnected. */
    fun playDisconnected() {
        playTwoTone(ToneGenerator.TONE_SUP_INTERCEPT_ABBREV, ToneGenerator.TONE_CDMA_CALLDROP_LITE)
    }

    /** Short beep when a new tag is scanned. */
    fun playNewTag() {
        play(ToneGenerator.TONE_PROP_BEEP, 50)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        toneGenerator?.release()
        toneGenerator = null
    }

    private fun playTwoTone(firstTone: Int, secondTone: Int) {
        play(firstTone, 150)
        handler.postDelayed({ play(secondTone, 300) }, SECOND_BEEP_DELAY_MS)
    }

    private fun play(tone: Int, durationMs: Int) {
        val tg = toneGenerator ?: return
        try {
            tg.startTone(tone, durationMs)
        } catch (e: RuntimeException) {
            Log.e(TAG, "startTone failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ToneFeedback"
        private const val SECOND_BEEP_DELAY_MS = 180L
    }
}
