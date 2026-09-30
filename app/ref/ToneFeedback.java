package com.zebra.rfid.demo.sdksample;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Audible feedback for reader connect/disconnect and newly seen tags. Main thread only. */
class ToneFeedback {

    private static final String TAG = "RFID_SAMPLE";
    private static final long SECOND_BEEP_DELAY_MS = 180;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ToneGenerator toneGenerator;

    ToneFeedback() {
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to initialize ToneGenerator", e);
        }
    }

    // Ascending two-beep "phone connected" sound.
    void playConnected() {
        playTwoTone(ToneGenerator.TONE_CDMA_CALLDROP_LITE, ToneGenerator.TONE_SUP_INTERCEPT_ABBREV);
    }

    // Descending two-beep "phone drop" sound.
    void playDisconnected() {
        playTwoTone(ToneGenerator.TONE_SUP_INTERCEPT_ABBREV, ToneGenerator.TONE_CDMA_CALLDROP_LITE);
    }

    void playNewTag() {
        play(ToneGenerator.TONE_PROP_BEEP, 50);
    }

    void release() {
        handler.removeCallbacksAndMessages(null);
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
    }

    private void playTwoTone(int firstTone, int secondTone) {
        play(firstTone, 150);
        handler.postDelayed(() -> play(secondTone, 300), SECOND_BEEP_DELAY_MS);
    }

    private void play(int tone, int durationMs) {
        if (toneGenerator == null) {
            return;
        }
        try {
            toneGenerator.startTone(tone, durationMs);
        } catch (RuntimeException e) {
            Log.e(TAG, "startTone failed: " + e.getMessage());
        }
    }
}
