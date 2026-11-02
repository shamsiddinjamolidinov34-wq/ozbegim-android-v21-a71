package uz.ekranoquvchi.mvp.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import uz.ekranoquvchi.mvp.R
import uz.ekranoquvchi.mvp.ReaderService
import uz.ekranoquvchi.mvp.content.UzNumbers
import uz.ekranoquvchi.mvp.util.Prefs
import java.util.Calendar
import java.util.Locale

/**
 * Vaqt va batareya e'loni (Sozlamalar > Tafsilotlar > Vaqt va batareya).
 *
 * Vaqt: har 30 yoki 60 daqiqada, soatning aniq chegarasida (14:00, 14:30). Format 24 yoki 12 soatlik.
 * Ikki yo'l bir vaqtda ishlaydi: qurilma uyg'oq bo'lsa, Handler aniq vaqtda ishga tushiradi; qurilma uxlayotgan
 * (ekran o'chiq) bo'lsa, AlarmManager uni uyg'otadi. Qaysi biri birinchi kelsa, e'lon bir marta aytiladi.
 * "Aniq alarm" ruxsati berilmagan bo'lsa (Android 12 dan boshlab), alarm noaniqroq (bir necha daqiqagacha kechikishi
 * mumkin); ekran yoqiq paytida Handler baribir aniq vaqtda aytadi.
 *
 * Batareya: har 10 foizda yoki foydalanuvchi belgilagan qadamda (masalan, 90, 80, 70 yoki zaryadlanganda 20, 30).
 * Kuzatish xizmat ishlayotganda ACTION_BATTERY_CHANGED orqali bo'ladi (ekran o'chiq paytida ham).
 *
 * Matn o'qilayotgan paytda e'lon kelsa, "wait" rejimida o'qish tugashi kutiladi, "interrupt" rejimida darhol aytiladi.
 */
object TimeAnnouncer {
    const val ACTION_TICK = "uz.ekranoquvchi.mvp.TIME_TICK"
    private const val REQUEST_CODE = 7301

    private val main = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var tick: Runnable? = null
    private var lastSlot = -1L
    private var prevLevel = -1

    fun start(context: Context) {
        val app = context.applicationContext
        registerReceiver(app)
        registerPrefListener(app)
        reschedule(app)
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        cancelSchedule(app)
        try {
            receiver?.let { app.unregisterReceiver(it) }
        } catch (_: Throwable) {
        }
        receiver = null
        try {
            prefListener?.let { Prefs.get(app).unregisterOnSharedPreferenceChangeListener(it) }
        } catch (_: Throwable) {
        }
        prefListener = null
        prevLevel = -1
    }

    // ------------------------------------------------------------------ vaqt

    private fun intervalMinutes(app: Context): Int = when (Prefs.timeMode(app)) {
        "30" -> 30
        "60" -> 60
        else -> 0
    }

    /** now dan keyingi eng yaqin chegara: 30 daqiqada :00 va :30, 60 daqiqada :00. */
    fun nextTrigger(interval: Int, now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val minute = cal.get(Calendar.MINUTE)
        cal.add(Calendar.MINUTE, interval - (minute % interval))
        return cal.timeInMillis
    }

    /** Jadvalni qaytadan tuzadi (sozlama, vaqt yoki vaqt zonasi o'zgarganda; har e'londan keyin). */
    fun reschedule(context: Context) {
        val app = context.applicationContext
        cancelSchedule(app)
        val interval = intervalMinutes(app)
        if (interval == 0) return
        val now = System.currentTimeMillis()
        val at = nextTrigger(interval, now)
        val slot = at / 60_000L
        val r = Runnable { fire(app, slot) }
        tick = r
        main.postDelayed(r, maxOf(0L, at - now))
        scheduleAlarm(app, at)
    }

    private fun cancelSchedule(app: Context) {
        tick?.let { main.removeCallbacks(it) }
        tick = null
        try {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(alarmIntent(app))
        } catch (_: Throwable) {
        }
    }

    private fun alarmIntent(app: Context): PendingIntent {
        val i = Intent(app, TimeAlarmReceiver::class.java).setAction(ACTION_TICK)
        return PendingIntent.getBroadcast(app, REQUEST_CODE, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun scheduleAlarm(app: Context, at: Long) {
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(app)
        try {
            val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: Throwable) {
            // Ruxsat yo'q yoki tizim rad etdi: noaniq alarmga o'tamiz
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: Throwable) {
            }
        }
    }

    /** AlarmManager signali keldi (TimeAlarmReceiver). */
    fun onAlarm(context: Context) {
        val app = context.applicationContext
        if (intervalMinutes(app) == 0) return
        fire(app, System.currentTimeMillis() / 60_000L)
    }

    private fun fire(app: Context, slot: Long) {
        if (slot == lastSlot) return
        lastSlot = slot
        // Keyingi e'lon darhol rejalashtiriladi: jadval uzilmasin
        reschedule(app)
        if (intervalMinutes(app) == 0) return
        say(app, timeText(app), true)
    }

    /** Hozirgi vaqtni tanlangan format bilan o'qiladigan matnga aylantiradi (o'zbekcha raqamlar so'z bilan). */
    fun timeText(app: Context, cal: Calendar = Calendar.getInstance()): String {
        val h24 = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val uz = Locale.getDefault().language == "uz"
        if (Prefs.timeFormat(app) == "12") {
            val h12 = if (h24 % 12 == 0) 12 else h24 % 12
            val t = if (uz) UzNumbers.time(h12, m) else String.format(Locale.getDefault(), "%d:%02d", h12, m)
            return app.getString(R.string.tb_say_12, app.getString(partOfDay(h24)), t)
        }
        val t = if (uz) UzNumbers.time(h24, m) else String.format(Locale.getDefault(), "%d:%02d", h24, m)
        return app.getString(R.string.msg_status_time, t)
    }

    private fun partOfDay(h: Int): Int = when (h) {
        in 4..5 -> R.string.tb_part_dawn
        in 6..11 -> R.string.tb_part_morning
        in 12..17 -> R.string.tb_part_day
        in 18..21 -> R.string.tb_part_evening
        else -> R.string.tb_part_night
    }

    // ------------------------------------------------------------------ batareya

    private fun levelOf(i: Intent): Int {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    private fun isCharging(i: Intent): Boolean = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

    fun batteryText(app: Context, level: Int, charging: Boolean): String {
        val uz = Locale.getDefault().language == "uz"
        val shown = if (uz) UzNumbers.words(level) else level.toString()
        val sb = StringBuilder(app.getString(R.string.msg_status_battery, shown))
        if (charging) sb.append(", ").append(app.getString(R.string.msg_charging))
        return sb.toString()
    }

    private fun onBattery(app: Context, i: Intent) {
        val level = levelOf(i)
        if (level < 0) return
        val prev = prevLevel
        prevLevel = level
        if (prev < 0 || level == prev) return
        val mode = Prefs.batteryMode(app)
        if (mode == "off") return
        val step = if (mode == "custom") Prefs.batteryStep(app) else 10
        // Chegarani kesib o'tish: qadam karralisiga (90, 80, ...) yetganda aytiladi; o'qish oralig'ida bir necha foiz sakrasa ham bir marta
        val announce = if (level < prev) {
            val m = (prev - 1) / step * step
            m > 0 && level <= m
        } else {
            val m = (prev / step + 1) * step
            level >= m
        }
        if (announce || (level == 100 && prev < 100)) {
            say(app, batteryText(app, level, isCharging(i)), true)
        }
    }

    // ------------------------------------------------------------------ umumiy

    private fun registerReceiver(app: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val i = intent ?: return
                when (i.action) {
                    Intent.ACTION_BATTERY_CHANGED -> onBattery(app, i)
                    Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> reschedule(app)
                }
            }
        }
        try {
            val f = IntentFilter()
            f.addAction(Intent.ACTION_BATTERY_CHANGED)
            f.addAction(Intent.ACTION_TIME_CHANGED)
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED)
            val sticky = app.registerReceiver(r, f)
            receiver = r
            if (sticky != null) prevLevel = levelOf(sticky)
        } catch (_: Throwable) {
        }
    }

    private fun registerPrefListener(app: Context) {
        if (prefListener != null) return
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.TIME_MODE) reschedule(app)
        }
        try {
            Prefs.get(app).registerOnSharedPreferenceChangeListener(l)
            prefListener = l
        } catch (_: Throwable) {
        }
    }

    private fun say(app: Context, text: String, respectBusy: Boolean) {
        val svc = ReaderService.instance ?: return
        val wait = respectBusy && Prefs.timeBusy(app) == "wait"
        svc.announceTimed(text, wait)
    }

    /** Sozlamalar ekranidagi "hozir ayt" tugmalari: darhol aytiladi. */
    fun speakTimeNow(context: Context) {
        val app = context.applicationContext
        say(app, timeText(app), false)
    }

    fun speakBatteryNow(context: Context) {
        val app = context.applicationContext
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = levelOf(i)
        if (level < 0) return
        say(app, batteryText(app, level, isCharging(i)), false)
    }
}
