package com.keenzero.app.web

import android.content.Context
import android.view.KeyEvent
import android.webkit.WebView
import com.keenzero.app.input.RemoteInputRouter

/** Routes remote keys before focused page controls can consume them. */
class KeenWebView(
    context: Context,
    private val inputRouter: RemoteInputRouter,
) : WebView(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        inputRouter.handle(this, event) || super.dispatchKeyEvent(event)
}
