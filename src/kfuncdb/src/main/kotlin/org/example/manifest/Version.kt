package org.example.manifest

/** Simple semantic version representation and matching. */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        return compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(text: String): SemVer {
            val parts = text.trim().split('.', limit = 3)
            fun p(i: Int) = parts.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            return SemVer(p(0), p(1), p(2))
        }
    }
}

/**
 * Version constraint parser. Supports expressions like:
 *  - ">=1.2.3 <2.0.0"
 *  - "1.2.3"
 *  - "1.2.x" (wildcards)
 * Tokens can be separated by spaces or commas. All tokens must match (logical AND).
 */
class VersionConstraint private constructor(private val tokens: List<Token>) {
    fun matches(version: SemVer): Boolean = tokens.all { it.matches(version) }

    override fun toString(): String = tokens.joinToString(" ")

    companion object {
        fun parse(expr: String?): VersionConstraint? {
            if (expr == null) return null
            val ts = expr.split(',', ' ').mapNotNull { it.trim() }.filter { it.isNotEmpty() }
                .map { Token.parse(it) }
            return VersionConstraint(ts)
        }
    }

    private sealed interface Token {
        fun matches(v: SemVer): Boolean

        data class Cmp(val op: String, val ver: SemVer) : Token {
            override fun matches(v: SemVer): Boolean = when (op) {
                ">" -> v > ver
                ">=" -> v >= ver
                "<" -> v < ver
                "<=" -> v <= ver
                "=" -> v == ver
                else -> v == ver
            }
        }

        data class WildcardEq(val major: Int?, val minor: Int?, val patch: Int?) : Token {
            override fun matches(v: SemVer): Boolean {
                if (major != null && v.major != major) return false
                if (minor != null && v.minor != minor) return false
                if (patch != null && v.patch != patch) return false
                return true
            }
        }

        companion object {
            fun parse(token: String): Token {
                val ops = listOf(">=", "<=", ">", "<", "=")
                val op = ops.firstOrNull { token.startsWith(it) }
                return if (op != null) {
                    val v = SemVer.parse(token.removePrefix(op))
                    Cmp(op, v)
                } else if (token.contains('x') || token.contains('*')) {
                    val parts = token.split('.')
                    fun part(ix: Int): Int? = parts.getOrNull(ix)?.let {
                        if (it == "x" || it == "*") null else it.toIntOrNull()
                    }
                    WildcardEq(part(0), part(1), part(2))
                } else {
                    Cmp("=", SemVer.parse(token))
                }
            }
        }
    }
}
