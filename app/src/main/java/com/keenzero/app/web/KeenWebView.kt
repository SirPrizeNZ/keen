package com.keenzero.app.web

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.webkit.WebView
import com.keenzero.app.input.ImeSubmitSignal
import com.keenzero.app.input.RemoteInputRouter

/**
 * Routes remote keys before focused page controls can consume them.
 * Also wraps the IME [InputConnection] so Android TV keyboard Search/Go/Done
 * is observed even when no normal [KeyEvent] is delivered.
 */
class KeenWebView(
    context: Context,
    private val inputRouter: RemoteInputRouter,
) : WebView(context) {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        inputRouter.handle(this, event) || super.dispatchKeyEvent(event)

    override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        // Prefer Search/Go on TV soft keyboards when the page did not specify.
        if (outAttrs != null) {
            val mask = EditorInfo.IME_MASK_ACTION
            val action = outAttrs.imeOptions and mask
            if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
                outAttrs.imeOptions =
                    (outAttrs.imeOptions and mask.inv()) or EditorInfo.IME_ACTION_SEARCH
            }
        }
        // InputConnection callbacks run off the UI thread — never call WebView there.
        return KeenImeInputConnection(base, true) { signal ->
            post { inputRouter.onImeSubmit(this@KeenWebView, signal) }
        }
    }
}

/**
 * Observes IME editor actions and Enter-like key/text commits without swallowing
 * the original WebView handling. Always returns the original base [handled] value.
 */
private class KeenImeInputConnection(
    target: InputConnection,
    mutable: Boolean,
    private val onSubmit: (ImeSubmitSignal) -> Unit,
) : InputConnectionWrapper(target, mutable) {

    private var lastSubmitAt = 0L

    private fun maybeSubmit(source: String, action: Int?, baseHandled: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSubmitAt < DEBOUNCE_MS) return
        lastSubmitAt = now
        onSubmit(
            ImeSubmitSignal(
                source = source,
                action = action,
                baseHandled = baseHandled,
                timestampMs = now,
            ),
        )
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        // 1) original InputConnection  2) record handled  3) notify Keen  4) return original
        val handled = super.performEditorAction(editorAction)
        when (editorAction) {
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_NEXT,
            -> maybeSubmit("performEditorAction", editorAction, handled)
        }
        return handled
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        val handled = super.sendKeyEvent(event)
        if (event != null &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            maybeSubmit("sendKeyEvent", event.keyCode, handled)
        }
        return handled
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val handled = super.commitText(text, newCursorPosition)
        if (text != null && text.contains('\n')) {
            maybeSubmit("commitText", null, handled)
        }
        return handled
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val handled = super.setComposingText(text, newCursorPosition)
        if (text != null && text.contains('\n')) {
            maybeSubmit("setComposingText", null, handled)
        }
        return handled
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
    }
}
