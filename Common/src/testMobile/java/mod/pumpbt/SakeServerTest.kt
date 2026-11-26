package mod.pumpbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import tk.glucodata.mod.pumpbt.core.AES128
import tk.glucodata.mod.pumpbt.core.KeyDatabase
import tk.glucodata.mod.pumpbt.core.RandomSource
import tk.glucodata.mod.pumpbt.core.SakeDeviceType
import tk.glucodata.mod.pumpbt.core.SakeError
import tk.glucodata.mod.pumpbt.core.SakeServer
import java.util.Random

/**
 * JugglucoNGmod, pump over Bluetooth (trial): the SAKE server with a made-up key database answers garbage with a
 * SakeError and never with another exception. No real key and no captured handshake (iPump-mini's SakeTest vectors
 * come from the real key and are not in this repository).
 */
class SakeServerTest {

    private val db = KeyDatabase(fakeKeyDatabase(local = 4, remotes = listOf(1)))
    private val counter = RandomSource { n -> ByteArray(n) { (it * 17 + 3).toByte() } }

    private fun server() = SakeServer(db, random = counter)

    private fun expectSakeError(block: () -> Unit) {
        try {
            block()
            fail("expected a SakeError")
        } catch (_: SakeError) {
        }
    }

    @Test
    fun wrongLengthIsRefused() {
        expectSakeError { server().handle(ByteArray(19)) }
        expectSakeError { server().handle(ByteArray(21)) }
        expectSakeError { server().handle(ByteArray(0)) }
    }

    @Test
    fun handshakeMustStartWithZeros() {
        val s = server()
        expectSakeError { s.handle(ByteArray(20) { 1 }) }
        assertEquals(0, s.stage)
    }

    @Test
    fun firstAnswerNamesTheMobileApplication() {
        val s = server()
        val msg0 = s.handle(ByteArray(20))!!
        assertEquals(20, msg0.size)
        assertEquals(SakeDeviceType.MOBILE_APPLICATION.raw, msg0[0].toInt())
        assertEquals(1, msg0[1].toInt())
        assertEquals(1, s.stage)
    }

    @Test
    fun unknownOrMissingDeviceTypeIsRefused() {
        val unknown = server().also { it.handle(ByteArray(20)) }
        expectSakeError { unknown.handle(ByteArray(20).also { it[8] = 0x77 }) }
        // a glucose sensor: no keys for it in this database
        val sensor = server().also { it.handle(ByteArray(20)) }
        expectSakeError { sensor.handle(ByteArray(20).also { it[8] = SakeDeviceType.GLUCOSE_SENSOR.raw.toByte() }) }
    }

    @Test
    fun wrongProofAndWrongPermitAreRefused() {
        val s = server()
        s.handle(ByteArray(20))
        val msg1 = ByteArray(20) { (it + 1).toByte() }.also { it[8] = SakeDeviceType.INSULIN_PUMP.raw.toByte() }
        val msg2 = s.handle(msg1)!!
        assertEquals(20, msg2.size)
        assertEquals(3, s.stage)
        // garbage instead of the pump's proof
        val wrong = server().also {
            it.handle(ByteArray(20))
            it.handle(msg1)
        }
        expectSakeError { wrong.handle(ByteArray(20) { 0x55 }) }
        assertFalse(wrong.isComplete)

        // the right proof (computed from the made-up keys), then garbage instead of the permit
        val keys = db.remoteDevices.getValue(SakeDeviceType.INSULIN_PUMP)
        val serverTag = msg2.copyOfRange(0, 8)
        val serverKeyMaterial = msg2.copyOfRange(8, 16)
        val proof = AES128.cmac(keys.handshakeAuthKey, serverTag + serverKeyMaterial + keys.derivationKey, 8)
        val msg4 = s.handle(proof + ByteArray(12))
        assertNotNull(msg4)
        assertEquals(5, s.stage)
        expectSakeError { s.handle(ByteArray(20) { 0x33 }) }
        assertFalse(s.isComplete)
    }

    @Test
    fun randomMessagesNeverCrash() {
        val rnd = Random(42)
        repeat(300) {
            val s = server()
            val steps = rnd.nextInt(4)
            try {
                for (i in 0..steps) {
                    val msg = if (i == 0 && rnd.nextBoolean()) ByteArray(20) else ByteArray(if (rnd.nextInt(10) == 0) rnd.nextInt(40) else 20).also(rnd::nextBytes)
                    s.handle(msg)
                }
            } catch (_: SakeError) {
            }
        }
    }
}
