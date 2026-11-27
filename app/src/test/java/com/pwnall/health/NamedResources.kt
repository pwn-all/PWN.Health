package com.pwnall.health

import android.content.res.Resources

/**
 * Just enough of Resources for a JVM test: each string is its own resource name, with the arguments
 * it was formatted with in brackets, so a test can say which sentence arrived.
 *
 * No configuration, so nothing here resolves a [ReportText.Day].
 */
@Suppress("DEPRECATION") // The one constructor a JVM test can reach; nothing reads what it is given.
internal class NamedResources : Resources(null, null, null) {
    private val strings = namesOf("com.pwnall.health.R\$string")
    private val plurals = namesOf("com.pwnall.health.R\$plurals")

    override fun getString(id: Int): String = strings.getValue(id)

    override fun getString(id: Int, vararg formatArgs: Any?): String =
        getString(id) + formatArgs.joinToString("|", "[", "]")

    override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String =
        plurals.getValue(id) + "[$quantity]"

    override fun getQuantityString(id: Int, quantity: Int): String = plurals.getValue(id) + "[$quantity]"

    private fun namesOf(className: String): Map<Int, String> =
        Class.forName(className).fields.associate { it.getInt(null) to it.name }
}
