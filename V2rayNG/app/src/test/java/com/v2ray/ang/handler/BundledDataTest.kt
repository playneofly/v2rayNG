package com.v2ray.ang.handler

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.tencent.mmkv.MMKV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream

/**
 * FILTERNET: the data baked into the APK, read back.
 *
 * Unlike the other handler tests this one drives the real [BundledData] instead
 * of a mirror of it, because the thing worth testing is precisely the handover:
 * tools/filternet-bundle.py writes these files and this object parses them. A
 * mirrored parser would agree with itself and prove nothing.
 *
 * The last test closes the loop against the assets actually committed to the
 * repository, so a change to the bundler that emits something the app cannot
 * read fails here rather than on someone's phone.
 */
class BundledDataTest {

    /* ─────────────────────────── fixtures ─────────────────────────── */

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
    }.toByteArray()

    /** A context whose assets are exactly [assets]; anything else is absent. */
    private fun contextWith(assets: Map<String, ByteArray>): Context {
        val manager = mock<AssetManager>()
        whenever(manager.open(any())).thenAnswer { invocation ->
            val name = invocation.getArgument<String>(0)
            assets[name]?.let { ByteArrayInputStream(it) } ?: throw IOException("no asset $name")
        }
        return mock<Context>().also { whenever(it.assets).thenReturn(manager) }
    }

    private val manifestJson = """
        {
         "builtAt": "2026-10-01T16:45:53Z",
         "cloudflare": {"addresses": 1524736, "cidrs": 15, "file": "cloudflare-ipv4.txt"},
         "internal": {"configs": 3, "encrypted": true, "file": "internal.bin"},
         "ircf": {"addresses": 5, "file": "ircf-seed.json", "operators": 3},
         "pool": {"configs": 1893, "file": "filternet-pool.txt.gz"},
         "schema": 1
        }
    """.trimIndent()

    private val poolText = """
        # FILTERNET - free server pool
        vless://aaaa@104.16.0.1:443?security=tls#one
        trojan://bbbb@104.17.0.2:2053?security=tls#two
    """.trimIndent()

    private val ircfJson =
        """{"operators":{"cname":["104.26.8.84"],"mci":["162.159.1.94","104.17.42.10"],""" +
            """"mtn":["104.19.255.51","172.67.186.42"]}}"""

    private val cidrText = "104.16.0.0/13\n172.64.0.0/13\n162.158.0.0/15\n"

    private fun fullContext(): Context = contextWith(
        mapOf(
            "fn-manifest.json" to manifestJson.toByteArray(),
            "filternet-pool.txt.gz" to gzip(poolText),
            "ircf-seed.json" to ircfJson.toByteArray(),
            "cloudflare-ipv4.txt" to cidrText.toByteArray(),
        )
    )

    @Before
    fun forgetPreviousBuild() {
        BundledData.resetCachesForTest()
    }

    /** LogUtil reaches android.util.Log, which the JVM does not provide. */
    private fun withLog(block: () -> Unit) {
        mockStatic(Log::class.java).use { block() }
    }

    /* ─────────────────────────── manifest ─────────────────────────── */

    @Test
    fun `manifest reports the counts the build wrote`() {
        withLog {
            val m = BundledData.manifest(fullContext())
            assertEquals("2026-10-01T16:45:53Z", m.builtAt)
            assertEquals(1893, m.poolConfigs)
            assertEquals(3, m.internalConfigs)
            assertEquals(5, m.ircfAddresses)
            assertEquals(3, m.ircfOperators)
            assertEquals(15, m.cloudflareCidrs)
            // An Int would overflow a much larger space; the field has to be Long.
            assertEquals(1_524_736L, m.cloudflareAddresses)
        }
    }

    @Test
    fun `an absent manifest reads as zero rather than throwing`() {
        withLog {
            val m = BundledData.manifest(contextWith(emptyMap()))
            assertEquals(0, m.poolConfigs)
            assertEquals(0L, m.cloudflareAddresses)
            assertTrue(m.isEmpty)
        }
    }

    @Test
    fun `a truncated manifest reads as zero rather than throwing`() {
        withLog {
            val ctx = contextWith(mapOf("fn-manifest.json" to """{"pool":{"conf""".toByteArray()))
            assertEquals(0, BundledData.manifest(ctx).poolConfigs)
        }
    }

    /* ─────────────────────────── the pool ─────────────────────────── */

    @Test
    fun `the pool asset is gunzipped back to exactly what went in`() {
        withLog {
            assertEquals(poolText, BundledData.poolText(fullContext()))
        }
    }

    @Test
    fun `a corrupt pool asset is null rather than a crash`() {
        withLog {
            val ctx = contextWith(mapOf("filternet-pool.txt.gz" to byteArrayOf(1, 2, 3, 4, 5)))
            assertNull(BundledData.poolText(ctx))
        }
    }

    @Test
    fun `an absent pool asset is null rather than a crash`() {
        withLog {
            assertNull(BundledData.poolText(contextWith(emptyMap())))
        }
    }

    @Test
    fun `the pool is decompressed once and then remembered`() {
        withLog {
            val ctx = fullContext()
            assertNotNull(BundledData.poolText(ctx))
            assertNotNull(BundledData.poolText(ctx))
            // The real asset is 600 KB inflated; the hunt asks for it per wave.
            verify(ctx.assets, times(1)).open("filternet-pool.txt.gz")
        }
    }

    /* ─────────────────────────── IRCF ─────────────────────────── */

    @Test
    fun `ircf addresses are grouped by operator`() {
        withLog {
            val seed = BundledData.ircfSeed(fullContext())
            assertEquals(setOf("cname", "mci", "mtn"), seed.keys)
            assertEquals(listOf("104.19.255.51", "172.67.186.42"), seed["mtn"])
        }
    }

    @Test
    fun `the phone's own operator is offered first`() {
        withLog {
            // An Irancell SIM resolves to mtn, and those addresses have to lead:
            // the scanner probes in order and gives up on a timer.
            val ordered = BundledData.ircfSeedFor(fullContext(), listOf("mtn", "cname"))
            assertEquals(listOf("104.19.255.51", "172.67.186.42", "104.26.8.84"), ordered.take(3))
        }
    }

    @Test
    fun `other operators are still offered, after the matching ones`() {
        withLog {
            // An edge that answers for MCI may well answer here too, and one
            // extra handshake is cheaper than one fewer candidate.
            val ordered = BundledData.ircfSeedFor(fullContext(), listOf("mtn"))
            assertEquals(5, ordered.size)
            assertTrue(ordered.containsAll(listOf("162.159.1.94", "104.17.42.10")))
        }
    }

    @Test
    fun `an address is never handed out twice`() {
        withLog {
            val ctx = contextWith(
                mapOf(
                    "ircf-seed.json" to
                        """{"operators":{"a":["1.1.1.1"],"b":["1.1.1.1"]}}""".toByteArray()
                )
            )
            assertEquals(listOf("1.1.1.1"), BundledData.ircfSeedFor(ctx, listOf("a")))
        }
    }

    @Test
    fun `a malformed ircf asset is empty rather than a crash`() {
        withLog {
            val ctx = contextWith(mapOf("ircf-seed.json" to "not json at all".toByteArray()))
            assertEquals(emptyMap<String, List<String>>(), BundledData.ircfSeed(ctx))
        }
    }

    /* ─────────────────────────── Cloudflare ─────────────────────────── */

    @Test
    fun `cloudflare cidrs are read in order`() {
        withLog {
            assertEquals(
                listOf("104.16.0.0/13", "172.64.0.0/13", "162.158.0.0/15"),
                BundledData.cloudflareCidrs(fullContext()),
            )
        }
    }

    @Test
    fun `blank lines and ipv6 in the cidr asset are ignored`() {
        withLog {
            val ctx = contextWith(
                mapOf("cloudflare-ipv4.txt" to "104.16.0.0/13\n\n  \n2400:cb00::/32\n".toByteArray())
            )
            // The IPv6 line has no dots and must not reach the IPv4 sweep.
            assertEquals(listOf("104.16.0.0/13"), BundledData.cloudflareCidrs(ctx))
        }
    }

    /* ──────────────── the assets actually in the repository ──────────────── */

    @Test
    fun `the committed assets are the ones the app can read`() {
        withLog {
            val dir = File("src/main/assets")
            assumeTrue("not running from the module directory", dir.isDirectory)

            val names = listOf(
                "fn-manifest.json", "filternet-pool.txt.gz", "ircf-seed.json", "cloudflare-ipv4.txt",
            )
            assumeTrue(
                "tools/filternet-bundle.py has not been run in this checkout",
                names.all { File(dir, it).isFile },
            )

            val ctx = contextWith(names.associateWith { File(dir, it).readBytes() })
            val m = BundledData.manifest(ctx)

            val links = BundledData.poolText(ctx).orEmpty().lineSequence()
                .map { it.trim() }
                .count { it.contains("://") && !it.startsWith("#") }
            assertEquals("the manifest disagrees with the pool it describes", m.poolConfigs, links)

            val seed = BundledData.ircfSeed(ctx)
            assertEquals(m.ircfOperators, seed.size)
            assertEquals(m.ircfAddresses, seed.values.sumOf { it.size })

            val cidrs = BundledData.cloudflareCidrs(ctx)
            assertEquals(m.cloudflareCidrs, cidrs.size)
            val space = cidrs.sumOf { 1L shl (32 - it.substringAfter('/').toInt()) }
            assertEquals(m.cloudflareAddresses, space)
            // Cloudflare's published IPv4 allocation. When it changes the number
            // in the app changes with it - that is what the manifest is for.
            assertEquals(1_524_736L, space)
        }
    }

    companion object {
        private val settings: MMKV = mock()

        /**
         * LogUtil asks MmkvManager for the log level the first time it is used.
         * Same dance as SubscriptionIndexTest: hand the object its handle once.
         */
        @BeforeClass
        @JvmStatic
        fun initializeHandles() {
            mockStatic(MMKV::class.java).use {
                it.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }
                    .thenReturn(settings)
                MmkvManager.decodeSettingsString("test-initialize")
            }
        }
    }
}
