package com.nuvio.tv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fork guard: a release build logs nothing (proguard-fork-logging.pro, see ReleaseLogStripRulesTest),
 * but debug installs and the Report-a-problem export (#172) still carry our own warn and error lines,
 * so in FORK code every one of them that carries a link, a host or exception text passes it through
 * [LogRedact]. Upstream files are not scanned: they are left as upstream wrote them.
 */
class LogRedactGuardTest {

    @Test
    fun `fork warn and error lines never log a raw link or exception text`() {
        val src = File("src")
        assertTrue("run from the app module", File(src, "main/java").isDirectory)
        val roots = src.listFiles().orEmpty()
            .filter { !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
            .map { File(it, "java") }
            .filter { it.isDirectory }
        val forkFiles = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" && isForkOwned(it) }.toList()
        }
        assertTrue("fork files found", forkFiles.size > 20)
        val problems = forkFiles.flatMap { file ->
            LogLeakScanner.scan(file.readText()).map { "${file.relativeTo(src)}:$it" }
        }
        assertTrue(
            "Route these through LogRedact.url, LogRedact.text or LogRedact.error\n" + problems.joinToString("\n"),
            problems.isEmpty()
        )
    }

    /** Sports & Live code: the sportslive packages and the SportsLive / Sports* files beside upstream ones. */
    private fun isForkOwned(file: File): Boolean {
        val path = file.invariantSeparatorsPath
        return "/sportslive/" in path || Regex("""^(.*SportsLive.*|.*Sports[A-Z]\w*)\.kt$""").matches(file.name)
    }

    @Test
    fun `the scanner flags raw links and exception text`() {
        val source = """
            fun a() {
                Log.w(TAG, "failed url=${'$'}streamUrl")
                Log.e(TAG, "failed ${'$'}{e.message}")
                Log.w(TAG, "failed", e)
                Log.w(TAG, message)
                Log.e(TAG, "failed " + result.manifestUrl)
                android.util.Log.w(TAG, "x ${'$'}{addon.baseUrl.take(40)}")
                Log.w(TAG, "plain (with) a paren, and ${'$'}{"nested, string"}", e)
                e.printStackTrace()
            }
        """.trimIndent()
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 8, 9), LogLeakScanner.scan(source).map { it.substringBefore(':').toInt() })
    }

    @Test
    fun `the scanner accepts redacted lines and harmless values`() {
        val source = """
            fun a() {
                Log.w(TAG, "failed url=${'$'}{LogRedact.url(streamUrl)} code=${'$'}code")
                Log.e(TAG, "failed ${'$'}{LogRedact.text(e.message)} ${'$'}{e.javaClass.simpleName}", LogRedact.error(e))
                Log.w(TAG, "loaded ${'$'}{urls.size} links from ${'$'}{hosts.isEmpty()}")
                Log.w(TAG, LogRedact.text(line))
                Log.e(TAG, "Failed " +
                    "to load ${'$'}id")
                Log.d(TAG, "debug lines are stripped from release url=${'$'}url")
                LogRedact.error(e)?.printStackTrace()
            }
        """.trimIndent()
        assertEquals(emptyList<String>(), LogLeakScanner.scan(source))
    }
}

/** Finds Log.w / Log.e / Log.wtf calls (and bare printStackTrace) that could put a link or a token in logcat. */
internal object LogLeakScanner {
    private val CALL = Regex("""\bLog\.(w|e|wtf)\(""")
    private val PRINT_STACK = Regex("""\.printStackTrace\(\)""")
    private val REDACT_CALL = Regex("""LogRedact\.\w+\(""")
    private val CHAIN = Regex("""[A-Za-z_]\w*(?:(?:\??\.|::)[A-Za-z_]\w*(?:\(\))?)*""")
    private val SENSITIVE = Regex(
        """(?:(?<![A-Za-z])(?:url|uri|link|host|domain|endpoint|manifest)|Url|URL|Uri|URI|Link|Host|Domain|Endpoint|Manifest)(?:s|name|names)?(?![a-z])|""" +
            """\.message\b|[lL]ocalizedMessage|diagnosticSummary|stackTraceToString|stackTraceString|causeChain|""" +
            """(?:^|\.)\w*(?:[eE]rror|[eE]xception|[mM]essage|[rR]aw)$"""
    )
    private val SAFE_SUFFIX = Regex(
        """\.(size|length|isBlank\(\)|isEmpty\(\)|isNotBlank\(\)|isNotEmpty\(\)|isNullOrBlank\(\)|isNullOrEmpty\(\)|count\(\)|hashCode\(\))$"""
    )
    private val THROWABLE_NAME = Regex(
        """e|t|ex|err|error|throwable|exception|cause|it|failure|lastException|retryError|refreshError|clearError|uploadError"""
    )

    /** Problems as "line: reason". */
    fun scan(source: String): List<String> {
        val problems = mutableListOf<String>()
        fun lineOf(index: Int) = source.substring(0, index).count { it == '\n' } + 1
        for (match in CALL.findAll(source)) {
            if (isCommentedOut(source, match.range.first)) continue
            val lexer = Lexer(source, match.range.last + 1)
            val args = lexer.arguments()
            val reasons = mutableListOf<String>()
            lexer.interpolations.forEach { expr -> leak(expr, interpolation = true)?.let { reasons += "\"\$$it\"" } }
            args.forEach { arg -> leak(arg.code, interpolation = false)?.let { reasons += it } }
            if (args.size >= 3 && !args.last().raw.contains("LogRedact.")) reasons += "throwable ${args.last().raw.trim()}"
            if (args.size == 2) {
                val message = args[1].raw.trim()
                if (!message.startsWith("\"") && !message.contains("LogRedact.")) reasons += "message $message"
            }
            if (reasons.isNotEmpty()) problems += "${lineOf(match.range.first)}: ${reasons.distinct().joinToString(", ")}"
        }
        for (match in PRINT_STACK.findAll(source)) {
            val lineStart = source.lastIndexOf('\n', match.range.first) + 1
            if (source.substring(lineStart, match.range.first).contains("LogRedact.error(")) continue
            if (isCommentedOut(source, match.range.first)) continue
            problems += "${lineOf(match.range.first)}: printStackTrace"
        }
        return problems.sortedBy { it.substringBefore(':').toInt() }
    }

    private fun isCommentedOut(source: String, index: Int): Boolean {
        val lineStart = source.lastIndexOf('\n', index) + 1
        return source.substring(lineStart, index).trimStart().startsWith("//")
    }

    /** The first chain in [code] (string literals already removed) that could hold a link or exception text. */
    private fun leak(code: String, interpolation: Boolean): String? {
        val visible = withoutRedactCalls(code)
        if (interpolation && THROWABLE_NAME.matches(visible.trim())) return visible.trim()
        return CHAIN.findAll(visible).map { it.value }.firstOrNull { chain ->
            SENSITIVE.containsMatchIn(chain) && !SAFE_SUFFIX.containsMatchIn(chain)
        }
    }

    private fun withoutRedactCalls(code: String): String {
        var out = code
        while (true) {
            val call = REDACT_CALL.find(out) ?: return out
            var depth = 1
            var i = call.range.last + 1
            while (i < out.length && depth > 0) {
                when (out[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            out = out.substring(0, call.range.first) + out.substring(i)
        }
    }

    class Argument(val raw: String, val code: String)

    /** A small Kotlin lexer: splits a call's arguments and collects string-template expressions. */
    private class Lexer(private val s: String, private var i: Int) {
        val interpolations = mutableListOf<String>()

        fun arguments(): List<Argument> {
            val args = mutableListOf<Argument>()
            code(')', args)
            return args.filter { it.raw.isNotBlank() }
        }

        /** Reads code up to the closer [close] at depth 0; returns it with string literals blanked. */
        private fun code(close: Char, args: MutableList<Argument>?): String {
            val all = StringBuilder()
            val raw = StringBuilder()
            val code = StringBuilder()
            var depth = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> {
                        val start = i
                        string()
                        raw.append(s, start, i)
                        code.append("\"\"")
                        all.append("\"\"")
                    }
                    c == '\'' -> {
                        val start = i
                        i++
                        while (i < s.length && s[i] != '\'') i += if (s[i] == '\\') 2 else 1
                        i++
                        raw.append(s, start, i)
                    }
                    s.startsWith("//", i) -> while (i < s.length && s[i] != '\n') i++
                    s.startsWith("/*", i) -> {
                        val end = s.indexOf("*/", i + 2)
                        i = if (end < 0) s.length else end + 2
                    }
                    depth == 0 && c == close -> {
                        i++
                        args?.add(Argument(raw.toString(), code.toString()))
                        return all.toString()
                    }
                    depth == 0 && c == ',' && args != null -> {
                        args.add(Argument(raw.toString(), code.toString()))
                        raw.clear()
                        code.clear()
                        all.append(c)
                        i++
                    }
                    else -> {
                        if (c == '(' || c == '[' || c == '{') depth++
                        if (c == ')' || c == ']' || c == '}') depth--
                        raw.append(c)
                        code.append(c)
                        all.append(c)
                        i++
                    }
                }
            }
            args?.add(Argument(raw.toString(), code.toString()))
            return all.toString()
        }

        private fun string() {
            val rawString = s.startsWith("\"\"\"", i)
            i += if (rawString) 3 else 1
            while (i < s.length) {
                val c = s[i]
                when {
                    !rawString && c == '\\' -> i += 2
                    rawString && s.startsWith("\"\"\"", i) -> {
                        i += 3
                        while (i < s.length && s[i] == '"') i++
                        return
                    }
                    !rawString && c == '"' -> {
                        i++
                        return
                    }
                    c == '$' && i + 1 < s.length && s[i + 1] == '{' -> {
                        i += 2
                        interpolations += code('}', null)
                    }
                    c == '$' && i + 1 < s.length && (s[i + 1].isLetter() || s[i + 1] == '_') -> {
                        val start = ++i
                        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                        interpolations += s.substring(start, i)
                    }
                    else -> i++
                }
            }
        }
    }
}
