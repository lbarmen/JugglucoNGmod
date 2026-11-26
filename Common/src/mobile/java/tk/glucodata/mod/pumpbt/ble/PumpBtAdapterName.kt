package tk.glucodata.mod.pumpbt.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import tk.glucodata.mod.pumpbt.PumpBtStore

/**
 * JugglucoNGmod, pump over Bluetooth (trial): name variant B changes the phone's Bluetooth name to "Mobile NNNNNN" for
 * pairing; the name before is saved first ([PumpBtStore.originalAdapterName], never empty) and given back here - right
 * after a successful handshake (the reconnect advertisement carries no name), on Stop, and on the next opening of the
 * trial window when the process died while it was changed. When the name cannot be read, variant B does not change it.
 */
internal object PumpBtAdapterName {

    @SuppressLint("MissingPermission")
    fun restore(context: Context, store: PumpBtStore, journal: (String) -> Unit) {
        val original = store.originalAdapterName ?: return
        val adapter = (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        try {
            if (!adapter.isEnabled) {
                journal("phone Bluetooth name not given back yet: Bluetooth is off")
                return
            }
            val ok = adapter.setName(original)
            journal("phone Bluetooth name given back: \"$original\" (" + (if (ok) "accepted" else "refused") + ")")
            if (ok) store.originalAdapterName = null
        } catch (e: SecurityException) {
            journal("phone Bluetooth name not given back: Bluetooth permission missing")
        }
    }
}
