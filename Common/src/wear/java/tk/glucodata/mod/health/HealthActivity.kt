package tk.glucodata.mod.health

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import tk.glucodata.R
import tk.glucodata.mod.watch.ModWatchLog

/**
 * JugglucoNGmod, watch block (part 4b): the page "JugglucoNGmod - health" with its own entry in the watch's app list:
 * what heart rate and steps may collect (permissions, asked in Glucove's order - [HealthPermissions.nextStep]), the
 * subscription, when the last batch went to the phone, the rows waiting, the last failure, and "Send now"
 * ([HealthHandOver.sendNow]). Upstream's watch app is not touched.
 *
 * A plain [Activity] with views made in code (no Compose), as our face editor. It never names Health Services: the
 * subscription goes through [HealthSources.current]. Disk reads run on [HealthCore.lightExecutor], subscribing on
 * [HealthCore.executor]; the page refreshes every 5 s while shown. Declared only in the `mod` build's manifest.
 */
class HealthActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var lines: TextView
    private lateinit var allow: Button
    private lateinit var send: Button
    @Volatile private var askedBackground = false
    private var resumed = false

    private val tick = object : Runnable {
        override fun run() {
            if (!resumed) return
            refresh()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(build())
        } catch (t: Throwable) {
            ModWatchLog.w("health page failed: ${t.javaClass.simpleName}")
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        main.post(tick)
        // A permission given in the system settings meanwhile, or a subscription lost: subscribe (Glucove did so too).
        if (supported() && HealthPermissions.read(this).any) subscribeIfNone()
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(tick)
        super.onPause()
    }

    private fun supported() = HealthSubscription.supported(Build.VERSION.SDK_INT)

    private fun build(): View {
        val side = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.12f).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(side, side, side, side)
        }
        column.addView(TextView(this).apply {
            setText(R.string.mod_watch_health_label)
            textSize = 16f
            gravity = Gravity.CENTER
        })
        lines = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
        }
        column.addView(lines)
        val minHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt()
        allow = Button(this).apply {
            setText(R.string.mod_watch_health_allow)
            this.minHeight = minHeight
            setOnClickListener { onAllow() }
        }
        send = Button(this).apply {
            setText(R.string.mod_watch_health_send)
            this.minHeight = minHeight
            setOnClickListener { onSend() }
        }
        column.addView(allow)
        column.addView(send)
        if (!supported()) {
            lines.setText(R.string.mod_watch_health_old_android)
            allow.visibility = View.GONE
            send.visibility = View.GONE
        }
        return ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
    }

    /** What the page shows, read off the main thread. */
    private class Shown(
        val perms: HealthPermissions.Perms,
        val registered: String,
        val lastSentAt: Long,
        val waiting: Int,
        val error: String,
        val askedBackground: Boolean,
    )

    private fun refresh() {
        if (!supported()) return
        val app = applicationContext
        try {
            HealthCore.lightExecutor.execute {
                try {
                    val state = HealthCore.get(app).state
                    val shown = Shown(
                        perms = HealthPermissions.read(app),
                        registered = state.registeredTypes,
                        lastSentAt = state.lastSentAt,
                        waiting = HealthCore.get(app).buffer.count(),
                        error = state.lastError.ifEmpty { state.registerError },
                        askedBackground = state.askedBackground,
                    )
                    main.post { show(shown) }
                } catch (t: Throwable) {
                    ModWatchLog.w("health page read failed: ${t.javaClass.simpleName}")
                }
            }
        } catch (t: Throwable) {
            ModWatchLog.w("health page refresh failed: ${t.javaClass.simpleName}")
        }
    }

    private fun show(s: Shown) {
        if (isFinishing || isDestroyed) return
        askedBackground = s.askedBackground
        fun mark(ok: Boolean) = getString(if (ok) R.string.mod_watch_health_yes else R.string.mod_watch_health_no)
        val types = s.registered.split(',').filter { it.isNotEmpty() }.map {
            getString(if (it == "hr") R.string.mod_watch_health_hr else R.string.mod_watch_health_steps)
        }
        val ageMin = ((System.currentTimeMillis() - s.lastSentAt) / 60_000L).coerceAtLeast(0)
        val text = buildList {
            add(getString(R.string.mod_watch_health_access, mark(s.perms.hr), mark(s.perms.activity), mark(s.perms.background)))
            add(getString(R.string.mod_watch_health_subscription, types.joinToString(", ").ifEmpty { getString(R.string.mod_watch_health_none) }))
            add(getString(R.string.mod_watch_health_last_batch,
                if (s.lastSentAt <= 0) getString(R.string.mod_watch_health_never) else getString(R.string.mod_watch_health_minutes_ago, ageMin)))
            if (s.waiting > 0) add(getString(R.string.mod_watch_health_waiting, s.waiting))
            if (s.error.isNotEmpty()) add(getString(R.string.mod_watch_health_error, s.error))
        }
        lines.text = text.joinToString("\n")
        allow.visibility = if (s.perms.anyMissing) View.VISIBLE else View.GONE
    }

    private fun onAllow() {
        try {
            val level = HealthPermissions.level(this)
            when (val step = HealthPermissions.nextStep(level, { HealthPermissions.granted(this, it) }, askedBackground)) {
                is HealthPermissions.Step.AskForeground -> requestPermissions(step.permissions.toTypedArray(), REQUEST_FOREGROUND)
                is HealthPermissions.Step.AskBackground -> askBackground(step.permission)
                HealthPermissions.Step.OpenSettings -> openAppSettings()
                HealthPermissions.Step.Done -> subscribe("permissions")
            }
        } catch (t: Throwable) {
            ModWatchLog.w("health page allow failed: ${t.javaClass.simpleName}")
        }
    }

    private fun askBackground(permission: String) {
        askedBackground = true
        val app = applicationContext
        HealthCore.lightExecutor.execute { runCatching { HealthCore.get(app).state.askedBackground = true } }
        requestPermissions(arrayOf(permission), REQUEST_BACKGROUND)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            val all = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            ModWatchLog.i("health permissions request=$requestCode granted=${grantResults.count { it == PackageManager.PERMISSION_GRANTED }} of ${grantResults.size}")
            val next = HealthPermissions.nextStep(HealthPermissions.level(this), { HealthPermissions.granted(this, it) }, askedBackground)
            if (requestCode == REQUEST_FOREGROUND && all && next is HealthPermissions.Step.AskBackground) {
                askBackground(next.permission)
            } else {
                subscribe("permissions")
            }
        } catch (t: Throwable) {
            ModWatchLog.w("health page permissions failed: ${t.javaClass.simpleName}")
        }
    }

    /** "Allow all the time" is often only in the app's settings (Permissions - Sensors). */
    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (t: Throwable) {
            ModWatchLog.w("health page settings failed: ${t.javaClass.simpleName}")
        }
    }

    private fun subscribe(reason: String) {
        val source = HealthSources.current ?: return
        val app = applicationContext
        HealthCore.executor.execute {
            try {
                source.register(app, reason)
            } catch (t: Throwable) {
                ModWatchLog.w("health page subscribe failed: ${t.javaClass.simpleName}")
            }
            main.post { if (resumed) refresh() }
        }
    }

    private fun subscribeIfNone() {
        val app = applicationContext
        HealthCore.lightExecutor.execute {
            val none = runCatching { HealthCore.get(app).state.registeredTypes.isEmpty() }.getOrDefault(false)
            if (none) main.post { subscribe("page") }
        }
    }

    private fun onSend() {
        if (HealthHandOver.sendNow(applicationContext)) main.postDelayed({ if (resumed) refresh() }, SEND_REFRESH_MS)
    }

    companion object {
        private const val REQUEST_FOREGROUND = 41
        private const val REQUEST_BACKGROUND = 42
        private const val REFRESH_MS = 5_000L
        private const val SEND_REFRESH_MS = 3_000L
    }
}
