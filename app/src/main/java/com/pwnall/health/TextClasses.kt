package com.pwnall.health

// The character classes the app's patterns are written with, because the phone and the tests do not
// read the short ones alike. Android compiles a pattern with ICU, the JVM the tests run on with
// java.util.regex: to ICU `\d` is a digit of any script and `\s` is [\t\n\f\r\p{Z}], every space
// Unicode names, the no-break ones a French or Russian text puts between a figure and its unit
// among them; to java.util.regex `\d` is ASCII's ten and `\s` is [ \t\n\x0B\f\r]. So a pattern
// that read a message, a sheet or a file would do on the phone what no test here could see. A pattern
// says [0-9] for a digit, and [SPACE] for a space; IcuRegexTest keeps the short classes out.

/** Whitespace as ICU's `\s` reads it, spelled out so that java.util.regex reads it the same: `${SPACE}*`. */
internal const val SPACE = """[\t\n\f\r\p{Z}]"""

/** [SPACE] without its brackets, for a set of its own: `[^0-9${SPACE_CHARS}]`. */
internal const val SPACE_CHARS = """\t\n\f\r\p{Z}"""

/**
 * Every decimal digit as its ASCII one: «٣», «३» and «3» are one three. For text a pattern written
 * with [0-9] reads in any language, so that a figure typed in the digits of the keyboard's script is
 * still read; one character for one, so a match's place in the result is its place in the text.
 */
internal fun String.asciiDigits(): String = map { c -> c.digitToIntOrNull()?.digitToChar() ?: c }.joinToString("")
