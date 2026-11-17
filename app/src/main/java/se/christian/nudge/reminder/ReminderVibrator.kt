package se.christian.nudge.reminder

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import se.christian.nudge.session.PillLevel

/**
 * Short vibrations for the bigger pills: one for [PillLevel.PROMINENT], two for [PillLevel.PERSISTENT].
 * Never while the ringer is silent; from API 33 the notification usage also makes Do Not Disturb apply.
 */
class ReminderVibrator(context: Context) {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val audio = context.getSystemService(AudioManager::class.java)

    fun vibrate(level: PillLevel) {
        val effect = when (level) {
            PillLevel.SUBTLE -> return
            PillLevel.PROMINENT -> VibrationEffect.createOneShot(PULSE_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            PillLevel.PERSISTENT -> VibrationEffect.createWaveform(longArrayOf(0, PULSE_MS, GAP_MS, PULSE_MS), -1)
        }
        if (!vibrator.hasVibrator() || audio.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
        } else {
            vibrator.vibrate(effect)
        }
    }

    private companion object {
        const val PULSE_MS = 70L
        const val GAP_MS = 120L
    }
}
