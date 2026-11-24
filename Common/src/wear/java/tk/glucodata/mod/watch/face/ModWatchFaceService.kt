package tk.glucodata.mod.watch.face

import android.view.SurfaceHolder
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.TapEvent
import androidx.wear.watchface.TapType
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.Notify

/**
 * JugglucoNGmod, watch block (part 3b): our watch face "Juggluco JugglucoNGmod" (watch plan, "Watch face"), built
 * like upstream's (`tk.glucodata.watchface.WatchFaceService`: the same `androidx.wear.watchface` library, a DIGITAL
 * face); declared only in the `mod` build's manifest (Common/src/wearMod/AndroidManifest.xml), so upstream's builds
 * do not list it. Upstream's face stays in the list under their name as a spare.
 *
 * No complication slots (plan: everything needed is already on the face). One setting, the graph's hours
 * ([FaceSettings], our own preference and editor [FaceSettingsActivity]; no `UserStyleSchema`). A tap on the value
 * opens upstream's main window of the watch app explicitly (`Applic.startMain()`, as their face does), never the package's launch intent: the package has several launcher
 * entries now (theirs, our "Snooze alarm"), and the first one could silently snooze an alert.
 */
class ModWatchFaceService : WatchFaceService() {

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): WatchFace {
        val renderer = ModFaceRenderer(applicationContext, surfaceHolder, watchState, currentUserStyleRepository)
        return WatchFace(WatchFaceType.DIGITAL, renderer).setTapListener(Taps(renderer))
    }

    private class Taps(private val renderer: ModFaceRenderer) : WatchFace.TapListener {
        override fun onTapEvent(tapType: Int, tapEvent: TapEvent, complicationSlot: ComplicationSlot?) {
            if (tapType != TapType.UP) return
            try {
                // as upstream's face: a tap stops a sounding alarm when the user has chosen so in their settings
                if (Natives.turnoffalarm()) Notify.stopalarm()
            } catch (_: Throwable) {
            }
            try {
                val layout = renderer.lastLayout ?: return
                if (FaceLayout.isOnValue(layout, tapEvent.xPos.toFloat(), tapEvent.yPos.toFloat())) {
                    Applic.RunOnUiThread { Applic.startMain() }
                }
            } catch (_: Throwable) {
            }
        }
    }
}
