// SPDX-License-Identifier: GPL-3.0-or-later
// Based on iPump-mini (https://github.com/sgitaize/iPump-mini, commit 29aa932),
// apps/android/app/src/main/java/de/sgitaize/openminipump/AppConfig.kt (object KeyImport), by Simon Gutjahr.
// Modified for JugglucoNGmod: no bundled database (no assets branch); the hex is kept in noBackupFilesDir instead of
// the preferences; failures are an enum for the screen instead of localised exceptions; reading a file chosen by the
// user; only the CRC and the database type are shown or logged; the page by a chain (a pinned commit, then main).
package tk.glucodata.mod.pumpbt

import android.content.Context
import tk.glucodata.mod.pumpbt.core.KeyDatabase
import tk.glucodata.mod.pumpbt.core.KeyDatabaseError
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * JugglucoNGmod, pump over Bluetooth: the SAKE key database "Mobile Application ↔ Insulin Pump". Not in the repository
 * and not in the app: "Start pairing" without one downloads OpenMinimed's public page - the pinned commit first, its
 * main branch when that gives none ([downloadChain]; series brief, decision 11) - or the user picks a file; the CRC and
 * the database type are checked and the hex kept in `noBackupFilesDir/mod-pumpbt/keydb.hex`. Shown and logged: only
 * the CRC (the first 4 bytes of the database are its checksum, not key material) and the type.
 */
internal object PumpBtKey {

    /** OpenMinimed's page of the dumped key databases at a pinned commit (the trial passed with its database). */
    const val PINNED_URL =
        "https://raw.githubusercontent.com/OpenMinimed/Documentation/37ac341f900a0f2afa8e331fefc32ec7741fcf95/key_databases.md"

    /** The same page on the main branch: asked only when the pinned one gives no database. */
    const val MAIN_URL = "https://raw.githubusercontent.com/OpenMinimed/Documentation/main/key_databases.md"

    /** The order of [downloadChain]. */
    val CHAIN = listOf(PINNED_URL, MAIN_URL)

    /**
     * The CRC of the database the trial passed with on a 740G (27.11). Another CRC is accepted (the database checks
     * itself); it only leaves a journal line ([crcLine]).
     */
    const val KNOWN_CRC = "F75995E7"

    enum class Failure { NO_NETWORK, HTTP, NOT_FOUND, CRC, WRONG_TYPE, FILE_NOT_READ }

    sealed class Result {
        class Ok(val hex: String, val db: KeyDatabase) : Result() {
            val crc: String get() = crcOf(hex)
        }

        data class Failed(val failure: Failure, val code: Int = 0) : Result()
    }

    /**
     * Finds the "Mobile Application ↔ Insulin Pump" database in a text (plain hex or a page like key_databases.md).
     * When there is none, tells why: a block that is a database of another type, a block whose CRC does not match,
     * or nothing that looks like a database.
     */
    fun parse(text: String): Result {
        val hex = KeyDatabase.findHex(text, KeyDatabase::isMobilePump)
        if (hex != null) {
            return try {
                Result.Ok(hex, KeyDatabase.fromHex(hex))
            } catch (_: KeyDatabaseError) {
                Result.Failed(Failure.NOT_FOUND)
            }
        }
        var wrongType = false
        var crc = false
        val candidates = Regex("<code>(.*?)</code>", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.groupValues[1] }.toMutableList()
        candidates.add(text)
        for (candidate in candidates) {
            val clean = candidate.filterNot { it.isWhitespace() }
            if (clean.isEmpty() || !clean.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) continue
            try {
                KeyDatabase.fromHex(clean)
                wrongType = true
            } catch (_: KeyDatabaseError.CrcMismatch) {
                crc = true
            } catch (_: KeyDatabaseError) {
            }
        }
        return Result.Failed(
            when {
                wrongType -> Failure.WRONG_TYPE
                crc -> Failure.CRC
                else -> Failure.NOT_FOUND
            },
        )
    }

    /** The CRC of a database in hex: its first 4 bytes, upper case. */
    fun crcOf(hex: String): String = hex.filterNot { it.isWhitespace() }.take(8).uppercase()

    fun file(context: Context): File = File(File(context.applicationContext.noBackupFilesDir, "mod-pumpbt"), "keydb.hex")

    /** The saved database, checked again; null when there is none or it does not pass any more. */
    fun load(context: Context): Result.Ok? = try {
        val f = file(context)
        if (!f.exists()) null else (parse(f.readText()) as? Result.Ok)
    } catch (_: Throwable) {
        null
    }

    fun save(context: Context, ok: Result.Ok) {
        val f = file(context)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(ok.hex)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw IOException("key file not saved")
        }
    }

    /** One address of the chain and what it gave. */
    data class Attempt(val url: String, val result: Result)

    /**
     * The key database by the chain: [PINNED_URL], then [MAIN_URL] only when the pinned page gave none. Every attempt
     * in order; the last one is the outcome (a failure - with main's reason). Blocking: call off the main thread.
     */
    fun downloadChain(fetch: (String) -> Result = ::download): List<Attempt> {
        val attempts = mutableListOf<Attempt>()
        for (url in CHAIN) {
            val result = try {
                fetch(url)
            } catch (_: Throwable) {
                Result.Failed(Failure.NO_NETWORK)
            }
            attempts += Attempt(url, result)
            if (result is Result.Ok) break
        }
        return attempts
    }

    /** The journal line of a database whose CRC is not [KNOWN_CRC] (no key material); null - the known one. */
    fun crcLine(crc: String): String? =
        if (crc.equals(KNOWN_CRC, ignoreCase = true)) null else "key database CRC $crc, tested with $KNOWN_CRC"

    /** Downloads OpenMinimed's page and finds the database. Blocking: call off the main thread. */
    fun download(url: String = MAIN_URL): Result {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (_: IOException) {
            return Result.Failed(Failure.NO_NETWORK)
        }
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        return try {
            val code = conn.responseCode
            if (code !in 200..299) return Result.Failed(Failure.HTTP, code)
            parse(conn.inputStream.bufferedReader().readText())
        } catch (_: IOException) {
            Result.Failed(Failure.NO_NETWORK)
        } finally {
            conn.disconnect()
        }
    }
}
