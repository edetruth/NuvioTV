package com.nuvio.tv.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nuvio Live fork version names (see app/build.gradle.kts, full flavor) must order the way releases ship. */
class LiveVersionOrderingTest {
    @Test
    fun `next fork build on the same upstream version is newer`() {
        assertTrue(VersionUtils.isRemoteNewer("v1.2.0-beta.1.live.2", "1.2.0-beta.1.live.1"))
        assertTrue(VersionUtils.isRemoteNewer("v1.2.0-live.10", "1.2.0-live.9"))
    }

    @Test
    fun `later upstream beta is newer even past nine`() {
        assertTrue(VersionUtils.isRemoteNewer("v1.2.0-beta.10.live.5", "1.2.0-beta.9.live.4"))
    }

    @Test
    fun `stable upstream base is newer than its betas`() {
        assertTrue(VersionUtils.isRemoteNewer("v1.2.0-live.6", "1.2.0-beta.10.live.5"))
        assertTrue(VersionUtils.isRemoteNewer("v1.2.1-beta.1.live.7", "1.2.0-live.6"))
    }

    @Test
    fun `same build is not newer`() {
        assertFalse(VersionUtils.isRemoteNewer("v1.2.0-beta.1.live.1", "1.2.0-beta.1.live.1"))
    }
}
