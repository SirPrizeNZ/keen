package com.keenzero.app.diagnostics

data class NavigationEvent(
    val t: Long,
    val type: String,
    val url: String? = null,
    val detail: String? = null,
    val isMainFrame: Boolean? = null,
)
