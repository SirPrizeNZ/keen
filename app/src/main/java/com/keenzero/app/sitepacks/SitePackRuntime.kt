package com.keenzero.app.sitepacks

import android.content.Context
import com.keenzero.app.BuildConfig
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Verifies and activates the declarative bundled compatibility pack off-main-thread. */
object SitePackRuntime {
    private const val KEY_ID = "keen-zero-pack-1"
    private const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZAYWZcVAApfOOffuOsmCnYyapHP1oPyoPH9Q/AnLGDpTnED+84TyztruKT5uLfaVTvuCYPOIH6OBDlk1HFnESw=="
    private const val MAX_PACKS = 100
    private const val MAX_CSS_RULES = 100
    private const val MAX_SELECTOR_LENGTH = 200

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "keen-site-pack-init").apply { isDaemon = true }
    }
    private val state = AtomicReference(State())

    fun initialize(context: Context) {
        val app = context.applicationContext
        executor.execute {
            state.set(
                try {
                    val envelope = app.assets.open("site-packs/bundle-envelope.json")
                        .bufferedReader().use { JSONObject(it.readText()) }
                    verifyAndParse(envelope)
                } catch (t: Throwable) {
                    State(error = "${t.javaClass.simpleName}: ${t.message}")
                },
            )
        }
    }

    fun repairsFor(url: String?): List<Repair> {
        val targetOrigin = origin(url) ?: return emptyList()
        return state.get().packs.filter { targetOrigin in it.origins }.map { pack ->
            Repair(pack.id, pack.selectors)
        }
    }

    fun snapshot(): Snapshot = state.get().let {
        Snapshot(
            ready = it.ready,
            verified = it.verified,
            bundleVersion = it.bundleVersion,
            activePackIds = it.packs.map(Pack::id),
            expires = it.expires,
            error = it.error,
        )
    }

    private fun verifyAndParse(envelope: JSONObject): State {
        require(envelope.getInt("schema") == 1) { "unsupported envelope schema" }
        require(envelope.getString("keyId") == KEY_ID) { "unknown signing key" }
        val payload = Base64.getDecoder().decode(envelope.getString("payload"))
        require(SitePackVerifier.verify(PUBLIC_KEY, payload, envelope.getString("signature"))) {
            "signature rejected"
        }
        val root = JSONObject(payload.toString(Charsets.UTF_8))
        require(root.getString("runtimeMin") <= BuildConfig.VERSION_NAME.substringBefore('-')) {
            "runtime too old"
        }
        val expires = Instant.parse(root.getString("expires"))
        require(expires.isAfter(Instant.now())) { "bundle expired" }
        val packArray = root.getJSONArray("packs")
        require(packArray.length() <= MAX_PACKS) { "too many packs" }
        val packs = buildList {
            for (i in 0 until packArray.length()) {
                val item = packArray.getJSONObject(i)
                val origins = item.getJSONArray("origins")
                val css = item.getJSONArray("css")
                require(css.length() <= MAX_CSS_RULES) { "too many CSS rules" }
                add(
                    Pack(
                        id = item.getString("id").also { require(it.matches(Regex("[a-z0-9-]{1,64}"))) },
                        origins = buildSet {
                            for (j in 0 until origins.length()) add(requireOrigin(origins.getString(j)))
                        },
                        selectors = buildList {
                            for (j in 0 until css.length()) {
                                val rule = css.getJSONObject(j)
                                require(rule.getString("action") == "hide") { "unsupported CSS action" }
                                add(requireSelector(rule.getString("selector")))
                            }
                        },
                    ),
                )
            }
        }
        return State(
            ready = true,
            verified = true,
            bundleVersion = root.getInt("bundleVersion"),
            expires = expires.toString(),
            packs = packs,
        )
    }

    private fun requireOrigin(value: String): String {
        val parsed = URI(value)
        require(parsed.scheme == "https" && parsed.host != null && parsed.path.isNullOrEmpty()) {
            "invalid origin"
        }
        return origin(value)!!
    }

    private fun requireSelector(value: String): String {
        require(value.length in 1..MAX_SELECTOR_LENGTH) { "invalid selector length" }
        require(value.none { it == '{' || it == '}' || it == ';' }) { "unsafe selector" }
        return value
    }

    private fun origin(url: String?): String? = try {
        val uri = url?.let(::URI) ?: return null
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        "$scheme://$host${if (uri.port != -1) ":${uri.port}" else ""}"
    } catch (_: Exception) {
        null
    }

    data class Repair(val packId: String, val selectors: List<String>)
    data class Snapshot(
        val ready: Boolean,
        val verified: Boolean,
        val bundleVersion: Int?,
        val activePackIds: List<String>,
        val expires: String?,
        val error: String?,
    )
    private data class Pack(val id: String, val origins: Set<String>, val selectors: List<String>)
    private data class State(
        val ready: Boolean = false,
        val verified: Boolean = false,
        val bundleVersion: Int? = null,
        val expires: String? = null,
        val packs: List<Pack> = emptyList(),
        val error: String? = null,
    )
}
