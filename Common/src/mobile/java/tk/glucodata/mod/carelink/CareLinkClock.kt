package tk.glucodata.mod.carelink

/**
 * JugglucoNGmod, pump data (series "pump over Bluetooth", part B1): the one wall clock of the pump writers, the
 * notifications, the cloud poll and the CareLink window. Moved out of [CareLinkAuth] so that the writers, which the
 * Bluetooth source uses too, do not depend on the cloud sign-in file. Tests and the CareLink trial put a fixed or
 * shifted clock here.
 */
object CareLinkClock {
    @Volatile
    internal var now: () -> Long = { System.currentTimeMillis() }
}
