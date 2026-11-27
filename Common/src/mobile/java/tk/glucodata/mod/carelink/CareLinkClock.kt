package tk.glucodata.mod.carelink

/**
 * JugglucoNGmod, pump data (series "pump over Bluetooth", part B1): the one wall clock of the pump writers and the
 * pump notifications (the Bluetooth source writes through them). Kept in its own file since the cloud sign-in code it
 * once lived in was removed. Tests put a fixed or shifted clock here.
 */
object CareLinkClock {
    @Volatile
    internal var now: () -> Long = { System.currentTimeMillis() }
}
