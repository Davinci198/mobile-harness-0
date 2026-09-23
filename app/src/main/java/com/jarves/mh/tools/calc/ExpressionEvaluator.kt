package com.jarves.mh.tools.calc

/** Thrown when an expression cannot be parsed or evaluated safely. */
class CalcParseException(message: String) : Exception(message)

/**
 * Safe recursive-descent evaluator for arithmetic expressions.
 *
 * Supports numbers, `+ - * / % ^`, parentheses, unary `+`/`-`,
 * constants (`PI`, `E`, `pi`, `e`, `tau`), and a fixed function set.
 * No identifiers outside that set, no assignment, no arbitrary execution.
 */
class ExpressionEvaluator(private val src: String) {
    private var i = 0

    fun evaluate(): Double {
        val value = parseExpr()
        skipWs()
        if (i < src.length) fail("Unexpected '${src[i]}'")
        if (!value.isFinite()) fail("Result is not a finite number")
        return value
    }

    private fun parseExpr(): Double {
        var value = parseTerm()
        while (true) {
            skipWs()
            when {
                match('+') -> value += parseTerm()
                match('-') -> value -= parseTerm()
                else -> return value
            }
        }
    }

    private fun parseTerm(): Double {
        var value = parseUnary()
        while (true) {
            skipWs()
            when {
                match('*') -> value *= parseUnary()
                match('/') -> {
                    val divisor = parseUnary()
                    if (divisor == 0.0) fail("Division by zero")
                    value /= divisor
                }
                match('%') -> {
                    val divisor = parseUnary()
                    if (divisor == 0.0) fail("Modulo by zero")
                    value %= divisor
                }
                else -> return value
            }
        }
    }

    private fun parseUnary(): Double {
        skipWs()
        if (match('+')) return parseUnary()
        if (match('-')) return -parseUnary()
        return parsePower()
    }

    private fun parsePower(): Double {
        val base = parsePrimary()
        skipWs()
        if (match('^')) {
            val exponent = parseUnary()
            return Math.pow(base, exponent)
        }
        return base
    }

    private fun parsePrimary(): Double {
        skipWs()
        if (i >= src.length) fail("Unexpected end of expression")
        val c = src[i]
        if (c == '(') {
            i++
            val value = parseExpr()
            skipWs()
            if (!match(')')) fail("Missing ')'")
            return value
        }
        if (c.isDigit() || c == '.') return parseNumber()
        if (c.isLetter() || c == '_') return parseIdentOrCall()
        fail("Unexpected '$c'")
    }

    private fun parseNumber(): Double {
        val start = i
        while (i < src.length && src[i].isDigit()) i++
        if (i < src.length && src[i] == '.') {
            i++
            while (i < src.length && src[i].isDigit()) i++
        }
        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
            val save = i
            i++
            if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
            if (i < src.length && src[i].isDigit()) {
                while (i < src.length && src[i].isDigit()) i++
            } else {
                i = save
            }
        }
        val text = src.substring(start, i)
        return text.toDoubleOrNull() ?: fail("Invalid number '$text'")
    }

    private fun parseIdentOrCall(): Double {
        val start = i
        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
        val name = src.substring(start, i)
        skipWs()
        if (i < src.length && src[i] == '(') {
            i++
            val args = mutableListOf<Double>()
            skipWs()
            if (!match(')')) {
                args += parseExpr()
                while (match(',')) args += parseExpr()
                if (!match(')')) fail("Missing ')' after arguments of '$name'")
            }
            return callFunction(name, args)
        }
        return constant(name) ?: fail("Unknown identifier '$name'")
    }

    private fun constant(name: String): Double? = when (name) {
        "PI", "pi" -> Math.PI
        "E", "e" -> Math.E
        "TAU", "tau" -> Math.PI * 2.0
        else -> null
    }

    private fun callFunction(name: String, args: List<Double>): Double {
        fun arg(index: Int): Double {
            if (index >= args.size) fail("'$name' is missing argument ${index + 1}")
            return args[index]
        }

        fun expectArity(n: Int) {
            if (args.size != n) fail("'$name' expects $n argument(s), got ${args.size}")
        }

        return when (name) {
            "sqrt" -> { expectArity(1); Math.sqrt(arg(0)) }
            "abs" -> { expectArity(1); Math.abs(arg(0)) }
            "floor" -> { expectArity(1); Math.floor(arg(0)) }
            "ceil" -> { expectArity(1); Math.ceil(arg(0)) }
            "round" -> { expectArity(1); Math.round(arg(0)).toDouble() }
            "trunc" -> { expectArity(1); Math.trunc(arg(0)) }
            "sign" -> { expectArity(1); Math.signum(arg(0)) }
            "exp" -> { expectArity(1); Math.exp(arg(0)) }
            "ln" -> { expectArity(1); Math.log(arg(0)) }
            "log" -> { expectArity(1); Math.log10(arg(0)) }
            "log2" -> { expectArity(1); Math.log(arg(0)) / Math.log(2.0) }
            "sin" -> { expectArity(1); Math.sin(arg(0)) }
            "cos" -> { expectArity(1); Math.cos(arg(0)) }
            "tan" -> { expectArity(1); Math.tan(arg(0)) }
            "asin" -> { expectArity(1); Math.asin(arg(0)) }
            "acos" -> { expectArity(1); Math.acos(arg(0)) }
            "atan" -> { expectArity(1); Math.atan(arg(0)) }
            "min" -> { expectArity(2); Math.min(arg(0), arg(1)) }
            "max" -> { expectArity(2); Math.max(arg(0), arg(1)) }
            "pow" -> { expectArity(2); Math.pow(arg(0), arg(1)) }
            "hypot" -> { expectArity(2); Math.hypot(arg(0), arg(1)) }
            else -> fail("Unknown function '$name'")
        }
    }

    private fun skipWs() {
        while (i < src.length && src[i].isWhitespace()) i++
    }

    private fun match(c: Char): Boolean {
        if (i < src.length && src[i] == c) {
            i++
            return true
        }
        return false
    }

    private fun fail(message: String): Nothing = throw CalcParseException(message)
}
