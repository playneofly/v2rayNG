package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FILTERNET: guards the two pieces of the scanner that are pure logic, plus a
 * regression test for the bug that twice stopped the app connecting.
 *
 * The maths and the string surgery are mirrored here because the real object
 * pulls in MMKV and android.util, neither of which exists on the JVM.
 */
class CleanIpScannerTest {

    /* ───────────────── address generation stays inside the ranges ───────────────── */

    private val ranges = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
        "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
        "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    )

    private data class Block(val base: Long, val size: Long)

    private val blocks = ranges.map { cidr ->
        val (ip, bits) = cidr.split("/")
        val p = ip.split(".").map { it.toLong() }
        Block((p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3], 1L shl (32 - bits.toInt()))
    }

    private fun toLong(ip: String): Long {
        val p = ip.split(".").map { it.toLong() }
        return (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3]
    }

    private fun addressAt(index: Long): String {
        var pick = index
        for (b in blocks) {
            if (pick < b.size) {
                val v = b.base + pick
                return "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
            }
            pick -= b.size
        }
        error("index out of space")
    }

    private fun inAnyRange(ip: String): Boolean {
        val v = toLong(ip)
        return blocks.any { v >= it.base && v < it.base + it.size }
    }

    @Test
    fun `address space is roughly one and a half million`() {
        val total = blocks.sumOf { it.size }
        assertTrue("expected > 1.4M, was $total", total > 1_400_000)
        assertTrue("expected < 2M, was $total", total < 2_000_000)
    }

    @Test
    fun `generated addresses always fall inside a published range`() {
        val total = blocks.sumOf { it.size }
        // Walk the whole space at a prime-ish stride: boundaries included.
        var i = 0L
        var checked = 0
        while (i < total) {
            assertTrue("out of range at $i", inAnyRange(addressAt(i)))
            i += 7919
            checked++
        }
        assertTrue(checked > 100)
        // first and last address of every block
        var offset = 0L
        for (b in blocks) {
            assertTrue(inAnyRange(addressAt(offset)))
            assertTrue(inAnyRange(addressAt(offset + b.size - 1)))
            offset += b.size
        }
    }

    @Test
    fun `addresses outside the ranges are rejected`() {
        listOf("8.8.8.8", "1.1.1.1", "192.168.0.1", "10.0.0.1").forEach {
            assertFalse("$it should not be in range", inAnyRange(it))
        }
    }

    /* ───────────────── rewriting a config onto a new address ───────────────── */

    private val hostPort = Regex("""@(?:\[([^\[\]]+)]|([^/?#@:]+)):(\d{1,5})""")

    private fun rewrite(raw: String, host: String, port: Int, ip: String, newPort: Int): String {
        val replaced = raw.replaceFirst("@$host:$port", "@$ip:$newPort")
        return if (replaced.contains("#")) {
            replaced.substringBeforeLast("#") + "#SCAN-$ip"
        } else {
            "$replaced#SCAN-$ip"
        }
    }

    private val sample =
        "vless://b3311f0d-72e4-4f9c-9a3d-5c6b7a8f9e0d@188.114.99.227:443" +
            "?encryption=none&security=tls&sni=worker.pages.dev&fp=randomized" +
            "&alpn=http%2F1.1&type=ws&host=worker.pages.dev&path=%252F#filternet.server-001"

    @Test
    fun `rewritten config keeps every credential and only swaps the address`() {
        val out = rewrite(sample, "188.114.99.227", 443, "104.21.5.9", 2053)

        assertTrue("new address missing", out.contains("@104.21.5.9:2053"))
        assertFalse("old address still present", out.contains("188.114.99.227"))
        // the parts that make the config work must survive untouched
        assertTrue(out.contains("b3311f0d-72e4-4f9c-9a3d-5c6b7a8f9e0d"))
        assertTrue(out.contains("sni=worker.pages.dev"))
        assertTrue(out.contains("host=worker.pages.dev"))
        assertTrue(out.contains("path=%252F"))
        assertTrue(out.contains("type=ws"))
        assertTrue(out.startsWith("vless://"))
    }

    @Test
    fun `rewritten config is still parseable back to an endpoint`() {
        val out = rewrite(sample, "188.114.99.227", 443, "104.21.5.9", 2053)
        val m = hostPort.find(out)
        assertNotNull(m)
        val host = m!!.groupValues[1].ifEmpty { m.groupValues[2] }
        assertEquals("104.21.5.9", host)
        assertEquals(2053, m.groupValues[3].toInt())
    }
}
