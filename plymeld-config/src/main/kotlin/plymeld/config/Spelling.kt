package plymeld.config

/**
 * Values the user types are spelled the American way (`center`), and the British way is read as the same where that is
 * unambiguous: a word's British spelling becomes the American one only when the result is one of the values the place
 * takes ([american]). The stack reads every chosen value through it ([ConfigStack]), so does an option taking one.
 */
object Spelling {
    /** British spellings of the words plymeld's values use, and their American ones. */
    private val WORDS = listOf("centre" to "center", "colour" to "color", "grey" to "gray", "metre" to "meter", "litre" to "liter",
        "aluminium" to "aluminum", "fibre" to "fiber")

    /** [v] as one of [options]: itself when it is one, else its American spelling when that is one; null when neither is. */
    fun american(v: String, options: Collection<String>): String? {
        if (v in options) return v
        var out = v
        for ((b, a) in WORDS) out = out.replace(b, a)
        return out.takeIf { it != v && it in options }
    }

    /** [e] with a chosen value spelled the British way written the American way, where its setting takes the American one. */
    fun entry(e: ConfigFiles.Entry): ConfigFiles.Entry {
        val t = Schema.find(e.path)?.type ?: return e
        val v = e.value
        val fixed: Any? = when {
            t is Schema.Type.Choice && v is String -> american(v, t.options)
            t is Schema.Type.Choices && v is List<*> && v.all { it is String } -> v.map { american(it as String, t.options) ?: it }.takeIf { it != v }
            else -> null
        }
        return if (fixed == null || fixed == v) e else ConfigFiles.Entry(e.path, fixed, e.source, e.keep)
    }
}
