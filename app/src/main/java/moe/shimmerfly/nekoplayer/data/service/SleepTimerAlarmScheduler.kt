package moe.shimmerfly.nekoplayer.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber

/** Schedules and cancels the OS alarm backing the duration sleep timer. */
interface SleepTimerAlarmScheduler {
    fun schedule(triggerAtMillis: Long)
    fun cancel()
}

/**
 * [SleepTimerAlarmScheduler] backed by [AlarmManager], falling back to inexact alarms when
 * exact-alarm permission is unavailable or denied.
 */
class AlarmManagerSleepTimerScheduler(private val context: Context) : SleepTimerAlarmScheduler {

    private val alarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    private fun createPendingIntent(): PendingIntent {
        val intent = Intent(context, SleepTimerReceiver::class.java).apply {
            action = MusicService.ACTION_SLEEP_TIMER_EXPIRED
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun schedule(triggerAtMillis: Long) {
        val pendingIntent = createPendingIntent()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            }
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "Exact alarm denied; using inexact sleep timer")
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    override fun cancel() {
        alarmManager.cancel(createPendingIntent())
    }

    private companion object {
        const val TAG = "SleepTimerScheduler"
    }
}
