package plymeld.config

/**
 * Formulas in config values:
 * any value that starts with `=` after trimming. A small language: numbers with units, `+ - * /`,
 * parentheses, `min`, `max`, `clamp`, `round`, `floor`, `ceil`, and references to other values by path
 * (absolute, or through a context name such as `tool`). Units are checked: every quantity carries the
 * powers of its base units, and the result must be the kind of quantity the value holds.
 *
 * A switch (a true / false setting) may be a formula too: `true`, `false`, `not`, `and`, `or`, comparisons of two
 * quantities of the same kind (`== != < <= > >=`), of two words (a choice or text: `==`, `!=`, a word written in single
 * quotes: `filament.kind == 'PETG'`) or of two switches (`==`, `!=`); and `if(condition, a, b)` picks one of two values
 * of the same kind. A reference gives a quantity, a switch's true / false, or a choice's word, as the value it names is.
 */
object Formula {
    /**
     * Powers of the base units: length (mm), time (s), temperature (C), angle (deg), mass (g), density (g/cm3). All zero
     * = a plain number. A density is a base quantity of its own here, so it scales, adds, and compares only with
     * densities: no formula can mix its cm3 with the millimetres of a length (density x volume is refused, not a mass).
     */
    data class Dims(val length: Int = 0, val time: Int = 0, val temperature: Int = 0, val angle: Int = 0, val density: Int = 0, val mass: Int = 0) {
        operator fun plus(o: Dims) = Dims(length + o.length, time + o.time, temperature + o.temperature, angle + o.angle, density + o.density, mass + o.mass)
        operator fun minus(o: Dims) = Dims(length - o.length, time - o.time, temperature - o.temperature, angle - o.angle, density - o.density, mass - o.mass)
        override fun toString(): String {
            val parts = listOf("mm" to length, "s" to time, "C" to temperature, "deg" to angle, "g/cm3" to density, "g" to mass).filter { it.second != 0 }
            return if (parts.isEmpty()) "a plain number" else parts.joinToString("·") { (u, p) -> if (p == 1) u else "$u^$p" }
        }
    }

    /** A formula's value: a quantity, a switch's true / false, or a word (a choice or text). */
    sealed interface V
    /** A number in base units with its dimensions. */
    data class Q(val value: Double, val dims: Dims) : V
    /** True or false. */
    data class B(val value: Boolean) : V
    /** A word: a choice's option or a text value. */
    data class T(val value: String) : V

    /** What [v] is, for messages. */
    fun kindOf(v: V): String = when (v) {
        is Q -> v.dims.toString()
        is B -> "true or false"
        is T -> "a word ('${v.value}')"
    }

    /** The dimensions of each config [Dimension]. */
    fun dimsOf(d: Dimension): Dims = when (d) {
        Dimension.LENGTH -> Dims(length = 1)
        Dimension.AREA -> Dims(length = 2)
        Dimension.VOLUME -> Dims(length = 3)
        Dimension.TIME -> Dims(time = 1)
        Dimension.SPEED -> Dims(length = 1, time = -1)
        Dimension.ACCEL -> Dims(length = 1, time = -2)
        Dimension.FLOW -> Dims(length = 3, time = -1)
        Dimension.ANGLE -> Dims(angle = 1)
        Dimension.TEMPERATURE -> Dims(temperature = 1)
        Dimension.DENSITY -> Dims(density = 1)
        Dimension.MASS -> Dims(mass = 1)
        Dimension.RATIO, Dimension.COUNT, Dimension.WEIGHT -> Dims()
    }

    fun isFormula(raw: Any): Boolean = raw is String && raw.trim().startsWith("=")

    /**
     * Works out [text] (with its leading `=`). [lookup] gives the value of a reference — a path as
     * segments, the first possibly a context name; [what] names the value in errors.
     */
    fun evaluate(text: String, what: String, lookup: (List<String>) -> V): V {
        val body = text.trim().removePrefix("=")
        val p = Parser(body, what, lookup)
        val v = p.expr()
        p.end()
        return v
    }

    /** [evaluate], then check the result is a [dim] quantity, returning it in base units. */
    fun evaluate(text: String, dim: Dimension, what: String, lookup: (List<String>) -> V): Double {
        val q = evaluate(text, what, lookup)
        val want = dimsOf(dim)
        if (q !is Q || q.dims != want) throw IllegalArgumentException("$what: '${text.trim()}' comes out as ${kindOf(q)}, but the value is a ${dim.name.lowercase()} ($want)")
        return q.value
    }

    /** [evaluate], then check the result is true or false. */
    fun evaluateSwitch(text: String, what: String, lookup: (List<String>) -> V): Boolean {
        val b = evaluate(text, what, lookup)
        if (b !is B) throw IllegalArgumentException("$what: '${text.trim()}' comes out as ${kindOf(b)}, but the value is true or false")
        return b.value
    }

    /** Units a number in a formula may carry, by the dimension each belongs to. */
    private val UNITS: Map<String, Pair<Dims, Double>> = buildMap {
        for (d in Dimension.entries) for ((u, f) in d.units) if (d != Dimension.TEMPERATURE && d != Dimension.RATIO) put(u, dimsOf(d) to f)
        put("C", Dims(temperature = 1) to 1.0); put("°C", Dims(temperature = 1) to 1.0)
        put("%", Dims() to 0.01)
    }

    /** Words the language keeps for itself: never a reference. */
    private val RESERVED = setOf("not", "and", "or", "true", "false")

    private class Parser(val s: String, val what: String, val lookup: (List<String>) -> V) {
        var i = 0
        fun fail(msg: String): Nothing = throw IllegalArgumentException("$what: formula '= $s': $msg (at character ${i + 1})")
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek(): Char? { ws(); return s.getOrNull(i) }
        fun eat(c: Char): Boolean { if (peek() == c) { i++; return true }; return false }
        fun end() { if (peek() != null) fail("unexpected '${s.substring(i)}'") }

        /** The keyword [w] here (not the start of a longer name). */
        fun word(w: String): Boolean {
            ws()
            if (!s.startsWith(w, i)) return false
            val next = s.getOrNull(i + w.length)
            if (next != null && (next.isLetterOrDigit() || next == '_' || next == '.')) return false
            i += w.length
            return true
        }

        fun num(v: V, op: String): Q = v as? Q ?: fail("'$op' takes quantities, not ${kindOf(v)}")
        fun truth(v: V, op: String): Boolean = (v as? B)?.value ?: fail("'$op' takes true or false, not ${kindOf(v)}")

        fun expr(): V = or()

        fun or(): V {
            var a = and()
            while (word("or")) { val b = and(); a = B(truth(a, "or") or truth(b, "or")) }
            return a
        }

        fun and(): V {
            var a = not()
            while (word("and")) { val b = not(); a = B(truth(a, "and") and truth(b, "and")) }
            return a
        }

        fun not(): V = if (word("not")) B(!truth(not(), "not")) else compare()

        fun compare(): V {
            val a = sum()
            ws()
            val op = listOf("==", "!=", "<=", ">=", "<", ">").firstOrNull { s.startsWith(it, i) } ?: return a
            i += op.length
            val b = sum()
            return B(when {
                a is Q && b is Q -> {
                    if (a.dims != b.dims) fail("cannot compare ${a.dims} with ${b.dims}")
                    val eps = 1e-9 * maxOf(1.0, Math.abs(a.value), Math.abs(b.value))
                    when (op) {
                        "==" -> Math.abs(a.value - b.value) <= eps; "!=" -> Math.abs(a.value - b.value) > eps
                        "<" -> a.value < b.value - eps; "<=" -> a.value <= b.value + eps
                        ">" -> a.value > b.value + eps; else -> a.value >= b.value - eps
                    }
                }
                a is T && b is T && (op == "==" || op == "!=") -> (a.value == b.value) == (op == "==")
                a is B && b is B && (op == "==" || op == "!=") -> (a.value == b.value) == (op == "==")
                a is T || a is B -> fail("${kindOf(a)} compares with '==' or '!=' only, with the same kind")
                else -> fail("cannot compare ${kindOf(a)} with ${kindOf(b)}")
            })
        }

        fun sum(): V {
            var a = term()
            while (true) {
                val c = peek()
                if (c == '+' || c == '-') {
                    i++
                    val x = num(a, c.toString()); val b = num(term(), c.toString())
                    if (x.dims != b.dims) fail("cannot ${if (c == '+') "add" else "subtract"} ${b.dims} ${if (c == '+') "to" else "from"} ${x.dims}")
                    a = Q(if (c == '+') x.value + b.value else x.value - b.value, x.dims)
                } else return a
            }
        }

        fun term(): V {
            var a = unary()
            while (true) {
                val c = peek()
                if (c == '*' || c == '/') {
                    i++
                    val x = num(a, c.toString()); val b = num(unary(), c.toString())
                    a = if (c == '*') Q(x.value * b.value, x.dims + b.dims)
                    else { if (b.value == 0.0) fail("division by zero"); Q(x.value / b.value, x.dims - b.dims) }
                } else return a
            }
        }

        fun unary(): V {
            if (eat('-')) return num(unary(), "-").let { Q(-it.value, it.dims) }
            if (eat('+')) return num(unary(), "+")
            return atom()
        }

        fun atom(): V {
            val c = peek() ?: fail("a value is missing")
            if (c == '(') { i++; val v = expr(); if (!eat(')')) fail("')' expected"); return v }
            if (c.isDigit() || c == '.') return number()
            if (c == '\'') {
                val end = s.indexOf('\'', i + 1); if (end < 0) fail("unclosed word (a word is written in single quotes)")
                return T(s.substring(i + 1, end)).also { i = end + 1 }
            }
            if (word("true")) return B(true)
            if (word("false")) return B(false)
            if (c.isLetter() || c == '"') {
                val start = i
                val path = path()
                if (path.size == 1 && peek() == '(') return call(path[0], start)
                if (path.size == 1 && path[0] in RESERVED) { i = start; fail("'${path[0]}' needs a value beside it") }
                return lookup(path)
            }
            fail("unexpected '$c'")
        }

        fun number(): Q {
            val m = Regex("""(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""").find(s, i)?.takeIf { it.range.first == i } ?: fail("a number expected")
            i = m.range.last + 1
            val v = m.value.toDouble()
            // a unit right after the number, with or without a space
            val save = i
            ws()
            val u = Regex("""(?:°C|°|µm|%|[A-Za-z]+(?:\^?[23²³])?(?:/[A-Za-z]+(?:\^?[23²³])?)?)""").find(s, i)?.takeIf { it.range.first == i }
            if (u != null && UNITS.containsKey(u.value)) {
                i = u.range.last + 1
                val (d, f) = UNITS.getValue(u.value)
                return Q(v * f, d)
            }
            i = save
            return Q(v, Dims())
        }

        /** A dotted path: bare segments (letters, digits, `_`, and `-` between them) or quoted ones. */
        fun path(): List<String> {
            val out = ArrayList<String>()
            while (true) {
                ws()
                if (s.getOrNull(i) == '"') {
                    val end = s.indexOf('"', i + 1); if (end < 0) fail("unclosed quote")
                    out.add(s.substring(i + 1, end)); i = end + 1
                } else {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || (s[i] == '-' && i > start && i + 1 < s.length && s[i + 1].isLetterOrDigit()) || s[i] == '#')) i++
                    if (i == start) fail("a name expected")
                    out.add(s.substring(start, i))
                }
                if (s.getOrNull(i) == '.') i++ else return out
            }
        }

        fun call(name: String, at: Int): V {
            eat('(')
            val args = ArrayList<V>()
            if (!eat(')')) { do args.add(expr()) while (eat(',')); if (!eat(')')) fail("')' expected after the arguments of $name") }
            if (name == "if") {
                if (args.size != 3) fail("if takes (condition, value if true, value if false)")
                val (a, b) = args[1] to args[2]
                val same = (a is Q && b is Q && a.dims == b.dims) || (a is B && b is B) || (a is T && b is T)
                if (!same) fail("if needs its two values of the same kind, got ${kindOf(a)} and ${kindOf(b)}")
                return if (truth(args[0], "if")) a else b
            }
            val qs = args.map { num(it, name) }
            fun same(qs: List<Q>) { if (qs.any { it.dims != qs[0].dims }) fail("$name needs its arguments in the same kind of quantity, got ${qs.joinToString { it.dims.toString() }}") }
            fun step(x: Q, st: Q?, f: (Double) -> Double): Q {
                if (st == null) return Q(f(x.value), x.dims)
                if (st.dims != x.dims) fail("$name's step must be the same kind of quantity as its value")
                if (st.value <= 0) fail("$name's step must be positive")
                return Q(f(x.value / st.value) * st.value, x.dims)
            }
            return when (name) {
                "min", "max" -> { if (qs.isEmpty()) fail("$name needs at least one value"); same(qs); if (name == "min") qs.minBy { it.value } else qs.maxBy { it.value } }
                "clamp" -> { if (qs.size != 3) fail("clamp takes (value, low, high)"); same(qs)
                    if (qs[1].value > qs[2].value) fail("clamp's low is above its high"); Q(qs[0].value.coerceIn(qs[1].value, qs[2].value), qs[0].dims) }
                "round", "floor", "ceil" -> { if (qs.size !in 1..2) fail("$name takes (value) or (value, step)")
                    step(qs[0], qs.getOrNull(1), when (name) { "round" -> { x -> Math.round(x).toDouble() }; "floor" -> Math::floor; else -> Math::ceil }) }
                else -> { i = at; fail("no function '$name' (min, max, clamp, round, floor, ceil, if)") }
            }
        }
    }
}
