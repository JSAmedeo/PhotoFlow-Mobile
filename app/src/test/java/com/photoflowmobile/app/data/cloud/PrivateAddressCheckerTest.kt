package com.photoflowmobile.app.data.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateAddressCheckerTest {

    // 10.0.0.0/8
    @Test fun `10_0_0_0 is private`()    { assertTrue(PrivateAddressChecker.isPrivate("10.0.0.0")) }
    @Test fun `10_255_255_255 is private`() { assertTrue(PrivateAddressChecker.isPrivate("10.255.255.255")) }

    // 172.16.0.0/12 — boundary at 172.16 and 172.31
    @Test fun `172_16_0_0 is private`()  { assertTrue(PrivateAddressChecker.isPrivate("172.16.0.0")) }
    @Test fun `172_31_255_255 is private`() { assertTrue(PrivateAddressChecker.isPrivate("172.31.255.255")) }
    @Test fun `172_15_255_255 is public`() { assertFalse(PrivateAddressChecker.isPrivate("172.15.255.255")) }
    @Test fun `172_32_0_0 is public`()   { assertFalse(PrivateAddressChecker.isPrivate("172.32.0.0")) }

    // 192.168.0.0/16
    @Test fun `192_168_0_0 is private`()  { assertTrue(PrivateAddressChecker.isPrivate("192.168.0.0")) }
    @Test fun `192_168_1_1 is private`()  { assertTrue(PrivateAddressChecker.isPrivate("192.168.1.1")) }
    @Test fun `192_169_0_0 is public`()   { assertFalse(PrivateAddressChecker.isPrivate("192.169.0.0")) }

    // 127.0.0.0/8 loopback
    @Test fun `127_0_0_1 is private`()   { assertTrue(PrivateAddressChecker.isPrivate("127.0.0.1")) }
    @Test fun `127_255_255_255 is private`() { assertTrue(PrivateAddressChecker.isPrivate("127.255.255.255")) }

    // Public addresses
    @Test fun `8_8_8_8 is public`()      { assertFalse(PrivateAddressChecker.isPrivate("8.8.8.8")) }
    @Test fun `1_1_1_1 is public`()      { assertFalse(PrivateAddressChecker.isPrivate("1.1.1.1")) }
    @Test fun `203_0_113_1 is public`()  { assertFalse(PrivateAddressChecker.isPrivate("203.0.113.1")) }

    // Hostnames (no DNS lookup — always treated as public)
    @Test fun `hostname is public`()     { assertFalse(PrivateAddressChecker.isPrivate("api.example.com")) }
    @Test fun `localhost string is public`() { assertFalse(PrivateAddressChecker.isPrivate("localhost")) }

    // Malformed
    @Test fun `empty string is public`() { assertFalse(PrivateAddressChecker.isPrivate("")) }
    @Test fun `too few octets is public`() { assertFalse(PrivateAddressChecker.isPrivate("192.168.1")) }
    @Test fun `out of range octet is public`() { assertFalse(PrivateAddressChecker.isPrivate("256.0.0.1")) }
}
