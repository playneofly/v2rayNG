package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import org.json.JSONObject
import java.util.zip.GZIPInputStream

/**
 * FILTERNET: everything the app needs, already inside the APK.
 *
 * ── why this exists ───────────────────────────────────────────────────────
 * The app used to learn where its servers were by downloading a file from
 * GitHub. That is a bootstrap problem dressed as a feature: the one phone that
 * genuinely needs a free server is the phone that cannot reach GitHub, because
 * reaching GitHub is precisely what it has no working proxy for. During a
 * shutdown the app would sit on a spinner and then announce it had found
 * nothing, while a perfectly good list of servers existed - on a website it
 * could not open.
 *
 * So the data is baked in at build time instead. Every APK carries, as of the
 * moment it was compiled:
 *
 *   filternet-pool.txt.gz   the public share links, gzipped (~23x smaller)
 *   ircf-seed.json          clean addresses per operator, resolved on the runner
 *   cloudflare-ipv4.txt     the published CIDRs - 1,524,736 addresses in 231 bytes
 *   internal.bin            the private Worker configs, AES-GCM sealed
 *   fn-manifest.json        what all of the above contains
 *
 * The network is then an optimisation rather than a dependency: tapping
 * refresh can pull something newer, and failing to do so costs nothing.
 *
 * ── on the Cloudflare count ───────────────────────────────────────────────
 * Storing 1.5 million addresses as text would add ~20 MB to the APK. Storing
 * the fifteen CIDRs they come from takes 231 bytes and loses nothing, because
 * the set is generated from them anyway. [CleanIpScanner] expands them on the
 * phone.
 *
 * Everything is read lazily and cached in memory; a failure anywhere returns
 * empty rather than throwing, so a damaged asset degrades the app instead of
 * killing it.
 */
object BundledData {

    private const val ASSET_POOL = "filternet-pool.txt.gz"
    private const val ASSET_IRCF = "ircf-seed.json"
    private const val ASSET_CF = "cloudflare-ipv4.txt"
    private const val ASSET_MANIFEST = "fn-manifest.json"

    /** What the build put in the APK. Counts are shown in the UI. */
    data class Manifest(
        val builtAt: String = "",
        val poolConfigs: Int = 0,
        val internalConfigs: Int = 0,
        val ircfAddresses: Int = 0,
        val ircfOperators: Int = 0,
        val cloudflareCidrs: Int = 0,
        val cloudflareAddresses: Long = 0L,
    ) {
        val isEmpty: Boolean get() = poolConfigs == 0 && ircfAddresses == 0
    }

    @Volatile
    private var manifestCache: Manifest? = null

    @Volatile
    private var poolCache: String? = null

    @Volatile
    private var ircfCache: Map<String, List<String>>? = null

    @Volatile
    private var cfCache: List<String>? = null

    /* ─────────────────────────── manifest ─────────────────────────── */

    fun manifest(context: Context): Manifest =
        manifestCache ?: readManifest(context).also { manifestCache = it }

    private fun readManifest(context: Context): Manifest = runCatching {
        val root = JSONObject(readAsset(context, ASSET_MANIFEST)?.decodeToString() ?: return@runCatching Manifest())
        Manifest(
            builtAt = root.optString("builtAt"),
            poolConfigs = root.optJSONObject("pool")?.optInt("configs") ?: 0,
            internalConfigs = root.optJSONObject("internal")?.optInt("configs") ?: 0,
            ircfAddresses = root.optJSONObject("ircf")?.optInt("addresses") ?: 0,
            ircfOperators = root.optJSONObject("ircf")?.optInt("operators") ?: 0,
            cloudflareCidrs = root.optJSONObject("cloudflare")?.optInt("cidrs") ?: 0,
            cloudflareAddresses = root.optJSONObject("cloudflare")?.optLong("addresses") ?: 0L,
        )
    }.getOrDefault(Manifest())

    /* ─────────────────────────── the pool ─────────────────────────── */

    /**
     * The share links compiled into this APK, as the raw text of the pool file.
     *
     * Decompressed once and kept, because [ServerPoolManager] may ask for it
     * repeatedly while hunting through a large pool.
     */
    fun poolText(context: Context): String? {
        poolCache?.let { return it }
        val gz = readAsset(context, ASSET_POOL) ?: return null
        val text = runCatching {
            GZIPInputStream(gz.inputStream()).use { it.readBytes().decodeToString() }
        }.getOrNull() ?: return null
        poolCache = text
        LogUtil.i(AppConfig.TAG, "BundledData: pool asset holds ${text.length} chars")
        return text
    }

    /* ─────────────────────────── IRCF ─────────────────────────── */

    /**
     * Clean addresses per operator code, as resolved when the APK was built.
     *
     * These are a floor, not a ceiling: [IrcfSource] still prefers a live
     * lookup and only falls back here. The point is that the fallback is never
     * empty, which it always was on a first run with no connectivity.
     */
    fun ircfSeed(context: Context): Map<String, List<String>> {
        ircfCache?.let { return it }
        val parsed = runCatching {
            val raw = readAsset(context, ASSET_IRCF)?.decodeToString() ?: return@runCatching emptyMap()
            val ops = JSONObject(raw).optJSONObject("operators") ?: return@runCatching emptyMap()
            buildMap<String, List<String>> {
                for (code in ops.keys()) {
                    val arr = ops.optJSONArray(code) ?: continue
                    val list = (0 until arr.length()).mapNotNull { i ->
                        arr.optString(i).takeIf { DohResolver.isIpv4(it) }
                    }
                    if (list.isNotEmpty()) put(code, list)
                }
            }
        }.getOrDefault(emptyMap())
        ircfCache = parsed
        return parsed
    }

    /** Bundled addresses for [codes], best-matching operator first. */
    fun ircfSeedFor(context: Context, codes: List<String>): List<String> {
        val seed = ircfSeed(context)
        if (seed.isEmpty()) return emptyList()
        val ordered = codes.flatMap { seed[it].orEmpty() }
        // Anything left over is still a Cloudflare edge worth probing.
        val rest = seed.filterKeys { it !in codes }.values.flatten()
        return (ordered + rest).distinct()
    }

    /* ─────────────────────────── Cloudflare ─────────────────────────── */

    /** The published IPv4 CIDRs shipped with this build. */
    fun cloudflareCidrs(context: Context): List<String> {
        cfCache?.let { return it }
        val list = runCatching {
            readAsset(context, ASSET_CF)?.decodeToString().orEmpty()
                .lineSequence()
                .map { it.trim() }
                .filter { it.contains('/') && it.count { c -> c == '.' } == 3 }
                .toList()
        }.getOrDefault(emptyList())
        cfCache = list
        return list
    }

    /* ─────────────────────────── plumbing ─────────────────────────── */

    private fun readAsset(context: Context, name: String): ByteArray? = runCatching {
        context.assets.open(name).use { it.readBytes() }
    }.onFailure {
        LogUtil.w(AppConfig.TAG, "BundledData: asset $name missing")
    }.getOrNull()

    /**
     * Forgets the in-memory copies.
     *
     * The app reads each asset once per process and has no reason ever to let
     * go of it. This exists so a test can hand the same object two different
     * builds' worth of assets without the first read sticking.
     */
    internal fun resetCachesForTest() {
        manifestCache = null
        poolCache = null
        ircfCache = null
        cfCache = null
    }
}
