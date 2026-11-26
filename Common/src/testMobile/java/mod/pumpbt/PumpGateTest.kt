package mod.pumpbt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.pumpbt.ble.PumpGate
import tk.glucodata.mod.pumpbt.ble.PumpOutlet
import tk.glucodata.mod.pumpbt.core.PumpUuid
import tk.glucodata.mod.pumpbt.core.SeqCrypt
import tk.glucodata.mod.pumpbt.core.StatusReaderOpcode

/** JugglucoNGmod, pump over Bluetooth (trial): a blocked request never reaches Bluetooth and is journaled BLOCKED. */
class PumpGateTest {

    private class FakeOutlet : PumpOutlet {
        val sent = mutableListOf<String>()
        val payloads = mutableListOf<ByteArray>()
        override fun read(service: String, characteristic: String): Boolean = sent.add("read $characteristic")
        override fun subscribe(service: String, characteristic: String): Boolean = sent.add("subscribe $characteristic")
        override fun write(service: String, characteristic: String, payload: ByteArray): Boolean {
            payloads += payload
            return sent.add("write $characteristic")
        }
    }

    private val outlet = FakeOutlet()
    private val journal = mutableListOf<String>()
    private val gate = PumpGate(outlet) { journal += it }

    private fun blocked(service: String, characteristic: String, plaintext: ByteArray) {
        val before = journal.size
        assertFalse(gate.write(service, characteristic, plaintext))
        assertTrue(outlet.sent.isEmpty())
        assertTrue(journal.drop(before).joinToString(), journal.drop(before).any { it.startsWith("BLOCKED") })
    }

    @Test
    fun noWriteBeforeTheSerialNumber() {
        blocked(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, hex("33690f"))
    }

    @Test
    fun forbiddenWritesNeverReachBluetooth() {
        gate.serialVerified = true
        gate.crypt = SeqCrypt(ByteArray(16), ByteArray(8), 1)
        blocked(PumpUuid.IDD_SERVICE, PumpUuid.IDD_STATUS_READER_CP, hex("0c03")) // Reset Status 0x030C
        blocked(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, hex("3c33")) // Delete stored records
        blocked(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, hex("3c330f"))
        blocked(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, hex("550f")) // Abort
        blocked(PumpUuid.IDD_SERVICE, PumpUuid.IDD_COMMAND_CP, hex("8e14")) // any Command CP write
        blocked(PumpUuid.IDD_SERVICE, PumpUuid.IDD_COMMAND_CP, StatusReaderOpcode.GET_INSULIN_ON_BOARD.request)
        blocked(PumpUuid.IDD_SERVICE, "00009999-0000-1000-0000-009132591325", hex("00")) // unknown characteristic
        blocked(PumpUuid.IDD_SERVICE, PumpUuid.IDD_STATUS_READER_CP, hex("f30300")) // wrong length
        blocked(PumpUuid.CGM_SERVICE, PumpUuid.RECORD_ACCESS_CP, hex("0106")) // CGM RACP
        blocked(PumpUuid.CGM_SERVICE, PumpUuid.CGM_SPECIFIC_OPS_CP, hex("1a"))
    }

    @Test
    fun allowedWritesPassAndAreJournaled() {
        gate.serialVerified = true
        // RACP is not encrypted: the plaintext goes out
        assertTrue(gate.write(PumpUuid.IDD_SERVICE, PumpUuid.RECORD_ACCESS_CP, hex("33690f")))
        assertEquals(listOf("write 2A52"), outlet.sent)
        assertArrayEquals(hex("33690f"), outlet.payloads[0])
        assertTrue(journal.last(), journal.last().startsWith("ALLOWED"))
    }

    @Test
    fun encryptedWriteNeedsASession() {
        gate.serialVerified = true
        blocked(PumpUuid.IDD_SERVICE, PumpUuid.IDD_STATUS_READER_CP, StatusReaderOpcode.GET_INSULIN_ON_BOARD.request)
        gate.crypt = SeqCrypt(ByteArray(16), ByteArray(8), 1)
        assertTrue(gate.write(PumpUuid.IDD_SERVICE, PumpUuid.IDD_STATUS_READER_CP, StatusReaderOpcode.GET_INSULIN_ON_BOARD.request))
        val payload = outlet.payloads.single()
        assertEquals(2 + 3, payload.size) // ciphertext + sequence byte + 2-byte MAC
        assertNotEquals(StatusReaderOpcode.GET_INSULIN_ON_BOARD.request.toList(), payload.copyOf(2).toList())
    }

    @Test
    fun readsAndSubscriptionsFollowTheirLists() {
        assertFalse(gate.read(PumpUuid.IDD_SERVICE, PumpUuid.IDD_COMMAND_DATA))
        assertFalse(gate.subscribe(PumpUuid.CGM_SERVICE, PumpUuid.CGM_MEASUREMENT))
        assertFalse(gate.subscribe(PumpUuid.IDD_SERVICE, PumpUuid.IDD_COMMAND_CP))
        assertTrue(outlet.sent.isEmpty())
        assertEquals(3, journal.count { it.startsWith("BLOCKED") })

        assertTrue(gate.read(PumpUuid.DEVICE_INFO_SERVICE, PumpUuid.MODEL_NUMBER))
        assertTrue(gate.read(PumpUuid.IDD_SERVICE, PumpUuid.IDD_FEATURES))
        assertTrue(gate.subscribe(PumpUuid.IDD_SERVICE, PumpUuid.IDD_STATUS_READER_CP))
        assertEquals(3, outlet.sent.size)
        assertTrue(journal.last().startsWith("ALLOWED subscription"))
    }
}
