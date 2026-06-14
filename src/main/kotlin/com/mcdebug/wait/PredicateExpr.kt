package com.mcdebug.wait

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.mcdebug.rpc.RpcErrors
import com.mcdebug.rpc.RpcException

/**
 * Expression-based predicate language for `wait.until`, replacing the v1
 * single-comparison regex. The grammar (recursive descent):
 *
 *   predicate := or_expr
 *   or_expr   := and_expr (OR and_expr)*
 *   and_expr  := not_expr (AND not_expr)*
 *   not_expr  := NOT not_expr | comparison
 *   comparison:= arith (CMP_OP arith)?
 *   arith     := term ((PLUS | MINUS) term)*
 *   term      := factor (TIMES factor)*
 *   factor    := MINUS factor
 *              | NUMBER | STRING | BOOL | NULL
 *              | source_ref
 *              | aggregate
 *              | LPAREN or_expr RPAREN
 *
 *   source_ref := SOURCE ([ POS ])? ( PATH )?
 *      SOURCE := tick | be | inv | block
 *      POS    := [ int , int , int ]
 *      PATH   := . ident (. ident | [ int ])*
 *
 *   aggregate := (SUM | COUNT) LPAREN inv_wild RPAREN
 *      inv_wild := inv POS . * . FIELD      # sum/count over every slot of an inventory
 *
 * Truthiness rules used when a non-bool value lands in boolean context (AND/OR/NOT,
 * or top-level): number != 0 is true; non-empty string is true; "true"/"false"
 * strings are NOT special-cased (use the bool literal); null/json-null is false.
 *
 * Backward compatibility: a v1 predicate like `be[0,64,0].charge > 100` parses
 * as a single Comparison with an arithmetic lhs/rhs, so all existing callers keep
 * working unchanged.
 *
 * Safety: this is a hand-written parser/evaluator — there is no string eval, no
 * code execution, no reflection. The only side effects are reads of world state
 * via the leaf-resolver callback supplied by WaitOps.
 */
internal object PredicateExpr {

    // ---- public API ----

    /**
     * Parse a predicate string into an AST. Throws RpcException(INVALID_PREDICATE)
     * on any lexical or syntax error.
     */
    fun parse(input: String): Node {
        val tokens = Lexer(input).tokenize()
        val parser = Parser(tokens)
        val node = parser.parseOr()
        if (parser.hasNext()) {
            throw RpcException(RpcErrors.INVALID_PREDICATE, "unexpected token after predicate: '${parser.peekDesc()}'")
        }
        return node
    }

    /**
     * Evaluate a parsed [node] to a boolean. [resolveLeaf] turns a SourceRef
     * (be/inv/block/tick + optional pos + optional path) into a JsonElement value,
     * and [resolveAggregate] turns an Aggregate node into a number. Both are
     * supplied by the caller (WaitOps) as closures that capture the live server —
     * this keeps PredicateExpr free of any Minecraft dependency.
     */
    fun evaluate(
        node: Node,
        resolveLeaf: (SourceRef) -> JsonElement?,
        resolveAggregate: (Aggregate) -> JsonElement,
    ): Boolean = Evaluator(resolveLeaf, resolveAggregate).evalBool(node)

    // ---- AST ----

    sealed class Node {
        object True : Node()
        data class Not(val inner: Node) : Node()
        data class And(val left: Node, val right: Node) : Node()
        data class Or(val left: Node, val right: Node) : Node()
        data class Comparison(val op: String, val left: Node, val right: Node) : Node()
        data class Arith(val op: String, val left: Node, val right: Node) : Node()
        data class Negate(val inner: Node) : Node()
        data class Literal(val value: JsonElement) : Node()
        data class SourceRef(
            val source: String,           // tick | be | inv | block
            val pos: Triple<Int, Int, Int>? = null,
            val path: String = "",        // without leading dot; "" means whole
        ) : Node()
        data class Aggregate(
            val fn: String,               // sum | count
            val pos: Triple<Int, Int, Int>,
            val field: String,            // count | item | maxCount | nbt.<path> | * (count only)
        ) : Node()
    }

    typealias SourceRef = Node.SourceRef
    typealias Aggregate = Node.Aggregate

    // ---- lexer ----

    private enum class TokKind {
        NUMBER, STRING, IDENT,        // NUMBER, "string", bare word (and/or/not/sum/count/source)
        AND, OR, NOT, TRUE, FALSE, NULL,
        CMP_OP, PLUS, MINUS,
        LPAREN, RPAREN, LBRACKET, RBRACKET,
        DOT, COMMA, STAR,
        EOF,
    }

    private data class Token(val kind: TokKind, val text: String, val pos: Int)

    private class Lexer(private val src: String) {
        private val out = ArrayList<Token>()
        private var i = 0

        fun tokenize(): List<Token> {
            while (i < src.length) {
                val c = src[i]
                when {
                    c.isWhitespace() -> i++
                    c == '(' -> { emit(TokKind.LPAREN, c); i++ }
                    c == ')' -> { emit(TokKind.RPAREN, c); i++ }
                    c == '[' -> { emit(TokKind.LBRACKET, c); i++ }
                    c == ']' -> { emit(TokKind.RBRACKET, c); i++ }
                    c == ',' -> { emit(TokKind.COMMA, c); i++ }
                    c == '+' -> { emit(TokKind.PLUS, c); i++ }
                    c == '-' -> {
                        // Could be minus operator or start of a negative number. Let the parser
                        // decide; emit as MINUS and let NUMBER be lexed as its absolute value.
                        emit(TokKind.MINUS, c); i++
                    }
                    c == '*' -> { emit(TokKind.STAR, c); i++ }
                    c == '.' -> { emit(TokKind.DOT, c); i++ }
                    c == '"' -> lexString()
                    c.isLetter() || c == '_' -> lexIdent()
                    c.isDigit() -> lexNumber()
                    c in "=!<><>" -> lexCmp()
                    else -> throw RpcException(RpcErrors.INVALID_PREDICATE, "unexpected char '$c' at ${loc()}")
                }
            }
            out.add(Token(TokKind.EOF, "", i))
            return out
        }

        private fun emit(kind: TokKind, c: Char) = out.add(Token(kind, c.toString(), i))
        private fun loc() = i

        private fun lexString() {
            val start = i
            i++ // skip opening quote
            val sb = StringBuilder()
            while (i < src.length && src[i] != '"') {
                if (src[i] == '\\' && i + 1 < src.length) {
                    when (src[i + 1]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        else -> sb.append(src[i + 1])
                    }
                    i += 2
                } else {
                    sb.append(src[i]); i++
                }
            }
            if (i >= src.length) throw RpcException(RpcErrors.INVALID_PREDICATE, "unterminated string at $start")
            i++ // closing quote
            out.add(Token(TokKind.STRING, sb.toString(), start))
        }

        private fun lexIdent() {
            val start = i
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == ':')) i++
            val word = src.substring(start, i)
            val kind = when (word.uppercase()) {
                "AND" -> TokKind.AND
                "OR" -> TokKind.OR
                "NOT" -> TokKind.NOT
                "TRUE" -> TokKind.TRUE
                "FALSE" -> TokKind.FALSE
                "NULL" -> TokKind.NULL
                else -> TokKind.IDENT   // sum, count, be, inv, block, tick, item ids (in strings only)
            }
            out.add(Token(kind, word, start))
        }

        private fun lexNumber() {
            val start = i
            while (i < src.length && src[i].isDigit()) i++
            // Fractional part: consume a dot only if followed by a digit. A trailing dot
            // that isn't part of a fraction (e.g. in a path) must be left to the DOT lexer.
            if (i + 1 < src.length && src[i] == '.' && src[i + 1].isDigit()) {
                i++ // dot
                while (i < src.length && src[i].isDigit()) i++
            }
            out.add(Token(TokKind.NUMBER, src.substring(start, i), start))
        }

        private fun lexCmp() {
            val start = i
            val c = src[i]
            if (c == '!' && i + 1 < src.length && src[i + 1] == '=') {
                out.add(Token(TokKind.CMP_OP, "!=", start)); i += 2; return
            }
            if (c == '=' && i + 1 < src.length && src[i + 1] == '=') {
                out.add(Token(TokKind.CMP_OP, "==", start)); i += 2; return
            }
            if ((c == '<' || c == '>')) {
                if (i + 1 < src.length && src[i + 1] == '=') {
                    out.add(Token(TokKind.CMP_OP, "$c=", start)); i += 2; return
                }
                out.add(Token(TokKind.CMP_OP, c.toString(), start)); i++; return
            }
            throw RpcException(RpcErrors.INVALID_PREDICATE, "bad comparison operator at $start")
        }
    }

    // ---- parser (recursive descent) ----

    private class Parser(private val tokens: List<Token>) {
        private var p = 0

        fun hasNext() = peek().kind != TokKind.EOF
        internal fun peekDesc(): String = peek().text
        private fun peek() = tokens[p]
        private fun advance(): Token { val t = tokens[p]; p++; return t }

        private fun expect(kind: TokKind, what: String): Token {
            val t = peek()
            if (t.kind != kind) throw RpcException(RpcErrors.INVALID_PREDICATE, "expected $what but got '${t.text}' at ${t.pos}")
            return advance()
        }

        fun parseOr(): Node {
            var left = parseAnd()
            while (peek().kind == TokKind.OR) {
                advance()
                left = Node.Or(left, parseAnd())
            }
            return left
        }

        private fun parseAnd(): Node {
            var left = parseNot()
            while (peek().kind == TokKind.AND) {
                advance()
                left = Node.And(left, parseNot())
            }
            return left
        }

        private fun parseNot(): Node {
            if (peek().kind == TokKind.NOT) {
                advance()
                return Node.Not(parseNot())
            }
            return parseComparison()
        }

        private fun parseComparison(): Node {
            // A parenthesized boolean expression is allowed here so AND/OR subtrees
            // can appear where a comparison is expected.
            if (peek().kind == TokKind.LPAREN) {
                // Could be a grouped bool expr OR just a grouped arithmetic operand.
                // Decide by scanning the parenthesised span: if it contains a top-level
                // CMP_OP or a boolean keyword/AND/OR, treat as boolean group; otherwise as
                // arithmetic. Simpler: try parse as arithmetic; if the parsed node is a
                // bool-typed structural node we accept it, else it must be followed by CMP_OP.
                val grouped = parseGroupedOrArith()
                return if (peek().kind == TokKind.CMP_OP) {
                    val op = advance().text
                    Node.Comparison(op, grouped, parseArith())
                } else grouped
            }
            val left = parseArith()
            return if (peek().kind == TokKind.CMP_OP) {
                val op = advance().text
                Node.Comparison(op, left, parseArith())
            } else {
                // Bare expression in boolean context: rely on truthiness at eval time.
                // But a bare arithmetic value with no comparison is ambiguous — require a
                // comparison to avoid silent surprises.
                throw RpcException(RpcErrors.INVALID_PREDICATE,
                    "expected comparison operator after '${describe(left)}' but got '${peek().text}'")
            }
        }

        /**
         * Parse a parenthesised group. If the group contains a top-level AND/OR/NOT/CMP,
         * it's a boolean sub-predicate (parseOr). Otherwise it's a parenthesised arithmetic
         * factor. We look ahead: scan tokens until the matching RPAREN; if any of
         * AND/OR/NOT/CMP_OP appear at the top bracket level, treat as boolean.
         */
        private fun parseGroupedOrArith(): Node {
            expect(TokKind.LPAREN, "'('")
            val isBoolean = spanContainsBoolean()
            val inner = if (isBoolean) parseOr() else parseArith()
            expect(TokKind.RPAREN, "')'")
            return inner
        }

        private fun spanContainsBoolean(): Boolean {
            var depth = 1
            var q = p
            while (q < tokens.size && depth > 0) {
                when (tokens[q].kind) {
                    TokKind.LPAREN -> depth++
                    TokKind.RPAREN -> depth--
                    TokKind.AND, TokKind.OR, TokKind.NOT, TokKind.CMP_OP -> if (depth == 1) return true
                    TokKind.EOF -> return false
                    else -> {}
                }
                q++
            }
            return false
        }

        private fun parseArith(): Node {
            var left = parseTerm()
            while (peek().kind == TokKind.PLUS || peek().kind == TokKind.MINUS) {
                val op = advance().text
                left = Node.Arith(op, left, parseTerm())
            }
            return left
        }

        private fun parseTerm(): Node {
            var left = parseFactor()
            while (peek().kind == TokKind.STAR) {
                advance()
                left = Node.Arith("*", left, parseFactor())
            }
            return left
        }

        private fun parseFactor(): Node {
            val t = peek()
            return when (t.kind) {
                TokKind.MINUS -> { advance(); Node.Negate(parseFactor()) }
                TokKind.NUMBER -> {
                    advance()
                    val raw = t.text
                    val v: Number = if (raw.contains('.')) raw.toDouble() else raw.toLong()
                    Node.Literal(JsonPrimitive(v))
                }
                TokKind.STRING -> { advance(); Node.Literal(JsonPrimitive(t.text)) }
                TokKind.TRUE -> { advance(); Node.Literal(JsonPrimitive(true)) }
                TokKind.FALSE -> { advance(); Node.Literal(JsonPrimitive(false)) }
                TokKind.NULL -> { advance(); Node.Literal(JsonNull.INSTANCE) }
                TokKind.LPAREN -> parseGroupedOrArith()
                TokKind.IDENT -> when (t.text.lowercase()) {
                    "sum", "count" -> parseAggregate()
                    "be", "inv", "block", "tick" -> parseSourceRef()
                    else -> throw RpcException(RpcErrors.INVALID_PREDICATE,
                        "unknown identifier '${t.text}' at ${t.pos}")
                }
                else -> throw RpcException(RpcErrors.INVALID_PREDICATE,
                    "unexpected token '${t.text}' at ${t.pos}")
            }
        }

        private fun parseSourceRef(): Node {
            val source = advance().text.lowercase()
            var pos: Triple<Int, Int, Int>? = null
            if (peek().kind == TokKind.LBRACKET) {
                advance()
                val x = parseIntToken()
                expect(TokKind.COMMA, "','")
                val y = parseIntToken()
                expect(TokKind.COMMA, "','")
                val z = parseIntToken()
                expect(TokKind.RBRACKET, "']'")
                pos = Triple(x, y, z)
            }
            var path = ""
            if (peek().kind == TokKind.DOT) {
                advance()
                path = parsePathTail()
            }
            return Node.SourceRef(source, pos, path)
        }

        /**
         * Parse the path after the leading dot: a sequence of `.ident` and `[int]`.
         * Returns the reconstructed dotted path WITHOUT leading dot
         * (e.g. "Items.0.count" for ".Items.0.count").
         */
        private fun parsePathTail(): String {
            val parts = ArrayList<String>()
            // first segment must be an ident or '*'
            parts.add(readPathSegment())
            while (peek().kind == TokKind.DOT || peek().kind == TokKind.LBRACKET) {
                if (peek().kind == TokKind.DOT) advance()
                parts.add(readPathSegment())
            }
            return parts.joinToString(".")
        }

        private fun readPathSegment(): String {
            val t = peek()
            return when (t.kind) {
                TokKind.IDENT -> { advance(); t.text }
                TokKind.STAR -> { advance(); "*" }
                TokKind.NUMBER -> { advance(); t.text }   // numeric path segment like slot index in dotted form
                else -> throw RpcException(RpcErrors.INVALID_PREDICATE,
                    "expected path segment but got '${t.text}' at ${t.pos}")
            }
        }

        private fun parseAggregate(): Node {
            val fn = advance().text.lowercase()   // sum or count
            expect(TokKind.LPAREN, "'('")
            // inv POS . * . FIELD
            val srcTok = peek()
            if (srcTok.kind != TokKind.IDENT || srcTok.text.lowercase() != "inv") {
                throw RpcException(RpcErrors.INVALID_PREDICATE,
                    "aggregate must apply to inv[pos].*.field, got '${srcTok.text}' at ${srcTok.pos}")
            }
            advance() // consume 'inv'
            expect(TokKind.LBRACKET, "'['")
            val x = parseIntToken(); expect(TokKind.COMMA, "','")
            val y = parseIntToken(); expect(TokKind.COMMA, "','")
            val z = parseIntToken(); expect(TokKind.RBRACKET, "']'")
            expect(TokKind.DOT, "'.'")
            expect(TokKind.STAR, "'*'")
            expect(TokKind.DOT, "'.'")
            val field = readPathSegment()
            // allow further dotted sub-path (e.g. nbt.charge)
            val fullField = buildString {
                append(field)
                while (peek().kind == TokKind.DOT) {
                    advance(); append('.'); append(readPathSegment())
                }
            }
            expect(TokKind.RPAREN, "')'")
            return Node.Aggregate(fn, Triple(x, y, z), fullField)
        }

        private fun parseIntToken(): Int {
            val sign = if (peek().kind == TokKind.MINUS) { advance(); -1 } else 1
            val t = peek()
            if (t.kind != TokKind.NUMBER) throw RpcException(RpcErrors.INVALID_PREDICATE,
                "expected integer but got '${t.text}' at ${t.pos}")
            advance()
            return sign * t.text.toInt()
        }

        private fun describe(node: Node): String = when (node) {
            is Node.SourceRef -> "${node.source}${node.pos?.let { "[${it.first},${it.second},${it.third}]" } ?: ""}${if (node.path.isNotEmpty()) ".${node.path}" else ""}"
            else -> node.toString()
        }
    }

    // ---- evaluator ----

    private class Evaluator(
        private val resolveLeaf: (SourceRef) -> JsonElement?,
        private val resolveAggregate: (Aggregate) -> JsonElement,
    ) {
        fun evalBool(node: Node): Boolean = when (node) {
            is Node.True -> true
            is Node.Not -> !evalBool(node.inner)
            is Node.And -> evalBool(node.left) && evalBool(node.right)
            is Node.Or -> evalBool(node.left) || evalBool(node.right)
            is Node.Comparison -> evalComparison(node)
            else -> truthy(evalValue(node))
        }

        private fun evalValue(node: Node): JsonElement? = when (node) {
            is Node.Literal -> node.value
            is Node.SourceRef -> resolveLeaf(node)
            is Node.Aggregate -> resolveAggregate(node)
            is Node.Negate -> {
                val v = evalValue(node.inner) ?: return JsonNull.INSTANCE
                if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) JsonPrimitive(-v.asDouble)
                else throw RpcException(RpcErrors.INVALID_PREDICATE, "cannot negate non-number: $v")
            }
            is Node.Arith -> {
                val l = evalValue(node.left) ?: JsonNull.INSTANCE
                val r = evalValue(node.right) ?: JsonNull.INSTANCE
                if (!l.isJsonPrimitive || !l.asJsonPrimitive.isNumber || !r.isJsonPrimitive || !r.asJsonPrimitive.isNumber) {
                    throw RpcException(RpcErrors.INVALID_PREDICATE, "arithmetic requires numbers: $l ${node.op} $r")
                }
                val a = l.asDouble; val b = r.asDouble
                JsonPrimitive(when (node.op) { "+" -> a + b; "-" -> a - b; "*" -> a * b; else -> throw RpcException(RpcErrors.INVALID_PREDICATE, "bad arith op") })
            }
            // Boolean nodes used in value context: coerce to 1/0.
            is Node.And, is Node.Or, is Node.Not, is Node.Comparison ->
                JsonPrimitive(if (evalBool(node)) 1L else 0L)
            is Node.True -> JsonPrimitive(1L)
        }

        private fun evalComparison(node: Node.Comparison): Boolean {
            val l = evalValue(node.left)
            val r = evalValue(node.right)
            val op = node.op
            // null handling
            if (l == null || l.isJsonNull || r == null || r.isJsonNull) {
                return when (op) {
                    "==" -> (l == null || l.isJsonNull) && (r == null || r.isJsonNull)
                    "!=" -> !((l == null || l.isJsonNull) && (r == null || r.isJsonNull))
                    else -> false
                }
            }
            if (l.isJsonPrimitive && l.asJsonPrimitive.isNumber && r.isJsonPrimitive && r.asJsonPrimitive.isNumber) {
                val a = l.asDouble; val b = r.asDouble
                return when (op) { "==" -> a == b; "!=" -> a != b; "<" -> a < b; "<=" -> a <= b; ">" -> a > b; ">=" -> a >= b; else -> false }
            }
            if (l.isJsonPrimitive && l.asJsonPrimitive.isBoolean && r.isJsonPrimitive && r.asJsonPrimitive.isBoolean) {
                return when (op) { "==" -> l.asBoolean == r.asBoolean; "!=" -> l.asBoolean != r.asBoolean; else -> false }
            }
            if (l.isJsonPrimitive && l.asJsonPrimitive.isString && r.isJsonPrimitive && r.asJsonPrimitive.isString) {
                val a = l.asString; val b = r.asString
                return when (op) { "==" -> a == b; "!=" -> a != b; else -> false }
            }
            // Type mismatch
            return when (op) { "!=" -> true; else -> false }
        }

        private fun truthy(v: JsonElement?): Boolean {
            if (v == null || v.isJsonNull) return false
            if (!v.isJsonPrimitive) return true   // object/array: non-empty truthiness left to caller context; default true
            val p = v.asJsonPrimitive
            return when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asDouble != 0.0
                p.isString -> p.asString.isNotEmpty()
                else -> false
            }
        }
    }
}
