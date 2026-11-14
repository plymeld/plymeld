package plymeld.gcode

/**
 * The placeholder language used in QIDIStudio / OrcaSlicer / PrusaSlicer custom g-code, as
 * observed in their templates and rendered output (not from their code):
 *
 *   [name]                   substitution; vector settings give slot 0
 *   {expr}                   expression, output as text
 *   {if c}..{elsif c}..{else}..{endif}   conditional blocks (may span lines)
 *
 * Expressions: numbers, "strings", identifiers, name[i], + - * / %, comparisons, and/or/not
 * (also && || !), min(), max(), int(), and parentheses. Integer / integer is INTEGER division
 * — confirmed from QIDIStudio output: `24/20` renders as 1 (F720 = 12/1*60).
 *
 * An unknown name is an error, never an empty string: a silently blank temperature in start
 * g-code is exactly the kind of defect that ships.
 */
object Template {
    sealed interface Value
    data class Num(val v: Double, val isInt: Boolean) : Value
    data class Str(val v: String) : Value
    data class Vec(val items: List<Value>) : Value

    fun num(v: Long) = Num(v.toDouble(), true)
    fun num(v: Double) = Num(v, false)

    /** Parse a setting's string form: integers stay integers, decimals are doubles, else string. */
    fun parse(s: String): Value {
        s.toLongOrNull()?.let { return Num(it.toDouble(), true) }
        s.toDoubleOrNull()?.let { return Num(it, false) }
        return Str(s)
    }

    fun render(src: String, lookup: (String) -> Value?): String {
        val nodes = Parser(src).parseBlock(stopAt = emptySet()).first
        val sb = StringBuilder()
        emit(nodes, lookup, sb)
        return sb.toString()
    }

    // ---- template structure ---------------------------------------------------------------

    private sealed interface Node
    private data class Text(val s: String) : Node
    private data class Subst(val name: String) : Node
    /** An `{expression}` written out; [src] its text, for a refusal. */
    private data class ExprOut(val e: Expr, val src: String) : Node
    private data class If(val branches: List<Pair<Expr?, List<Node>>>) : Node   // null cond = else

    private fun emit(nodes: List<Node>, lookup: (String) -> Value?, sb: StringBuilder) {
        for (n in nodes) when (n) {
            is Text -> sb.append(n.s)
            is Subst -> sb.append(format(finite(resolve(n.name, lookup)) { "[${n.name}]" }))
            is ExprOut -> sb.append(format(finite(eval(n.e, lookup)) { "{${n.src}}" }))
            is If -> for ((c, body) in n.branches) if (c == null || truthy(eval(c, lookup))) { emit(body, lookup, sb); break }
        }
    }

    /**
     * [v], refused when it is a number that is not finite: written, NaN would come out as 0 (a whole number's form) or fail
     * deep in formatting; [what] names the placeholder.
     */
    private fun finite(v: Value, what: () -> String): Value {
        if (v is Num && !v.v.isFinite()) error("template placeholder ${what()} is ${v.v}, not a number: no g-code can be written for it")
        return v
    }

    private fun resolve(name: String, lookup: (String) -> Value?): Value {
        val v = lookup(name) ?: error("template refers to unknown name '$name'")
        return if (v is Vec) (v.items.firstOrNull() ?: error("'$name' is an empty vector")) else v
    }

    fun format(v: Value): String = when (v) {
        is Str -> v.v
        is Vec -> format(v.items.first())
        is Num -> if (v.isInt) v.v.toLong().toString() else trimNum(v.v)
    }

    private fun trimNum(d: Double): String {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        var s = java.math.BigDecimal(d).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        if (s == "-0") s = "0"
        return s
    }

    private class Parser(val s: String) {
        var i = 0
        /** Parses nodes until a `{elsif..}`, `{else}` or `{endif}` in stopAt; returns (nodes, tag found). */
        fun parseBlock(stopAt: Set<String>): Pair<List<Node>, String?> {
            val out = ArrayList<Node>()
            val text = StringBuilder()
            fun flush() { if (text.isNotEmpty()) { out.add(Text(text.toString())); text.clear() } }
            while (i < s.length) {
                val c = s[i]
                if (c == '[') {
                    val j = s.indexOf(']', i)
                    val name = if (j > i) s.substring(i + 1, j) else null
                    if (name != null && name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { flush(); out.add(Subst(name)); i = j + 1; continue }
                    text.append(c); i++; continue
                }
                if (c == '{') {
                    val j = matchingBrace(i)
                    val inner = s.substring(i + 1, j).trim()
                    val word = inner.substringBefore(' ').substringBefore('(')
                    when {
                        word == "if" -> {
                            flush(); i = j + 1
                            val branches = ArrayList<Pair<Expr?, List<Node>>>()
                            var cond: Expr? = ExprParser(inner.removePrefix("if").trim()).parseAll()
                            while (true) {
                                val (body, tag) = parseBlock(setOf("elsif", "else", "endif"))
                                branches.add(cond to body)
                                when {
                                    tag == null -> error("unterminated {if}")
                                    tag == "endif" -> break
                                    tag == "else" -> cond = null
                                    tag.startsWith("elsif") -> cond = ExprParser(tag.removePrefix("elsif").trim()).parseAll()
                                }
                            }
                            out.add(If(branches))
                            continue
                        }
                        word == "elsif" || word == "else" || word == "endif" -> {
                            if (word !in stopAt) error("unexpected {$inner}")
                            flush(); i = j + 1
                            return out to (if (word == "elsif") inner else word)
                        }
                        else -> { flush(); out.add(ExprOut(ExprParser(inner).parseAll(), inner)); i = j + 1; continue }
                    }
                }
                text.append(c); i++
            }
            flush()
            if (stopAt.isNotEmpty()) error("unterminated {if}: expected one of $stopAt")
            return out to null
        }

        private fun matchingBrace(open: Int): Int {
            var depth = 0; var k = open; var inStr = false
            while (k < s.length) {
                val ch = s[k]
                if (ch == '"') inStr = !inStr
                if (!inStr) { if (ch == '{') depth++ else if (ch == '}') { depth--; if (depth == 0) return k } }
                k++
            }
            error("unbalanced '{' at offset $open")
        }
    }

    // ---- expressions ------------------------------------------------------------------------

    sealed interface Expr
    private data class Lit(val v: Value) : Expr
    private data class Var(val name: String, val index: Expr?) : Expr
    private data class Call(val fn: String, val args: List<Expr>) : Expr
    private data class Bin(val op: String, val a: Expr, val b: Expr) : Expr
    private data class Un(val op: String, val a: Expr) : Expr

    private class ExprParser(val s: String) {
        var i = 0
        fun parseAll(): Expr { val e = or(); skip(); if (i != s.length) error("unexpected '${s.substring(i)}' in expression '$s'"); return e }
        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun peekWord(w: String): Boolean { skip(); return s.startsWith(w, i) && (i + w.length == s.length || !s[i + w.length].isLetterOrDigit()) }
        private fun peek(t: String): Boolean { skip(); return s.startsWith(t, i) }
        private fun or(): Expr { var a = and(); while (true) { if (peekWord("or")) { i += 2; a = Bin("or", a, and()) } else if (peek("||")) { i += 2; a = Bin("or", a, and()) } else return a } }
        private fun and(): Expr { var a = not(); while (true) { if (peekWord("and")) { i += 3; a = Bin("and", a, not()) } else if (peek("&&")) { i += 2; a = Bin("and", a, not()) } else return a } }
        private fun not(): Expr { if (peekWord("not")) { i += 3; return Un("not", not()) }; if (peek("!") && !peek("!=")) { i++; return Un("not", not()) }; return cmp() }
        private fun cmp(): Expr {
            val a = add()
            for (op in listOf("<=", ">=", "==", "!=", "<", ">")) if (peek(op)) { i += op.length; return Bin(op, a, add()) }
            return a
        }
        private fun add(): Expr { var a = mul(); while (true) { if (peek("+")) { i++; a = Bin("+", a, mul()) } else if (peek("-")) { i++; a = Bin("-", a, mul()) } else return a } }
        private fun mul(): Expr { var a = unary(); while (true) { if (peek("*")) { i++; a = Bin("*", a, unary()) } else if (peek("/")) { i++; a = Bin("/", a, unary()) } else if (peek("%")) { i++; a = Bin("%", a, unary()) } else return a } }
        private fun unary(): Expr { if (peek("-")) { i++; return Un("-", unary()) }; return primary() }
        private fun primary(): Expr {
            skip()
            if (i >= s.length) error("expression ends early: '$s'")
            val c = s[i]
            if (c == '(') { i++; val e = or(); skip(); expect(')'); return e }
            if (c == '"') { val j = s.indexOf('"', i + 1); val v = s.substring(i + 1, j); i = j + 1; return Lit(Str(v)) }
            if (c.isDigit() || c == '.') {
                val st = i; while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                val t = s.substring(st, i)
                return Lit(if (t.contains('.')) Num(t.toDouble(), false) else Num(t.toDouble(), true))
            }
            if (c.isLetter() || c == '_') {
                val st = i; while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                val name = s.substring(st, i)
                if (name == "true") return Lit(Num(1.0, true)); if (name == "false") return Lit(Num(0.0, true))
                if (peek("(")) {
                    i++; val args = ArrayList<Expr>()
                    if (!peek(")")) { args.add(or()); while (peek(",")) { i++; args.add(or()) } }
                    skip(); expect(')'); return Call(name, args)
                }
                if (peek("[")) { i++; val idx = or(); skip(); expect(']'); return Var(name, idx) }
                return Var(name, null)
            }
            error("unexpected '$c' in expression '$s'")
        }
        private fun expect(ch: Char) { if (i >= s.length || s[i] != ch) error("expected '$ch' in '$s'"); i++ }
    }

    private fun truthy(v: Value): Boolean = when (v) {
        is Num -> v.v != 0.0
        is Str -> v.v.isNotEmpty() && v.v != "0" && v.v != "false"
        is Vec -> v.items.isNotEmpty()
    }

    private fun eval(e: Expr, lookup: (String) -> Value?): Value = when (e) {
        is Lit -> e.v
        is Var -> {
            val v = lookup(e.name) ?: error("template refers to unknown name '${e.name}'")
            if (e.index != null) {
                val k = (eval(e.index, lookup) as? Num ?: error("index of ${e.name} is not a number")).v.toInt()
                if (v is Vec) v.items.getOrNull(k) ?: error("${e.name}[$k] out of range") else v
            } else if (v is Vec) v.items.firstOrNull() ?: error("'${e.name}' is empty") else v
        }
        is Un -> when (e.op) {
            "-" -> (eval(e.a, lookup) as Num).let { Num(-it.v, it.isInt) }
            else -> Num(if (truthy(eval(e.a, lookup))) 0.0 else 1.0, true)
        }
        is Call -> {
            val args = e.args.map { eval(it, lookup) as? Num ?: error("${e.fn}() takes numbers") }
            when (e.fn) {
                "min" -> args.minBy { it.v }
                "max" -> args.maxBy { it.v }
                "int" -> Num(Math.floor(args.single().v), true)
                else -> error("unknown function ${e.fn}()")
            }
        }
        is Bin -> {
            if (e.op == "and") Num(if (truthy(eval(e.a, lookup)) && truthy(eval(e.b, lookup))) 1.0 else 0.0, true)
            else if (e.op == "or") Num(if (truthy(eval(e.a, lookup)) || truthy(eval(e.b, lookup))) 1.0 else 0.0, true)
            else {
                val a = eval(e.a, lookup); val b = eval(e.b, lookup)
                if (a is Str || b is Str) {
                    val x = format(a); val y = format(b)
                    when (e.op) { "==" -> Num(if (x == y) 1.0 else 0.0, true); "!=" -> Num(if (x != y) 1.0 else 0.0, true); "+" -> Str(x + y); else -> error("'${e.op}' on strings") }
                } else {
                    a as Num; b as Num
                    val int = a.isInt && b.isInt
                    when (e.op) {
                        "+" -> Num(a.v + b.v, int); "-" -> Num(a.v - b.v, int); "*" -> Num(a.v * b.v, int)
                        "/" -> if (int) Num((a.v.toLong() / b.v.toLong()).toDouble(), true) else Num(a.v / b.v, false)
                        "%" -> Num(a.v % b.v, int)
                        "<" -> Num(if (a.v < b.v) 1.0 else 0.0, true); "<=" -> Num(if (a.v <= b.v) 1.0 else 0.0, true)
                        ">" -> Num(if (a.v > b.v) 1.0 else 0.0, true); ">=" -> Num(if (a.v >= b.v) 1.0 else 0.0, true)
                        "==" -> Num(if (a.v == b.v) 1.0 else 0.0, true); "!=" -> Num(if (a.v != b.v) 1.0 else 0.0, true)
                        else -> error("unknown operator ${e.op}")
                    }
                }
            }
        }
    }
}
