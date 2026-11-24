package tk.glucodata.mod.health.hs

import android.os.SystemClock
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.HeartRateAccuracy
import java.time.Instant
import tk.glucodata.mod.health.HealthCore
import tk.glucodata.mod.health.HealthIntake
import tk.glucodata.mod.health.HealthSubscription
import tk.glucodata.mod.watch.ModWatchLog

/**
 * JugglucoNGmod, watch block (part 4b): Health Services hands the collected heart rate and day step counts here
 * (when it decides - every 1.5-3.5 minutes on the Galaxy Watch 5 Pro, watch log 19.11.2026 - and at once after a
 * flush). The data goes to [HealthIntake.deliver], which writes it to our queue before this callback returns and then
 * sends on our thread when no batch went for a minute ([tk.glucodata.mod.health.HealthSender.sendIfDue]). Times of Health Services count from the watch's boot; they
 * become ordinary times here, as Glucove did ([HealthSubscription.bootEpochMs]).
 *
 * Declared only in the `mod` build's manifest, bound by Health Services alone (its binding permission). Never throws.
 */
class ModHealthListenerService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        try {
            val boot = Instant.ofEpochMilli(HealthSubscription.bootEpochMs(System.currentTimeMillis(), SystemClock.elapsedRealtime()))
            val heartRate = dataPoints.getData(DataType.HEART_RATE_BPM).map { p ->
                val status = (p.accuracy as? HeartRateAccuracy)?.sensorStatus
                HealthIntake.HeartRate(
                    timeMs = p.getTimeInstant(boot).toEpochMilli(),
                    bpm = p.value.toFloat(),
                    noContact = status != null && status.id == HeartRateAccuracy.SensorStatus.NO_CONTACT.id,
                )
            }
            val steps = dataPoints.getData(DataType.STEPS_DAILY).map { p ->
                HealthIntake.StepsDay(
                    endMs = p.getEndInstant(boot).toEpochMilli(),
                    steps = p.value,
                    dayStartMs = p.getStartInstant(boot).toEpochMilli(),
                )
            }
            HealthIntake.deliver(applicationContext, heartRate, steps)
        } catch (t: Throwable) {
            ModWatchLog.w("health data not taken: ${t.javaClass.simpleName}")
        }
    }

    override fun onPermissionLost() {
        try {
            HealthCore.get(applicationContext).state.registeredTypes = ""
            ModWatchLog.w("health permission lost, subscription gone")
        } catch (t: Throwable) {
            ModWatchLog.w("health permission lost not kept: ${t.javaClass.simpleName}")
        }
    }
}
