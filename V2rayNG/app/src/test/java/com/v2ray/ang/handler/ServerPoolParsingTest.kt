package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FILTERNET: guards the pool parser against the two bugs that actually shipped.
 *
 * The logic is mirrored here rather than called directly because
 * [ServerPoolManager] pulls in android.util.Base64 and MMKV, neither of which
 * exists on the JVM. The regex and the de-duplication rule are the parts that
 * broke in production, and they are pure text handling.
 */
class ServerPoolParsingTest {

    private val hostPort = Regex("""@(?:\[([^\[\]]+)]|([^/?#@:]+)):(\d{1,5})""")

    private fun endpointOf(link: String): Pair<String, Int>? {
        val m = hostPort.find(link) ?: return null
        val host = m.groupValues[1].ifEmpty { m.groupValues[2] }
        val port = m.groupValues[3].toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
    }

    /** The shipping rule: de-duplicate on the whole link. */
    private fun parse(lines: List<String>): List<String> {
        val seen = HashSet<String>()
        return lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") && it.contains("://") }
            .filter { endpointOf(it) != null }
            .filter { seen.add(it) }
    }

    private fun vless(ip: String, sni: String) =
        "vless://b3311f0d-72e4-4f9c-9a3d-5c6b7a8f9e0d@$ip:443" +
            "?encryption=none&security=tls&sni=$sni&type=ws&host=$sni&path=%252F#name-$sni"

    @Test
    fun `extracts host and port from a real vless link`() {
        val e = endpointOf(vless("188.114.99.227", "a.pages.dev"))
        assertEquals("188.114.99.227" to 443, e)
    }

    @Test
    fun `handles ipv6 literals`() {
        val e = endpointOf("vless://uid@[2606:4700::1111]:8443?type=ws#x")
        assertEquals("2606:4700::1111" to 8443, e)
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val out = parse(listOf("# note", "", "   ", "// other", vless("1.2.3.4", "a")))
        assertEquals(1, out.size)
    }

    @Test
    fun `configs sharing one endpoint are all kept`() {
        // The shipped bug: de-duplicating on host:port collapsed a whole CDN
        // pool down to a handful of links.
        val links = (1..10).map { vless("188.114.99.227", "host$it.pages.dev") }
        assertEquals(10, parse(links).size)
    }

    @Test
    fun `identical links are still de-duplicated`() {
        val one = vless("1.2.3.4", "a")
        assertEquals(1, parse(listOf(one, one, one)).size)
    }

    @Test
    fun `garbage lines never crash the parser`() {
        val out = parse(listOf("vless://", "://@:", "@:99999", "not a link", "vless://u@h:70000"))
        assertTrue(out.isEmpty())
    }
}
