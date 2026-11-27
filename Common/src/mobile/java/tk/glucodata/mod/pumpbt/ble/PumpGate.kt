// SPDX-License-Identifier: GPL-3.0-or-later
// Based on iPump-mini (https://github.com/sgitaize/iPump-mini, commit 29aa932),
// apps/android/app/src/main/java/de/sgitaize/openminipump/ble/PumpLink.kt (guardedRead, guardedSubscribe,
// guardedWrite, ENCRYPTED), by Simon Gutjahr.
// Modified for JugglucoNGmod: moved out of the link into a class of its own with an outlet interface (testable without
// Android); English journal lines with the gate's decision (ALLOWED / BLOCKED); no session storage.
package tk.glucodata.mod.pumpbt.ble

import tk.glucodata.mod.pumpbt.core.PolicyDecision
import tk.glucodata.mod.pumpbt.core.PumpUuid
import tk.glucodata.mod.pumpbt.core.ReadOnlyPolicy
import tk.glucodata.mod.pumpbt.core.SeqCrypt
import tk.glucodata.mod.pumpbt.core.WriteRequest
import tk.glucodata.mod.pumpbt.core.toHex

/**
 * What the gate hands to Bluetooth once a request passed. The link's implementation is the only code that queues a
 * read, a CCCD write or a characteristic write on the pump, and only [PumpGate] holds it.
 */
internal interface PumpOutlet {
    fun read(service: String, characteristic: String): Boolean
    fun subscribe(service: String, characteristic: String): Boolean
    fun write(service: String, characteristic: String, payload: ByteArray): Boolean
}

/**
 * JugglucoNGmod, pump over Bluetooth (trial): the one gate towards the pump - read only. Every characteristic write
 * passes [ReadOnlyPolicy.check] on the plaintext (only "Get …" Status Reader requests and the RACP "Report Stored
 * Records" / "Report Number of Stored Records"), every subscription (CCCD write) [ReadOnlyPolicy.maySubscribe], every
 * read [ReadOnlyPolicy.mayRead]; no write before the pump's serial number was read. Anything else is not sent and the
 * journal gets a `BLOCKED …` line; what passes gets `ALLOWED …`. Encryption happens here, after the check.
 */
internal class PumpGate(private val outlet: PumpOutlet, private val log: (String) -> Unit) {

    /** Set once the pump's serial number was read on this client link. */
    var serialVerified = false

    /** Session cipher of the current handshake; null - none. Never stored anywhere. */
    var crypt: SeqCrypt? = null

    fun read(service: String, characteristic: String): Boolean {
        val c = PumpUuid.normalize(characteristic)
        if (!ReadOnlyPolicy.mayRead(c)) {
            log("BLOCKED read of $c: not on the read list")
            return false
        }
        return outlet.read(service, c)
    }

    fun subscribe(service: String, characteristic: String): Boolean {
        val c = PumpUuid.normalize(characteristic)
        if (!ReadOnlyPolicy.maySubscribe(c)) {
            log("BLOCKED subscription to $c: not on the subscription list")
            return false
        }
        log("ALLOWED subscription to $c (CCCD write)")
        return outlet.subscribe(service, c)
    }

    /** The only way to write a pump characteristic. */
    fun write(service: String, characteristic: String, plaintext: ByteArray): Boolean {
        val c = PumpUuid.normalize(characteristic)
        if (!serialVerified) {
            log("BLOCKED write to $c ${plaintext.toHex()}: the pump's serial number was not read yet")
            return false
        }
        val decision = ReadOnlyPolicy.check(WriteRequest(service, c, plaintext))
        if (decision is PolicyDecision.Blocked) {
            log("BLOCKED ${decision.reason}")
            return false
        }
        var payload = plaintext
        if (c in ENCRYPTED) {
            val cr = crypt ?: run {
                log("BLOCKED write to $c ${plaintext.toHex()}: no session")
                return false
            }
            payload = try {
                cr.encrypt(plaintext)
            } catch (e: Exception) {
                log("write to $c not sent: encryption failed (${e.javaClass.simpleName})")
                return false
            }
        }
        log("ALLOWED ${(decision as PolicyDecision.Allowed).reason}: ${plaintext.toHex()}")
        val queued = outlet.write(service, c, payload)
        if (!queued) log("write to $c not queued")
        return queued
    }

    companion object {
        /** Characteristics whose values are SAKE-encrypted (OpenMinimed pump-services.md). */
        val ENCRYPTED: Set<String> = listOf(
            PumpUuid.IDD_STATUS_CHANGED, PumpUuid.IDD_STATUS, PumpUuid.IDD_ANNUNCIATION, PumpUuid.IDD_FEATURES,
            PumpUuid.IDD_STATUS_READER_CP, PumpUuid.IDD_COMMAND_CP, PumpUuid.IDD_COMMAND_DATA, PumpUuid.IDD_HISTORY_DATA,
            PumpUuid.CGM_MEASUREMENT, PumpUuid.CGM_STATUS, PumpUuid.CGM_SESSION_START_TIME, PumpUuid.CGM_SPECIFIC_OPS_CP,
        ).map(PumpUuid::normalize).toSet()
    }
}
