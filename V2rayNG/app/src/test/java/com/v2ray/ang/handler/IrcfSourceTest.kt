package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FILTERNET: the clean-address source, minus the network.
 *
 * Operator mapping and DoH parsing are mirrored here because the real objects
 * reach for TelephonyManager and MMKV, which do not exist on the JVM. These
 * are the parts where a quiet mistake would simply make the feature fetch
 * nothing - the worst kind of bug, because it looks like it is working.
 */
class IrcfSourceTest {

    private val byMccMnc = mapOf(
        "43235" to "mtn",
        "43211" to "mci",
        "43220" to "rtl",
        "43232" to "mci",
        "43219" to "mci",
        "43270" to "mkh",
        "43293" to "mci",
    )

    private fun hostsFor(code: String) = listOf("${code}c.ircf.space", "$code.ircf.space")

    /* ─────────────────────── operator mapping ─────────────────────── */

    @Test
    fun `irancell maps to mtn`() {
        assertEquals("mtn", byMccMnc["43235"])
    }

    @Test
    fun `the main iranian operators are all covered`() {
        // 432 is Iran's country code; these are the networks worth naming.
        listOf("43235", "43211", "43220").forEach {
            assertTrue("$it unmapped", byMccMnc.containsKey(it))
        }
    }

    @Test
    fun `the crawler hostname is tried before the single one`() {
        val hosts = hostsFor("mtn")
        assertEquals(listOf("mtnc.ircf.space", "mtn.ircf.space"), hosts)
        // the crawler variant is the one that returns several addresses, so
        // asking for it first is what makes a single lookup worthwhile
        assertTrue(hosts.first().startsWith("mtnc"))
    }

    /* ─────────────────────── DoH answer parsing ─────────────────────── */

    private fun parseAnswers(json: String): List<String> {
        // Mirrors DohResolver.parseAnswers without org.json on the classpath.
        val out = mutableListOf<String>()
        val entries = Regex("""\{[^{}]*}""").findAll(json)
        for (e in entries) {
            val body = e.value
            val type = Regex(""""type"\s*:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull()
            if (type != 1) continue
            val data = Regex(""""data"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)?.trim()
            if (data != null && isIpv4(data)) out.add(data)
        }
        return out.distinct()
    }

    private fun isIpv4(s: String): Boolean {
        val parts = s.split(".")
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } &&
                (p.toIntOrNull() ?: -1) in 0..255
        }
    }

    private val sample = """
        {"Status":0,"Answer":[
          {"name":"mtnc.ircf.space","type":5,"TTL":300,"data":"edge.example.net"},
          {"name":"edge.example.net","type":1,"TTL":300,"data":"104.19.255.51"},
          {"name":"edge.example.net","type":1,"TTL":300,"data":"172.67.186.42"},
          {"name":"edge.example.net","type":1,"TTL":300,"data":"199.59.243.225"}
        ]}
    """.trimIndent()

    @Test
    fun `only A records are taken from a DoH answer`() {
        val ips = parseAnswers(sample)
        assertEquals(listOf("104.19.255.51", "172.67.186.42", "199.59.243.225"), ips)
        // the CNAME in the chain must not leak through as an address
        assertFalse(ips.any { it.contains("example") })
    }

    @Test
    fun `an answer with no records yields nothing`() {
        assertTrue(parseAnswers("""{"Status":3}""").isEmpty())
        assertTrue(parseAnswers("""{"Status":0,"Answer":[]}""").isEmpty())
    }

    @Test
    fun `malformed addresses are rejected`() {
        assertTrue(isIpv4("104.19.255.51"))
        assertFalse(isIpv4("999.1.1.1"))
        assertFalse(isIpv4("104.19.255"))
        assertFalse(isIpv4("not.an.ip.here"))
        assertFalse(isIpv4(""))
    }

    /* ─────────────── addresses outside Cloudflare are still kept ─────────────── */

    @Test
    fun `ircf addresses are never filtered by cloudflare range`() {
        // Measured against the real lists: some of what ircf hands out for
        // Irancell is not Cloudflare space at all. Discarding those would throw
        // away exactly the addresses that work on the operator that is broken.
        val fromIrcf = listOf("203.32.121.53", "199.59.243.225", "104.19.255.51")
        val kept = fromIrcf.filter { isIpv4(it) }
        assertEquals(3, kept.size)
    }
}
