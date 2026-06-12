package pl.sp8mb.owrx.scanner

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Short beep + vibration when the scanner locks onto a carrier. */
@Singleton
class ScanAlerter @Inject constructor(
    @ApplicationContext private val context: Context?,
) {
    var enabled = true

    private val vibrator: Vibrator? by lazy {
        val ctx = context ?: return@lazy null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun lockAlert() {
        if (!enabled) return
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).apply {
                startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            }
        } catch (e: Exception) {
            // ToneGenerator can fail if the audio path is busy; ignore
        }
        vibrator?.let { v ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(120)
            }
        }
    }
}
