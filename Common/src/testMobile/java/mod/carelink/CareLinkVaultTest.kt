package mod.carelink

import android.app.Application
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.mod.carelink.AesGcmSessionCipher
import tk.glucodata.mod.carelink.CareLinkSession
import tk.glucodata.mod.carelink.CareLinkVault
import java.io.File
import javax.crypto.KeyGenerator

/**
 * JugglucoNGmod, CareLink 4b: the session file. Robolectric has no Android Keystore, so the same AES-256-GCM cipher
 * runs with a software key (plan, "not checked": the Keystore key itself is checked on the phone).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkVaultTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val file get() = File(app.noBackupFilesDir, "mod-carelink/session.bin")
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val session = CareLinkSession(
        accessToken = "made-up-access", refreshToken = "made-up-refresh", expiresAtMs = 1_000L, username = "tester",
        role = "PATIENT", patientId = null, country = "DE", region = "EU", baseUrlCareLink = "https://cl.example.eu",
        baseUrlCumulus = "https://cu.example.eu", tokenUrl = CareLinkTestData.TOKEN_URL, clientId = "abc123",
        loggedInAtMs = 0L,
    )

    @Before
    fun setUp() {
        CareLinkVault.cipher = AesGcmSessionCipher(keyProvider = { key })
        CareLinkVault.clear(app)
    }

    @After
    fun tearDown() {
        CareLinkVault.clear(app)
        CareLinkVault.cipher = AesGcmSessionCipher.keystore
    }

    @Test
    fun saveLoadClear() {
        assertFalse(CareLinkVault.exists(app))
        assertNull(CareLinkVault.load(app))
        CareLinkVault.save(app, session)
        assertTrue(CareLinkVault.exists(app))
        assertTrue(file.exists())
        assertFalse(File(file.parentFile, "session.bin.tmp").exists())
        assertEquals(session, CareLinkVault.load(app))
        val renewed = session.copy(accessToken = "made-up-access-2", refreshToken = "made-up-refresh-2", refreshedAtMs = 7L)
        CareLinkVault.save(app, renewed)
        assertEquals(renewed, CareLinkVault.load(app))
        CareLinkVault.clear(app)
        assertFalse(CareLinkVault.exists(app))
        assertNull(CareLinkVault.load(app))
    }

    @Test
    fun fileHoldsNoPlainText() {
        CareLinkVault.save(app, session)
        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(text.contains("made-up-refresh"))
        assertFalse(text.contains("tester"))
        assertFalse(text.contains("refreshToken"))
    }

    @Test
    fun aFileThatDoesNotOpenIsAStorageErrorAndIsKept() {
        CareLinkVault.save(app, session)
        val before = file.readBytes()
        // another key (as after a Keystore reset): the file must stay, the caller decides
        CareLinkVault.cipher = AesGcmSessionCipher(keyProvider = { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() })
        try {
            CareLinkVault.load(app)
            fail("expected StorageException")
        } catch (_: CareLinkVault.StorageException) {
        }
        assertTrue(file.exists())
        assertArrayEquals(before, file.readBytes())
        // a truncated file - the same
        CareLinkVault.cipher = AesGcmSessionCipher(keyProvider = { key })
        file.writeBytes(byteArrayOf(1, 2, 3))
        try {
            CareLinkVault.load(app)
            fail("expected StorageException")
        } catch (_: CareLinkVault.StorageException) {
        }
        assertTrue(file.exists())
    }

    @Test
    fun cipherRoundTripAndTamperIsRefused() {
        val c = AesGcmSessionCipher(keyProvider = { key })
        val sealed = c.seal("made-up".toByteArray())
        assertEquals("made-up", String(c.open(sealed)))
        assertFalse(sealed.copyOfRange(12, sealed.size).contentEquals("made-up".toByteArray()))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        try {
            c.open(sealed)
            fail("a changed file must not open")
        } catch (_: Exception) {
        }
    }

    @Test
    fun everySealHasItsOwnIv() {
        val c = AesGcmSessionCipher(keyProvider = { key })
        val a = c.seal("same".toByteArray())
        val b = c.seal("same".toByteArray())
        assertFalse(a.copyOfRange(0, 12).contentEquals(b.copyOfRange(0, 12)))
    }

    @Test
    fun aWriteStartedBeforeASignOutIsRefused() {
        val generation = CareLinkVault.generation
        CareLinkVault.clear(app) // sign-out in between
        assertFalse(CareLinkVault.save(app, session, generation))
        assertFalse(file.exists())
        assertTrue(CareLinkVault.save(app, session, CareLinkVault.generation))
        assertTrue(file.exists())
    }

    @Test
    fun aKeyTheKeystoreCannotUseIsMadeAgainOnANewSignIn() {
        var broken = true
        var resets = 0
        CareLinkVault.cipher = AesGcmSessionCipher(
            keyProvider = { if (broken) throw java.security.UnrecoverableKeyException("made-up") else key },
            keyReset = { resets++; broken = false },
        )
        // a renewal does not touch the key: it fails and leaves the decision to the caller
        try {
            CareLinkVault.save(app, session)
            fail("expected the broken key to fail")
        } catch (_: java.security.UnrecoverableKeyException) {
        }
        assertEquals(0, resets)
        // a new sign-in drops the key, makes a new one and stores the session
        broken = true
        assertTrue(CareLinkVault.saveNew(app, session, CareLinkVault.generation))
        assertEquals(1, resets)
        assertEquals(session, CareLinkVault.load(app))
    }
}
