package com.example.basicbrowser.scope.engine

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Utility for parsing and matching IPv4 and IPv6 addresses against CIDR notation ranges.
 */
object CidrUtils {

    /**
     * Checks if [pattern] looks like a CIDR block (e.g. contains "/" followed by a prefix length).
     */
    fun isCidr(pattern: String): Boolean {
        val trimmed = pattern.trim()
        val slashIdx = trimmed.indexOf('/')
        if (slashIdx <= 0 || slashIdx == trimmed.length - 1) return false
        val prefixStr = trimmed.substring(slashIdx + 1)
        val prefix = prefixStr.toIntOrNull() ?: return false
        val ipPart = trimmed.substring(0, slashIdx)
        return try {
            val addr = InetAddress.getByName(ipPart)
            if (addr is Inet4Address) prefix in 0..32 else prefix in 0..128
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if [candidateIp] (e.g. "192.168.1.15" or "2001:db8::1") is within the CIDR range [cidrPattern].
     */
    fun matchesCidr(candidateIp: String, cidrPattern: String): Boolean {
        val trimmedCidr = cidrPattern.trim()
        val slashIdx = trimmedCidr.indexOf('/')
        if (slashIdx <= 0) return false

        val ipStr = trimmedCidr.substring(0, slashIdx).trim()
        val prefixLen = trimmedCidr.substring(slashIdx + 1).trim().toIntOrNull() ?: return false

        val networkAddr: InetAddress
        val candidateAddr: InetAddress
        try {
            networkAddr = InetAddress.getByName(ipStr)
            candidateAddr = InetAddress.getByName(candidateIp.trim())
        } catch (_: Exception) {
            return false
        }

        // Must be the same IP family
        if (networkAddr is Inet4Address && candidateAddr is Inet4Address) {
            if (prefixLen !in 0..32) return false
            return matchesIpv4Cidr(candidateAddr, networkAddr, prefixLen)
        } else if (networkAddr is Inet6Address && candidateAddr is Inet6Address) {
            if (prefixLen !in 0..128) return false
            return matchesIpv6Cidr(candidateAddr, networkAddr, prefixLen)
        }
        return false
    }

    private fun matchesIpv4Cidr(candidate: Inet4Address, network: Inet4Address, prefixLen: Int): Boolean {
        if (prefixLen == 0) return true
        val candInt = bytesToInt(candidate.address)
        val netInt = bytesToInt(network.address)
        val mask = (-1 shl (32 - prefixLen))
        return (candInt and mask) == (netInt and mask)
    }

    private fun matchesIpv6Cidr(candidate: Inet6Address, network: Inet6Address, prefixLen: Int): Boolean {
        if (prefixLen == 0) return true
        val candBytes = candidate.address
        val netBytes = network.address

        var fullBytes = prefixLen / 8
        val remainingBits = prefixLen % 8

        for (i in 0 until fullBytes) {
            if (candBytes[i] != netBytes[i]) return false
        }

        if (remainingBits > 0) {
            val mask = (0xFF shl (8 - remainingBits)) and 0xFF
            val candByte = candBytes[fullBytes].toInt() and 0xFF
            val netByte = netBytes[fullBytes].toInt() and 0xFF
            if ((candByte and mask) != (netByte and mask)) return false
        }

        return true
    }

    private fun bytesToInt(bytes: ByteArray): Int {
        return ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
    }

    /**
     * Checks if [host] is a valid IP address literal (IPv4 or IPv6).
     */
    fun isIpAddress(host: String): Boolean {
        val cleanHost = host.removePrefix("[").removeSuffix("]").trim()
        return try {
            val addr = InetAddress.getByName(cleanHost)
            // Ensure getByName didn't do DNS resolution for a non-literal domain
            // by checking if string matches canonical or hex/digits
            cleanHost.contains(":") || cleanHost.split(".").size == 4 && cleanHost.all { it.isDigit() || it == '.' }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if two IP literals are equivalent (e.g. "127.0.0.1" == "127.0.0.1" or "::1" == "0:0:0:0:0:0:0:1").
     */
    fun ipEquals(ip1: String, ip2: String): Boolean {
        val c1 = ip1.removePrefix("[").removeSuffix("]").trim()
        val c2 = ip2.removePrefix("[").removeSuffix("]").trim()
        return try {
            val a1 = InetAddress.getByName(c1)
            val a2 = InetAddress.getByName(c2)
            a1 == a2
        } catch (_: Exception) {
            false
        }
    }
}

