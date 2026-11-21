package com.nuvio.tv.ui.screens.addon

import org.junit.Assert.assertEquals
import org.junit.Test

class AddonInstallErrorTextTest {

    @Test
    fun `a network in the way reads as blocked, never the raw TLS text`() {
        assertEquals(AddonInstallFailure.BLOCKED, addonInstallFailureOf("Unable to parse TLS packet header", null))
        assertEquals(AddonInstallFailure.BLOCKED, addonInstallFailureOf("javax.net.ssl.SSLHandshakeException - Chain validation failed", null))
        assertEquals(AddonInstallFailure.BLOCKED, addonInstallFailureOf("Trust anchor for certification path not found", null))
    }

    @Test
    fun `each common failure gets its own plain cause`() {
        assertEquals(AddonInstallFailure.NO_HOST, addonInstallFailureOf("Unable to resolve host \"example.invalid\" - No address associated with hostname", null))
        assertEquals(AddonInstallFailure.TIMEOUT, addonInstallFailureOf("timeout", null))
        assertEquals(AddonInstallFailure.TIMEOUT, addonInstallFailureOf("failed to connect to example.invalid (port 443) after 10000ms", null))
        assertEquals(AddonInstallFailure.REFUSED, addonInstallFailureOf("Failed to connect to example.invalid", null))
        assertEquals(AddonInstallFailure.CLEARTEXT, addonInstallFailureOf("CLEARTEXT communication to example.invalid not permitted by network security policy", null))
        assertEquals(AddonInstallFailure.NOT_FOUND, addonInstallFailureOf("Not Found", 404))
        assertEquals(AddonInstallFailure.SERVER, addonInstallFailureOf("Bad Gateway", 502))
    }

    @Test
    fun `anything else is the general failure`() {
        assertEquals(AddonInstallFailure.OTHER, addonInstallFailureOf("Expected BEGIN_OBJECT but was STRING", null))
        assertEquals(AddonInstallFailure.OTHER, addonInstallFailureOf("Forbidden", 403))
        assertEquals(AddonInstallFailure.OTHER, addonInstallFailureOf("", null))
    }
}
