package com.photoflowmobile.app.data.cloud

/**
 * Classifies IPv4 host strings as private (RFC-1918 / loopback) or public.
 *
 * Used by CloudApiClient to refuse plain-HTTP connections to non-LAN hosts:
 * the API key travels in the HTTP header and must not cross the public internet
 * unencrypted. FTP is out of scope (inherently plaintext; LAN-only by design).
 *
 * Android's network_security_config cannot express subnet ranges, so this
 * check is enforced client-side rather than at the OS layer.
 *
 * Hostnames (non-dotted-decimal strings) always return false (treated as public)
 * because classifying them would require a DNS lookup that introduces side-effects.
 */
object PrivateAddressChecker {

    /**
     * Returns `true` if [host] is a dotted-decimal IPv4 address in a private or
     * loopback range: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, or 127.0.0.0/8.
     * Returns `false` for hostnames, IPv6 strings, or public addresses.
     */
    fun isPrivate(host: String): Boolean {
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return when (parts[0]) {
            10   -> true                     // 10.0.0.0/8
            127  -> true                     // 127.0.0.0/8 loopback
            172  -> parts[1] in 16..31       // 172.16.0.0/12
            192  -> parts[1] == 168          // 192.168.0.0/16
            else -> false
        }
    }
}
