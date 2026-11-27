package mod.pumpbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tk.glucodata.mod.pumpbt.PumpBtKey
import tk.glucodata.mod.pumpbt.core.KeyDatabase
import tk.glucodata.mod.pumpbt.core.KeyDatabaseError
import tk.glucodata.mod.pumpbt.core.SakeDeviceType
import tk.glucodata.mod.pumpbt.core.toHex

/**
 * JugglucoNGmod, pump over Bluetooth (trial): the key database and its loading, on databases made up here (pattern
 * bytes with a correct CRC). No real key in this repository.
 */
class PumpBtKeyTest {

    private val mobilePump = fakeKeyDatabase(local = 4, remotes = listOf(1))
    private val sensorDisplay = fakeKeyDatabase(local = 8, remotes = listOf(2), seed = 2)

    @Test
    fun madeUpDatabaseWithRightCrcIsAccepted() {
        val db = KeyDatabase(mobilePump)
        assertEquals(SakeDeviceType.MOBILE_APPLICATION, db.localDeviceType)
        assertNotNull(db.remoteDevices[SakeDeviceType.INSULIN_PUMP])
        assertTrue(KeyDatabase.isMobilePump(db))

        val r = PumpBtKey.parse(mobilePump.toHex())
        assertTrue(r is PumpBtKey.Result.Ok)
        r as PumpBtKey.Result.Ok
        assertEquals(mobilePump.toHex(), r.hex)
        assertEquals(mobilePump.copyOf(4).toHex().uppercase(), r.crc)
    }

    @Test
    fun crcMismatchIsRefused() {
        val broken = mobilePump.copyOf().also { it[20] = (it[20].toInt() xor 0x01).toByte() }
        try {
            KeyDatabase(broken)
            fail("a wrong CRC must fail")
        } catch (_: KeyDatabaseError.CrcMismatch) {
        }
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.CRC), PumpBtKey.parse(broken.toHex()))
    }

    @Test
    fun wrongTypeIsRefused() {
        assertFalse(KeyDatabase.isMobilePump(KeyDatabase(sensorDisplay)))
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.WRONG_TYPE), PumpBtKey.parse(sensorDisplay.toHex()))
        // mobile application, but with keys for another device only
        val mobileSensor = fakeKeyDatabase(local = 4, remotes = listOf(2))
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.WRONG_TYPE), PumpBtKey.parse(mobileSensor.toHex()))
    }

    @Test
    fun garbageIsRefused() {
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.NOT_FOUND), PumpBtKey.parse(""))
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.NOT_FOUND), PumpBtKey.parse("hello, no key here"))
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.NOT_FOUND), PumpBtKey.parse("<html><body>404</body></html>"))
        // hex, but too short; a cut database no longer matches its CRC ("damaged")
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.NOT_FOUND), PumpBtKey.parse("0102"))
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.CRC), PumpBtKey.parse(mobilePump.toHex().dropLast(2)))
        try {
            KeyDatabase.fromHex("abc")
            fail("odd hex must fail")
        } catch (_: KeyDatabaseError.InvalidHex) {
        }
        try {
            KeyDatabase(ByteArray(5))
            fail("5 bytes must fail")
        } catch (_: KeyDatabaseError.TooShort) {
        }
    }

    @Test
    fun findsTheDatabaseOnAPageLikeKeyDatabasesMd() {
        // made-up page in the layout of OpenMinimed's key_databases.md: other database first, then ours
        val page = """
            # SAKE Key Databases

            ## Dumped Key DBs

            ### Primary Display <-> Glucose Sensor

            <code>
            ${spacedHex(sensorDisplay)}
            </code>

            ### Mobile Application <-> Insulin Pump

            <code>
            ${spacedHex(mobilePump)}
            </code>
        """.trimIndent()
        val found = KeyDatabase.findHex(page, KeyDatabase::isMobilePump)
        assertEquals(mobilePump.toHex(), found)
        val r = PumpBtKey.parse(page)
        assertTrue(r is PumpBtKey.Result.Ok)
        assertEquals(mobilePump.copyOf(4).toHex().uppercase(), (r as PumpBtKey.Result.Ok).crc)

        // a page with only the other database: the type is wrong, not "nothing found"
        val other = "<code>\n${spacedHex(sensorDisplay)}\n</code>"
        assertNull(KeyDatabase.findHex(other, KeyDatabase::isMobilePump))
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.WRONG_TYPE), PumpBtKey.parse(other))
    }

    @Test
    fun crcIsTheFirstFourBytes() {
        assertEquals("0A1B2C3D", PumpBtKey.crcOf("0a1b2c3d4e5f"))
        assertEquals("0A1B2C3D", PumpBtKey.crcOf("0a 1b 2c\n3d 4e"))
    }
}
