package mod.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.util.SizeF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.mod.widget.ModWidgetDots
import tk.glucodata.mod.widget.ModWidgetLayout
import tk.glucodata.mod.widget.ModWidgetNumberSize
import tk.glucodata.mod.widget.ModWidgetSettings
import tk.glucodata.mod.widget.ModWidgetSettingsStore
import tk.glucodata.mod.widget.ModWidgetUpdater

/** JugglucoNGmod widget: per-widget settings in our own file, and the sizes we draw. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModWidgetSettingsStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun defaultsAreTheXdripLook() {
        val d = ModWidgetSettingsStore.read(context, 7)
        assertEquals(ModWidgetSettings.DEFAULT, d)
        assertEquals(100, d.backgroundTransparency)
        assertEquals(0, d.backgroundAlpha)
        assertEquals(3, d.periodHours)
        assertEquals(ModWidgetDots.AVERAGE_5MIN, d.dots)
        assertTrue(d.showDelta && d.showAge && d.colorByRange)
        assertEquals(false, d.rangeLines)
        assertEquals(ModWidgetLayout.OVERLAY, d.layout)
        assertEquals(ModWidgetNumberSize.AUTO, d.numberSize)
    }

    @Test
    fun eachWidgetKeepsItsOwnAndDeleteRemovesOnlyIt() {
        val a = ModWidgetSettings(backgroundTransparency = 50, periodHours = 6, dots = ModWidgetDots.LINE,
            showDelta = false, showAge = false, colorByRange = false, rangeLines = true,
            layout = ModWidgetLayout.TOP, numberSize = ModWidgetNumberSize.L)
        val b = ModWidgetSettings(backgroundTransparency = 0, periodHours = 1)
        ModWidgetSettingsStore.write(context, 1, a)
        ModWidgetSettingsStore.write(context, 2, b)
        assertEquals(a, ModWidgetSettingsStore.read(context, 1))
        assertEquals(b, ModWidgetSettingsStore.read(context, 2))
        ModWidgetSettingsStore.delete(context, 1)
        assertEquals(ModWidgetSettings.DEFAULT, ModWidgetSettingsStore.read(context, 1))
        assertEquals(b, ModWidgetSettingsStore.read(context, 2))
        // "w1." is not a prefix of "w12."
        ModWidgetSettingsStore.write(context, 12, a)
        ModWidgetSettingsStore.delete(context, 1)
        assertEquals(a, ModWidgetSettingsStore.read(context, 12))
    }

    @Test
    fun damagedValuesFallBackToDefaults() {
        context.getSharedPreferences(ModWidgetSettingsStore.FILE, Context.MODE_PRIVATE).edit()
            .putInt("w3.transparency", 33).putInt("w3.period", 5).putString("w3.dots", "SQUARES").apply()
        val s = ModWidgetSettingsStore.read(context, 3)
        assertEquals(100, s.backgroundTransparency)
        assertEquals(3, s.periodHours)
        assertEquals(ModWidgetDots.AVERAGE_5MIN, s.dots)
        assertEquals(255, ModWidgetSettings(backgroundTransparency = 0).backgroundAlpha)
        assertEquals(128, ModWidgetSettings(backgroundTransparency = 50).backgroundAlpha)
    }

    @Test
    fun sizesFromAndroid12ListOrFromTheOldRange() {
        val sizes = Bundle().apply {
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
                arrayListOf(SizeF(640f, 48f), SizeF(320f, 72f), SizeF(320f, 72f)))
        }
        assertEquals(listOf(SizeF(320f, 72f), SizeF(640f, 48f)), ModWidgetUpdater.sizesDp(context, sizes))
        val old = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 600)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 50)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 80)
        }
        assertEquals(listOf(SizeF(300f, 80f)), ModWidgetUpdater.sizesDp(context, old))
        assertEquals(1, ModWidgetUpdater.sizesDp(context, null).size)
    }

    @Test
    fun picturesPastTheScreenAreaAreDrawnSmaller() {
        val m = context.resources.displayMetrics
        val screenDp = SizeF(m.widthPixels / m.density, m.heightPixels / m.density)
        assertEquals(1f, ModWidgetUpdater.pictureScale(context, listOf(SizeF(340f, 60f), SizeF(600f, 40f))), 0f)
        val scale = ModWidgetUpdater.pictureScale(context, listOf(screenDp, screenDp))
        assertEquals(kotlin.math.sqrt(0.5f), scale, 1e-3f)
    }
}
