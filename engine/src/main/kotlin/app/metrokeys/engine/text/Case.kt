package app.metrokeys.engine.text

import java.util.Locale

/** Lower-cases character by character (Java `Character.toLowerCase` on each UTF-16 unit); keeps the length. */
public fun toKey(s: String): String {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (Character.toLowerCase(c) != c) break
        i++
    }
    if (i == s.length) return s
    val out = CharArray(s.length)
    for (j in s.indices) out[j] = Character.toLowerCase(s[j])
    return String(out)
}

/** The first character upper-cased (single-character mapping), the rest unchanged. */
public fun capitalizeFirst(s: String): String {
    if (s.isEmpty()) return s
    val up = Character.toUpperCase(s[0])
    return if (up == s[0]) s else up + s.substring(1)
}

/** Every character upper-cased with the single-character mapping (ß stays ß); keeps the length. */
public fun upperPerChar(s: String): String {
    val out = CharArray(s.length)
    for (j in s.indices) out[j] = Character.toUpperCase(s[j])
    return String(out)
}

/** Full upper-case display form (ß becomes SS). */
public fun upperDisplay(s: String): String = s.uppercase(Locale.ROOT)

/** The first character lower-cased, the rest unchanged. */
public fun decapitalize(s: String): String {
    if (s.isEmpty()) return s
    val low = Character.toLowerCase(s[0])
    return if (low == s[0]) s else low + s.substring(1)
}

public fun startsUpper(s: String): Boolean = s.isNotEmpty() && Character.isUpperCase(s[0])

public fun startsLower(s: String): Boolean = s.isNotEmpty() && Character.isLowerCase(s[0])

/** True if the word contains a letter and every character equals its upper-case form. */
public fun isAllCapsWord(s: String): Boolean {
    var letter = false
    for (c in s) {
        if (Character.isLetter(c)) letter = true
        if (Character.toUpperCase(c) != c) return false
    }
    return letter
}
