package com.keenzero.app.diagnostics

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes machine-readable Phase 0 evidence under app-private storage.
 * Path is returned so the operator can pull it with adb.
 */
object EvidenceExporter {

    /**
     * Writes evidence to app-private internal storage (always pullable via
     * `adb shell run-as … cat …` on debuggable builds) and mirrors to
     * external app files when the platform provides that directory.
     */
    fun export(context: Context, payload: JSONObject): File {
        val text = payload.toString(2)
        val stamp = utcStamp()
        val primaryDir = File(context.filesDir, "evidence/phase-0")
        if (!primaryDir.exists() && !primaryDir.mkdirs()) {
            throw IllegalStateException("Cannot create evidence directory: ${primaryDir.absolutePath}")
        }
        val primary = File(primaryDir, "phase0-$stamp.json")
        primary.writeText(text)
        File(primaryDir, "latest.json").writeText(text)

        val externalRoot = context.getExternalFilesDir(null)
        if (externalRoot != null) {
            try {
                val externalDir = File(externalRoot, "evidence/phase-0")
                if (externalDir.exists() || externalDir.mkdirs()) {
                    File(externalDir, primary.name).writeText(text)
                    File(externalDir, "latest.json").writeText(text)
                }
            } catch (_: Throwable) {
                // Internal write is authoritative; external mirror is best-effort.
            }
        }
        return primary
    }

    private fun utcStamp(): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }
}
