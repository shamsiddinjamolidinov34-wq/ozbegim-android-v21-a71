package uz.ekranoquvchi.mvp.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import uz.ekranoquvchi.mvp.R
import uz.ekranoquvchi.mvp.core.TimeAnnouncer
import uz.ekranoquvchi.mvp.util.Prefs

/**
 * Sozlamalar > Tafsilotlar > Vaqt va batareya: soatni har 30 yoki 60 daqiqada aytish (12 yoki 24 soatlik),
 * o'qish paytida kelsa nima qilish, batareya quvvatini har 10 foizda yoki surgich bilan belgilangan qadamda aytish.
 */
class TimeBatteryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.tb_title)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, TimeBatteryFragment())
                .commit()
        }
    }
}

class TimeBatteryFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        // Sozlamalar xizmat bilan bir xil (qurilma-himoyalangan) faylga yoziladi
        preferenceManager.preferenceDataStore = SharedPrefsStore(ctx.applicationContext, Prefs.get(ctx))
        setPreferencesFromResource(R.xml.preferences_time_battery, rootKey)

        findPreference<Preference>("time_test")?.setOnPreferenceClickListener {
            TimeAnnouncer.speakTimeNow(requireContext())
            true
        }
        findPreference<Preference>("battery_test")?.setOnPreferenceClickListener {
            TimeAnnouncer.speakBatteryNow(requireContext())
            true
        }
        findPreference<Preference>("time_exact_alarm")?.setOnPreferenceClickListener {
            openExactAlarmSettings()
            true
        }
        // Surgich ekran o'quvchi bilan qiyin bo'lishi mumkin: aniq son yozish oynasi ham bor (nutq tezligidagi kabi)
        findPreference<Preference>("battery_step_exact")?.setOnPreferenceClickListener {
            NumberDialog.show(requireContext(), R.string.tb_battery_step, Prefs.BATTERY_STEP, 1..50, 10) {
                refreshStep()
            }
            true
        }
        refreshStep()
    }

    override fun onResume() {
        super.onResume()
        refreshExactAlarm()
        refreshStep()
    }

    private fun refreshStep() {
        val step = Prefs.batteryStep(requireContext())
        findPreference<SeekBarPreference>(Prefs.BATTERY_STEP)?.value = step
        findPreference<Preference>("battery_step_exact")?.summary = getString(R.string.tb_battery_step_exact_summary, step)
    }

    private fun refreshExactAlarm() {
        val p = findPreference<Preference>("time_exact_alarm") ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            p.isVisible = false
            return
        }
        val am = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        p.summary = getString(if (am.canScheduleExactAlarms()) R.string.tb_exact_on else R.string.tb_exact_off)
    }

    private fun openExactAlarmSettings() {
        val uri = Uri.parse("package:" + requireContext().packageName)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, uri))
                return
            }
        } catch (_: Throwable) {
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        } catch (_: Throwable) {
        }
    }
}
