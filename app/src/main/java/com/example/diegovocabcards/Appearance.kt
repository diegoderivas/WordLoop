package com.example.diegovocabcards

internal enum class Appearance(val key: String) {
    System("system"), Light("light"), Dark("dark");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: System
    }
}
