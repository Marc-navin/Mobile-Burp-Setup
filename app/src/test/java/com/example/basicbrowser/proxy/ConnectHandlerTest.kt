package com.example.basicbrowser.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ConnectHandlerTest {

    @Test
    fun testParseAuthority_standardHostAndPort() {
        val result = ConnectHandler.parseAuthority("example.com:443")
        assertNotNull(result)
        assertEquals("example.com", result!!.first)
        assertEquals(443, result.second)
    }

    @Test
    fun testParseAuthority_customPort() {
        val result = ConnectHandler.parseAuthority("localhost:8443")
        assertNotNull(result)
        assertEquals("localhost", result!!.first)
        assertEquals(8443, result.second)
    }

    @Test
    fun testParseAuthority_defaultPortWhenOmitted() {
        val result = ConnectHandler.parseAuthority("example.com")
        assertNotNull(result)
        assertEquals("example.com", result!!.first)
        assertEquals(443, result.second)
    }

    @Test
    fun testParseAuthority_ipv4WithPort() {
        val result = ConnectHandler.parseAuthority("127.0.0.1:8080")
        assertNotNull(result)
        assertEquals("127.0.0.1", result!!.first)
        assertEquals(8080, result.second)
    }

    @Test
    fun testParseAuthority_ipv6BracketedWithPort() {
        val result = ConnectHandler.parseAuthority("[2001:db8::1]:443")
        assertNotNull(result)
        assertEquals("2001:db8::1", result!!.first)
        assertEquals(443, result.second)
    }

    @Test
    fun testParseAuthority_ipv6BracketedDefaultPort() {
        val result = ConnectHandler.parseAuthority("[::1]")
        assertNotNull(result)
        assertEquals("::1", result!!.first)
        assertEquals(443, result.second)
    }

    @Test
    fun testParseAuthority_invalidInputs() {
        assertNull(ConnectHandler.parseAuthority(""))
        assertNull(ConnectHandler.parseAuthority("   "))
        assertNull(ConnectHandler.parseAuthority("example.com:notanumber"))
        assertNull(ConnectHandler.parseAuthority("example.com:70000"))
        assertNull(ConnectHandler.parseAuthority("example.com:0"))
        assertNull(ConnectHandler.parseAuthority("example.com:-1"))
        assertNull(ConnectHandler.parseAuthority("[unclosed-ipv6:443"))
    }

    @Test
    fun testHandle_successfulConnectionOnFirstAddress() {
        val clientSocket = Socket()
        val clientIn = BufferedInputStream(ByteArrayInputStream(ByteArray(0)))
        val clientOut = ByteArrayOutputStream()

        val targetAddr = InetAddress.getByName("127.0.0.1")
        val handler = ConnectHandler(
            clientSocket = clientSocket,
            clientIn = clientIn,
            clientOut = clientOut,
            dnsResolver = { arrayOf(targetAddr) },
            socketConnector = { addr, port, timeout ->
                assertEquals(targetAddr, addr)
                assertEquals(443, port)
                Socket()
            },
            caReadinessChecker = { true }
        )

        handler.handle("example.com:443", "HTTP/1.1")
        val output = clientOut.toString(Charsets.US_ASCII.name())
        assertTrue("Expected 200 Connection Established but got: $output", output.contains("200 Connection Established"))
    }

    @Test
    fun testHandle_fallbackToSecondAddressWhenFirstFails() {
        val clientSocket = Socket()
        val clientIn = BufferedInputStream(ByteArrayInputStream(ByteArray(0)))
        val clientOut = ByteArrayOutputStream()

        val addr1 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0.toByte(), 2.toByte(), 1.toByte()))
        val addr2 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0.toByte(), 2.toByte(), 2.toByte()))

        val attemptedAddrs = mutableListOf<InetAddress>()
        val handler = ConnectHandler(
            clientSocket = clientSocket,
            clientIn = clientIn,
            clientOut = clientOut,
            dnsResolver = { arrayOf(addr1, addr2) },
            socketConnector = { addr, port, timeout ->
                attemptedAddrs.add(addr)
                if (addr == addr1) {
                    throw SocketTimeoutException("Timed out connecting to addr1")
                } else {
                    Socket()
                }
            },
            caReadinessChecker = { true }
        )

        handler.handle("example.com:443", "HTTP/1.1")
        assertEquals(listOf(addr1, addr2), attemptedAddrs)
        val output = clientOut.toString(Charsets.US_ASCII.name())
        assertTrue("Expected 200 Connection Established but got: $output", output.contains("200 Connection Established"))
    }

    @Test
    fun testHandle_caNotReady_returns502WithoutSending200() {
        val clientSocket = Socket()
        val clientIn = BufferedInputStream(ByteArrayInputStream(ByteArray(0)))
        val clientOut = ByteArrayOutputStream()

        val targetAddr = InetAddress.getByName("127.0.0.1")
        val handler = ConnectHandler(
            clientSocket = clientSocket,
            clientIn = clientIn,
            clientOut = clientOut,
            dnsResolver = { arrayOf(targetAddr) },
            socketConnector = { _, _, _ -> Socket() },
            caReadinessChecker = { false }
        )

        handler.handle("example.com:443", "HTTP/1.1")
        val output = clientOut.toString(Charsets.US_ASCII.name())
        assertTrue("Expected 502 Bad Gateway but got: $output", output.contains("502 Bad Gateway"))
        assertFalse("Must NOT send 200 Connection Established when CA is not ready", output.contains("200 Connection Established"))
    }

    @Test
    fun testHandle_allAddressesFail_returnsError() {
        val clientSocket = Socket()
        val clientIn = BufferedInputStream(ByteArrayInputStream(ByteArray(0)))
        val clientOut = ByteArrayOutputStream()

        val addr1 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0.toByte(), 2.toByte(), 1.toByte()))
        val addr2 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0.toByte(), 2.toByte(), 2.toByte()))

        val handler = ConnectHandler(
            clientSocket = clientSocket,
            clientIn = clientIn,
            clientOut = clientOut,
            dnsResolver = { arrayOf(addr1, addr2) },
            socketConnector = { addr, _, _ ->
                throw ConnectException("Connection refused for $addr")
            }
        )

        handler.handle("example.com:443", "HTTP/1.1")
        val output = clientOut.toString(Charsets.US_ASCII.name())
        assertTrue("Expected 502 Bad Gateway but got: $output", output.contains("502 Bad Gateway"))
    }

    @Test
    fun testHandle_dnsResolutionFails_returns502() {
        val clientSocket = Socket()
        val clientIn = BufferedInputStream(ByteArrayInputStream(ByteArray(0)))
        val clientOut = ByteArrayOutputStream()

        val handler = ConnectHandler(
            clientSocket = clientSocket,
            clientIn = clientIn,
            clientOut = clientOut,
            dnsResolver = { throw UnknownHostException("Host unresolvable") },
            socketConnector = { _, _, _ -> Socket() }
        )

        handler.handle("unresolvable.invalid:443", "HTTP/1.1")
        val output = clientOut.toString(Charsets.US_ASCII.name())
        assertTrue("Expected 502 Bad Gateway but got: $output", output.contains("502 Bad Gateway"))
    }
}
