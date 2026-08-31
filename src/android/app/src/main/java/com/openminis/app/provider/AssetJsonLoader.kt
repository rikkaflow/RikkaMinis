package com.openminis.app.provider

import android.content.Context
import java.io.File

/**
 * [T-cost-catalog] Asset-file reader for the bundled model price JSON.
 *
 * Isolated so the rest of the catalog is pure-JVM-testable: production injects
 * the Android [Context] via [init], while sandbox tests never touch this class.
 *
 * Robustness: if the asset is missing/truncated the catalog degrades to an
 * empty table — cost shows "unknown" (honest) rather than crashing the app.
 */
object AssetJsonLoader {

    private var appContext: Context? = null

    /** Must be called once at app startup (before any cost lookup). Idempotent. */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    /**
     * Read an asset as UTF-8 text. Returns empty string on any failure so
     * callers degrade gracefully (catalog → empty, cost → unknown).
     */
    fun read(assetPath: String): String {
        val ctx = appContext ?: return ""
        return try {
            ctx.assets.open(assetPath).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }

    /** Exposed for tests that want a real file (non-Android fallback). */
    fun readFile(file: File): String = try {
        file.readText()
    } catch (_: Exception) {
        ""
    }
}
