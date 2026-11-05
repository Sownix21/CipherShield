package com.example

import kotlin.math.ln

/** A conservative local heuristic. It is guidance, not a password-cracking oracle. */
object KeyStrengthEvaluator {
    enum class StrengthTier(val score: Int, val label: String) {
        WEAK(1, "Weak"),
        MEDIUM(2, "Medium"),
        STRONG(3, "Strong"),
        EXCELLENT(4, "Excellent")
    }

    private val blockedTerms = arrayOf(
        "password", "passphrase", "123456", "qwerty", "admin", "welcome", "letmein",
        "secret", "ciphershield", "wireguard", "openvpn", "vless", "vmess",
        "shadowsocks", "trojan", "proxy"
    )
    private val keyboardRuns = arrayOf(
        "0123456789", "9876543210", "abcdefghijklmnopqrstuvwxyz",
        "zyxwvutsrqponmlkjihgfedcba", "qwertyuiop", "poiuytrewq", "asdfghjkl",
        "lkjhgfdsa", "zxcvbnm", "mnbvcxz"
    )

    fun evaluate(password: CharArray): StrengthTier {
        val length = password.size
        if (length < 8 || length > CipherShieldEngine.MAX_PASSWORD_CHARS) return StrengthTier.WEAK
        if (containsBlockedTerm(password) || isRepeatedPattern(password) || containsPredictableRun(password)) {
            return StrengthTier.WEAK
        }

        var hasLower = false
        var hasUpper = false
        var hasDigit = false
        var hasSpecial = false
        var hasNonAscii = false
        val unique = HashSet<Char>(length.coerceAtMost(128))
        password.forEach { char ->
            unique.add(char)
            when {
                char in 'a'..'z' -> hasLower = true
                char in 'A'..'Z' -> hasUpper = true
                char in '0'..'9' -> hasDigit = true
                char.code > 0x7F -> hasNonAscii = true
                else -> hasSpecial = true
            }
        }

        if (unique.size < 6 || unique.size * 4 < length) return StrengthTier.WEAK

        var pool = 0
        if (hasLower) pool += 26
        if (hasUpper) pool += 26
        if (hasDigit) pool += 10
        if (hasSpecial) pool += 33
        if (hasNonAscii) pool += 64
        val estimatedBits = length * (ln(pool.coerceAtLeast(2).toDouble()) / ln(2.0))
        val classCount = listOf(hasLower, hasUpper, hasDigit, hasSpecial, hasNonAscii).count { it }

        return when {
            estimatedBits < 55 || (classCount == 1 && length < 16) -> StrengthTier.WEAK
            estimatedBits < 75 || (classCount == 1 && length < 20) -> StrengthTier.MEDIUM
            estimatedBits < 100 -> StrengthTier.STRONG
            else -> StrengthTier.EXCELLENT
        }
    }

    private fun containsBlockedTerm(password: CharArray): Boolean = blockedTerms.any { term ->
        password.containsCaseInsensitive(term)
    }

    private fun containsPredictableRun(password: CharArray): Boolean {
        if (password.size < 4) return false
        return keyboardRuns.any { run ->
            for (start in 0..password.size - 4) {
                for (runStart in 0..run.length - 4) {
                    if (run.regionMatches(runStart, password, start, 4)) return true
                }
            }
            false
        }
    }

    private fun isRepeatedPattern(password: CharArray): Boolean {
        for (period in 1..password.size / 2) {
            if (password.size % period != 0) continue
            var repeated = true
            for (index in period until password.size) {
                if (password[index] != password[index % period]) {
                    repeated = false
                    break
                }
            }
            if (repeated) return true
        }
        return false
    }

    private fun CharArray.containsCaseInsensitive(term: String): Boolean {
        if (term.length > size) return false
        for (start in 0..size - term.length) {
            var matches = true
            for (offset in term.indices) {
                if (!this[start + offset].equals(term[offset], ignoreCase = true)) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun String.regionMatches(
        stringStart: Int,
        password: CharArray,
        passwordStart: Int,
        length: Int,
    ): Boolean {
        if (stringStart + length > this.length) return false
        for (offset in 0 until length) {
            if (!this[stringStart + offset].equals(password[passwordStart + offset], ignoreCase = true)) return false
        }
        return true
    }
}
