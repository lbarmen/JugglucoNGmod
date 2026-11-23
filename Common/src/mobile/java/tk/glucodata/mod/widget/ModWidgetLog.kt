package tk.glucodata.mod.widget

/**
 * JugglucoNGmod widget: our lines in upstream's debug log (`tk.glucodata.Log` goes to the shared log file). Counters,
 * codes and exception classes only - never a glucose value or a reading time.
 */
internal object ModWidgetLog {
    private const val TAG = "ModWidget"

    fun i(message: String) {
        try {
            tk.glucodata.Log.i(TAG, message)
        } catch (_: Throwable) {
        }
    }

    fun w(message: String) {
        try {
            tk.glucodata.Log.w(TAG, message)
        } catch (_: Throwable) {
        }
    }
}
