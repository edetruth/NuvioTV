package com.nuvio.tv.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fork: a release build writes no log lines (addon links carry tokens). Checks the R8 rules in
 * proguard-fork-logging.pro strip every android.util.Log level and printStackTrace, and that the
 * release build type uses that file.
 */
class ReleaseLogStripRulesTest {

    private val rules = File("proguard-fork-logging.pro").readText()
        .lines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    private fun block(header: String): String {
        val start = rules.indexOf("$header {")
        assertTrue("missing $header", start >= 0)
        return rules.substring(start, rules.indexOf('}', start))
    }

    @Test
    fun `every android Log level is stripped and isLoggable reports false`() {
        val log = block("-assumenosideeffects class android.util.Log")
        listOf("v", "d", "i", "w", "e", "wtf", "println").forEach { level ->
            assertTrue("Log.$level not stripped", log.contains("public static int $level(...) return 0;"))
        }
        assertTrue(log, log.contains("public static boolean isLoggable(java.lang.String, int) return false;"))
    }

    @Test
    fun `printStackTrace to the console is stripped for every throwable`() {
        assertTrue(block("-assumenosideeffects class java.lang.Throwable").contains("public void printStackTrace();"))
        assertTrue(block("-assumenosideeffects class * extends java.lang.Throwable").contains("public void printStackTrace();"))
    }

    @Test
    fun `the release build type uses the strip rules`() {
        val gradle = File("build.gradle.kts").readText()
        val release = gradle.substring(gradle.indexOf("        release {"), gradle.indexOf("create(\"benchmark\")"))
        assertTrue(release.contains("isMinifyEnabled = true"))
        assertTrue(release.contains("proguardFiles(\"proguard-fork-logging.pro\")"))
    }
}
