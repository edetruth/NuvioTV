package com.nuvio.tv.core.sportslive

/**
 * Small raised letters some addons put in titles ("Arsenal ᴸᴵⱽᴱ", "ʟɪᴠᴇ", "ᴴᴰ"): superscript and
 * small-capital letters read as ordinary ones, and a run that only spells a mark (live, new, hd...)
 * dropped, so it is not taken as part of a team name.
 *
 * Adapted from SportsCatalog (plainSmall / withoutSmallMarks) in homelessbrian/NuvioPlusIPTV
 * (GPL-3.0, as this project).
 */
internal object SportsLiveSmallLetters {
    private const val SMALL = "ᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻᶦᶫᶰᶸᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘʀꜱᴛᴜᴠᴡʏᴢ⁰¹²³⁴⁵⁶⁷⁸⁹⁺"
    private const val PLAIN = "abdeghijklmnoprtuvwabcdefghijklmnoprstuvwxyzilnuabcdefghijklmnoprstuvwyz0123456789+"
    private val RUN = Regex("[$SMALL]+(?:\\s*[$SMALL]+)*")
    private val MARKS = setOf("live", "new", "hd", "fhd", "uhd", "4k")
    private val SPACES = Regex("\\s+")

    /** [text] with small raised letters made plain, and runs that are only marks removed. */
    fun plain(text: String): String {
        if (text.none { it in SMALL }) return text
        return text.replace(RUN) { match ->
            val words = buildString {
                match.value.forEach { ch ->
                    val i = SMALL.indexOf(ch)
                    if (i >= 0) append(PLAIN[i]) else if (ch.isWhitespace()) append(' ')
                }
            }.trim()
            if (words.split(' ').filter { it.isNotBlank() }.all { it in MARKS }) " " else " $words "
        }.replace(SPACES, " ").trim()
    }
}
