package com.keenzero.app.diagnostics

import android.util.Log
import org.json.JSONObject

/**
 * Test-only structured signals for the lab harness.
 *
 * Emulator `run-as cat` is flaky under load. Harnesses should prefer:
 *   adb logcat -s KeenLab:I
 * and parse lines containing `KZ_LAB_JSON:`.
 *
 * Never used as a product UX surface.
 */
object LabSignal {
    const val TAG = "KeenLab"
    private const val PREFIX = "KZ_LAB_JSON:"

    fun emit(type: String, fields: Map<String, Any?> = emptyMap()) {
        val o = JSONObject()
        o.put("type", type)
        o.put("t", System.currentTimeMillis())
        for ((k, v) in fields) {
            when (v) {
                null -> o.put(k, JSONObject.NULL)
                is Boolean -> o.put(k, v)
                is Int -> o.put(k, v)
                is Long -> o.put(k, v)
                is Float -> o.put(k, v.toDouble())
                is Double -> o.put(k, v)
                is Number -> o.put(k, v.toDouble())
                else -> o.put(k, v.toString())
            }
        }
        // Single-line JSON so logcat parsers stay simple.
        Log.i(TAG, PREFIX + o.toString())
    }

    fun emitJson(type: String, obj: JSONObject) {
        val copy = JSONObject(obj.toString())
        copy.put("type", type)
        if (!copy.has("t")) copy.put("t", System.currentTimeMillis())
        Log.i(TAG, PREFIX + copy.toString())
    }
}
