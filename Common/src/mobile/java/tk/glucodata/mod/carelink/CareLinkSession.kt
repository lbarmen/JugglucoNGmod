package tk.glucodata.mod.carelink

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * JugglucoNGmod, CareLink (stage 4b): the session after sign-in - the access key (lives about 3 h) and the refresh
 * key for renewal without a password, region addresses and the user name the cloud gives data for
 * (`display/message` wants `username`). MOD never sees or keeps the password. [toString] shows no keys and no name,
 * so the session never reaches a log as a string.
 */
data class CareLinkSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val username: String,
    /** Role from `users/me`: PATIENT, CARE_PARTNER...; null - not asked yet. */
    val role: String?,
    /** Patient user name of a care partner account (`links/patients`); null for a patient account. */
    val patientId: String?,
    val country: String?,
    val region: String,
    val baseUrlCareLink: String,
    val baseUrlCumulus: String,
    val tokenUrl: String,
    val clientId: String,
    val loggedInAtMs: Long,
    val refreshedAtMs: Long? = null,
) {
    val carePartner: Boolean get() = role == "CARE_PARTNER" || role == "CARE_PARTNER_OUS"

    override fun toString(): String =
        "CareLinkSession(region=$region, country=$country, role=$role, expiresAtMs=$expiresAtMs, " +
            "loggedInAtMs=$loggedInAtMs, refreshedAtMs=$refreshedAtMs)"

    fun toJson(): JSONObject = JSONObject().apply {
        put("accessToken", accessToken)
        put("refreshToken", refreshToken)
        put("expiresAtMs", expiresAtMs)
        put("username", username)
        put("role", role ?: JSONObject.NULL)
        put("patientId", patientId ?: JSONObject.NULL)
        put("country", country ?: JSONObject.NULL)
        put("region", region)
        put("baseUrlCareLink", baseUrlCareLink)
        put("baseUrlCumulus", baseUrlCumulus)
        put("tokenUrl", tokenUrl)
        put("clientId", clientId)
        put("loggedInAtMs", loggedInAtMs)
        put("refreshedAtMs", refreshedAtMs ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): CareLinkSession? = CareLinkSession(
            accessToken = o.clStr("accessToken") ?: return null,
            refreshToken = o.clStr("refreshToken") ?: return null,
            expiresAtMs = o.clLong("expiresAtMs") ?: return null,
            username = o.clStr("username") ?: return null,
            role = o.clStr("role"),
            patientId = o.clStr("patientId"),
            country = o.clStr("country"),
            region = o.clStr("region") ?: return null,
            baseUrlCareLink = o.clStr("baseUrlCareLink") ?: return null,
            baseUrlCumulus = o.clStr("baseUrlCumulus") ?: return null,
            tokenUrl = o.clStr("tokenUrl") ?: return null,
            clientId = o.clStr("clientId") ?: return null,
            loggedInAtMs = o.clLong("loggedInAtMs") ?: return null,
            refreshedAtMs = o.clLong("refreshedAtMs"),
        )

        fun decode(text: String): CareLinkSession? = CareLinkConfig.parse(text)?.let(::fromJson)
    }
}

/** Seals and opens the session file. An interface: Robolectric has no Android Keystore, tests use a software key. */
interface SessionCipher {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray

    /** Drop the key so the next [seal] makes a new one (a key the Keystore can no longer use). */
    fun resetKey() {}
}

/**
 * AES-256-GCM, file = 12-byte IV + cipher text with the tag (a new IV from the provider on every seal). The key comes
 * from [keyProvider]; [keyReset] drops it.
 */
class AesGcmSessionCipher(
    private val keyProvider: () -> SecretKey,
    private val keyReset: () -> Unit = {},
) : SessionCipher {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size <= IV_BYTES) throw SealedDataTooShortException()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    override fun resetKey() {
        keyReset()
    }

    /**
     * The sealed data is shorter than its IV: a cut file that will never open. Its own class, not the wide
     * IllegalArgumentException, so that only this case wipes the session (critic 4d2, N8).
     */
    class SealedDataTooShortException : java.security.GeneralSecurityException("sealed data too short")

    companion object {
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "mod_carelink_session"

        /** The phone's key in Android Keystore: never leaves the phone, no backup carries it. */
        val keystore: SessionCipher by lazy { AesGcmSessionCipher(::keystoreKey, ::deleteKeystoreKey) }

        @Synchronized
        private fun keystoreKey(): SecretKey {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }

        @Synchronized
        private fun deleteKeystoreKey() {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        }
    }
}

/**
 * Protected session store: the file `noBackupFilesDir/mod-carelink/session.bin`, sealed by [cipher] (on the phone -
 * AES-256-GCM with the Android Keystore key `mod_carelink_session`). noBackupFilesDir is in no backup: not in Android's
 * (allowBackup=false anyway) and not in upstream's settings export, which takes every shared_prefs file. Written through
 * a temporary file, fsync and rename.
 *
 * Keystore work (seal, open) runs outside the file lock: the lock covers file reads, writes and deletes only, so
 * [clear] (sign-out from the main thread) never waits for a slow Keystore. [generation] grows on every [clear]: a write
 * started before a sign-out ([save] with the generation it began with) is refused instead of bringing the session back.
 */
object CareLinkVault {

    /** The session file exists but did not open (Keystore not ready before unlock, broken file): keep it, report. */
    class StorageException(cause: Throwable?) : Exception("CareLink session not readable", cause)

    @Volatile
    internal var cipher: SessionCipher = AesGcmSessionCipher.keystore

    private val lock = Any()

    @Volatile
    var generation: Long = 0L
        private set

    internal fun dir(context: Context): File = File(context.applicationContext.noBackupFilesDir, "mod-carelink")

    private fun file(context: Context): File = File(dir(context), "session.bin")

    /** Is there a session - by the file alone, without opening it (cheap; for scheduling decisions). */
    fun exists(context: Context): Boolean = file(context).exists()

    /** null - no session file; [StorageException] - a file that does not open (it is kept). */
    fun load(context: Context): CareLinkSession? {
        val f = file(context)
        val bytes: ByteArray = synchronized(lock) {
            if (!f.exists()) return null
            try {
                f.readBytes()
            } catch (t: Throwable) {
                throw StorageException(t)
            }
        }
        val session = try {
            CareLinkSession.decode(String(cipher.open(bytes), Charsets.UTF_8))
        } catch (t: Throwable) {
            throw StorageException(t)
        }
        return session ?: throw StorageException(null)
    }

    /**
     * Writes [session] if no [clear] happened since [expectedGeneration] was read; false - refused (signed out
     * meanwhile). Throws when sealing or writing fails.
     */
    fun save(context: Context, session: CareLinkSession, expectedGeneration: Long = generation): Boolean {
        val sealed = cipher.seal(session.toJson().toString().toByteArray(Charsets.UTF_8))
        synchronized(lock) {
            if (generation != expectedGeneration) return false
            writeAtomically(file(context), sealed)
        }
        return true
    }

    /**
     * A new sign-in: [save], and if it fails, a new Keystore key and one more try. A key the Keystore cannot use any
     * more (lock screen changed on some phones, Keystore reset) would otherwise refuse every sign-in; the old file does
     * not open with it either, so nothing readable is lost.
     */
    fun saveNew(context: Context, session: CareLinkSession, expectedGeneration: Long): Boolean = try {
        save(context, session, expectedGeneration)
    } catch (first: Throwable) {
        try {
            cipher.resetKey()
        } catch (t: Throwable) {
            first.addSuppressed(t)
            throw first
        }
        save(context, session, expectedGeneration)
    }

    /** Deletes the session (and a left temporary file); later writes of an older [generation] are refused. Fast. */
    fun clear(context: Context) {
        val f = file(context)
        synchronized(lock) {
            generation++
            f.delete()
            File(f.parentFile, f.name + ".tmp").delete()
        }
    }

    internal fun writeAtomically(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IllegalStateException("no folder for ${target.name}")
        if (!parent.isDirectory && !parent.mkdirs()) throw IllegalStateException("folder not created")
        val tmp = File(parent, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IllegalStateException("${target.name} not replaced")
            }
        }
    }
}
