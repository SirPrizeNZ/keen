package com.keenzero.app.input

/**
 * Carries the result of an Android TV IME editor action so Keen can decide whether
 * a single fallback submit is allowed (only when the original InputConnection
 * returned false and the page showed no handling).
 */
data class ImeSubmitSignal(
    val source: String,
    val action: Int?,
    val baseHandled: Boolean,
    val timestampMs: Long,
)
