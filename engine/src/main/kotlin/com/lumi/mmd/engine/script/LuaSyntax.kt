package com.lumi.mmd.engine.script

/**
 * 写脚本的那个框用到的两样东西：给代码上颜色、输入时给出补全。
 * 都是纯文本运算，不依赖界面，所以放在引擎里（也好测）。
 */
object LuaSyntax {
    const val KEYWORD = 1
    const val STRING = 2
    const val NUMBER = 3
    const val COMMENT = 4
    /** 应用提供的东西：scene、fx、time 这些表，以及 print、after、tween 这些函数。 */
    const val API = 5
    /** function 后面的那个名字。 */
    const val DEFINE = 6

    val KEYWORDS: Set<String> = setOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in",
        "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while",
    )

    /** 应用里脚本能直接用的表，以及每张表里有什么（从接口说明里读出来，再加上 Lua 自带的）。带 ( 或 { 结尾的是要调用的。 */
    val TABLES: Map<String, List<String>> by lazy { buildTables() }

    /** 模型、骨骼、材质这些对象身上的方法（m:bone(…) 里冒号后面的名字），带着 ( 或 {。 */
    val METHODS: List<String> by lazy { buildMethods() }

    /** 对象身上能读写的属性（m.opacity 里点后面的名字）。 */
    val PROPERTIES: List<String> by lazy { buildProperties() }

    /** 不属于哪张表、直接写名字就能用的函数。 */
    val GLOBALS: List<String> = listOf(
        "print(", "after(", "every(", "cancel(", "tween(", "run(", "wait(", "require(",
        "pairs(", "ipairs(", "tostring(", "tonumber(", "type(", "select(", "pcall(", "error(", "assert(", "unpack(", "next(", "setmetatable(", "getmetatable(",
    )

    /** 应用会来调用的函数名（写 function 时提示）。 */
    val CALLBACKS: List<String> = listOf("init", "update", "on_button", "on_event", "on_exit")

    private val OBJECT_VARS = setOf("m", "b", "mat", "r", "e", "s", "t", "c", "g", "w", "sh", "rb")

    private fun buildTables(): Map<String, List<String>> {
        val names = listOf("time", "scene", "physics", "camera", "light", "post", "render", "floor", "fx", "canvas", "mesh", "sim", "ui", "input", "ease", "math")
        val out = LinkedHashMap<String, LinkedHashSet<String>>()
        for (n in names) out[n] = LinkedHashSet()
        val re = Regex("(?<![A-Za-z0-9_.:])([a-z]+)\\.([A-Za-z_][A-Za-z0-9_]*)(\\(|\\{)?")
        for (m in re.findAll(ScriptLibrary.REFERENCE)) {
            val set = out[m.groupValues[1]] ?: continue
            val name = m.groupValues[2]
            val call = m.groupValues[3]
            // 同一个名字既出现过带括号的也出现过不带的：以带括号的为准
            if (call.isNotEmpty()) { set.remove(name); set.add(name + call) } else if (set.none { it == name || it.startsWith("$name(") || it.startsWith("$name{") }) set.add(name)
        }
        // 接口说明里 ease 那一行是把名字并排写的
        out["ease"]!!.addAll(listOf("linear", "in_quad", "out_quad", "inout", "out_cubic", "out_back", "out_elastic", "out_bounce"))
        out["math"]!!.addAll(listOf("sin(", "cos(", "tan(", "abs(", "floor(", "ceil(", "max(", "min(", "sqrt(", "random(", "pi", "huge", "rad(", "deg(", "fmod(", "atan(", "atan2(", "exp(", "log(", "pow(", "sign(", "round("))
        out["string"] = linkedSetOf("format(", "sub(", "len(", "find(", "gsub(", "rep(", "upper(", "lower(", "byte(", "char(", "match(", "gmatch(")
        out["table"] = linkedSetOf("insert(", "remove(", "concat(", "sort(", "unpack(")
        out["os"] = linkedSetOf("time(", "clock(", "date(")
        return out.mapValues { it.value.toList() }
    }

    private fun buildMethods(): List<String> {
        val set = LinkedHashSet<String>()
        for (m in Regex("(?<![A-Za-z0-9_])(?:m|b|mat|r|e|s|t|c|g|w|sh|rb):([a-z_][A-Za-z0-9_]*)(\\(|\\{)").findAll(ScriptLibrary.REFERENCE)) {
            val name = m.groupValues[1]
            // 同一个名字（比如 set）在不同对象上既有 ( 也有 {：先出现的算数
            if (set.none { it.dropLast(1) == name }) set.add(name + m.groupValues[2])
        }
        return set.toList()
    }

    private fun buildProperties(): List<String> {
        val set = LinkedHashSet<String>()
        // 对象的方法都是用冒号调用的，所以点后面的一律是属性（说明里属性后面常带一个括号写的注释，不能当成函数）
        for (m in Regex("(?<![A-Za-z0-9_])(m|b|mat|r|e|s|t|c|g|w|sh|rb)\\.([a-z_][A-Za-z0-9_]*)(?![A-Za-z0-9_])").findAll(ScriptLibrary.REFERENCE)) {
            if (m.groupValues[1] in OBJECT_VARS) set.add(m.groupValues[2])
        }
        return set.toList()
    }

    private fun isWord(c: Char): Boolean = c == '_' || c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c.code > 127
    private fun isWordStart(c: Char): Boolean = c == '_' || c in 'a'..'z' || c in 'A'..'Z' || c.code > 127

    /** 从 i 处的 [ 开始，如果是长括号（[[ 或 [==[）的开头，返回里面有几个等号；不是则返回 -1。 */
    private fun longBracket(s: String, i: Int): Int {
        var j = i + 1
        var eq = 0
        while (j < s.length && s[j] == '=') { eq++; j++ }
        return if (j < s.length && s[j] == '[') eq else -1
    }

    /** 长括号的内容到哪结束（返回结束符之后的位置；没有结束符就到文本末尾）。 */
    private fun longEnd(s: String, from: Int, eq: Int): Int {
        val close = "]" + "=".repeat(eq) + "]"
        val k = s.indexOf(close, from)
        return if (k < 0) s.length else k + close.length
    }

    /**
     * 把代码切成要上色的小段。
     * @return 三个一组：开始位置、结束位置（不含）、种类（[KEYWORD] 等）。普通的标识符和符号不在里面。
     */
    fun highlight(text: String): IntArray {
        val out = ArrayList<Int>(256)
        val n = text.length
        var i = 0
        var afterFunction = false
        while (i < n) {
            val c = text[i]
            when {
                c == '-' && i + 1 < n && text[i + 1] == '-' -> {
                    val eq = if (i + 2 < n && text[i + 2] == '[') longBracket(text, i + 2) else -1
                    val end = if (eq >= 0) longEnd(text, i + 4 + eq, eq) else text.indexOf('\n', i).let { if (it < 0) n else it }
                    out.add(i); out.add(end); out.add(COMMENT)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && text[j] != c && text[j] != '\n') { if (text[j] == '\\' && j + 1 < n) j++; j++ }
                    val end = if (j < n && text[j] == c) j + 1 else j
                    out.add(i); out.add(end); out.add(STRING)
                    i = end
                }
                c == '[' && longBracket(text, i) >= 0 -> {
                    val eq = longBracket(text, i)
                    val end = longEnd(text, i + 2 + eq, eq)
                    out.add(i); out.add(end); out.add(STRING)
                    i = end
                }
                c in '0'..'9' || (c == '.' && i + 1 < n && text[i + 1] in '0'..'9' && (i == 0 || !isWord(text[i - 1]))) -> {
                    var j = i
                    if (c == '0' && j + 1 < n && (text[j + 1] == 'x' || text[j + 1] == 'X')) {
                        j += 2
                        while (j < n && (text[j] in '0'..'9' || text[j] in 'a'..'f' || text[j] in 'A'..'F')) j++
                    } else {
                        // 两个点是字符串连接（1 .. 2），不算进数字里
                        while (j < n && (text[j] in '0'..'9' || (text[j] == '.' && !(j + 1 < n && text[j + 1] == '.')))) j++
                        if (j < n && (text[j] == 'e' || text[j] == 'E')) {
                            var k = j + 1
                            if (k < n && (text[k] == '+' || text[k] == '-')) k++
                            if (k < n && text[k] in '0'..'9') { j = k; while (j < n && text[j] in '0'..'9') j++ }
                        }
                    }
                    out.add(i); out.add(j); out.add(NUMBER)
                    i = j
                }
                isWordStart(c) -> {
                    var j = i + 1
                    while (j < n && isWord(text[j])) j++
                    val w = text.substring(i, j)
                    val prev = if (i > 0) text[i - 1] else ' '
                    val member = prev == '.' || prev == ':'
                    when {
                        w in KEYWORDS && !member -> { out.add(i); out.add(j); out.add(KEYWORD); afterFunction = w == "function" }
                        afterFunction -> { out.add(i); out.add(j); out.add(DEFINE); afterFunction = false }
                        !member && (TABLES.containsKey(w) || GLOBAL_NAMES.contains(w)) -> { out.add(i); out.add(j); out.add(API) }
                    }
                    if (w !in KEYWORDS) afterFunction = false
                    i = j
                }
                else -> {
                    if (c != ' ' && c != '\t' && c != '\n' && c != '\r') afterFunction = false
                    i++
                }
            }
        }
        return out.toIntArray()
    }

    private val GLOBAL_NAMES: Set<String> by lazy { GLOBALS.map { it.trimEnd('(') }.toSet() }

    /** 光标是不是落在字符串或者注释里面（那里不给补全）。 */
    private fun insideTextOrComment(text: String, cursor: Int): Boolean {
        // 只看光标之前的部分就够了：没闭合的字符串 / 注释会一直延伸到这段文本的末尾
        val head = if (cursor < text.length) text.substring(0, cursor) else text
        val spans = highlight(head)
        var i = spans.size - 3
        while (i >= 0) {
            val kind = spans[i + 2]
            if (kind == STRING || kind == COMMENT) {
                val s = spans[i]; val e = spans[i + 1]
                if (e < cursor) return false
                if (kind == COMMENT) return true
                // 字符串：结束位置正好在光标处时，要看它是不是已经闭合了
                val q = head[s]
                val closed = if (q == '[') head.endsWith("]") && e - s >= 4 else e - s >= 2 && head[e - 1] == q && !(e - s >= 3 && head[e - 2] == '\\')
                return !closed
            }
            if (spans[i + 1] < cursor) return false
            i -= 3
        }
        return false
    }

    /** 一条补全。 */
    class Suggestion(
        /** 显示在补全条上的字。 */
        val label: String,
        /** 点了之后填进去的字（替换掉正在输入的那一截）。 */
        val insert: String,
        /** 填完之后光标从末尾往回退几个字符（比如停在括号中间）。 */
        val cursorBack: Int = 0,
    )

    /** [start, end) 是正在输入的那个词，点了补全就把它换掉。 */
    class Completion(val start: Int, val end: Int, val items: List<Suggestion>)

    private fun callable(name: String): Suggestion = when {
        name.endsWith("(") -> Suggestion(name.dropLast(1), name + ")", 1)
        name.endsWith("{") -> Suggestion(name.dropLast(1), name + "}", 1)
        else -> Suggestion(name, name)
    }

    /**
     * 光标处可以补全成什么。
     *  - 表名后面的点（scene.、fx.、math.）：这张表里的东西；
     *  - 别的名字后面的点：对象的属性（opacity、layer…）；冒号后面：对象的方法（bone、rotate…）；
     *  - 光秃秃的一个词：应用的表和函数、Lua 关键字、这份代码里出现过的名字，以及几个常用的整段写法。
     * 在字符串、注释里，或者没有可补的，返回 null。
     */
    fun complete(text: String, cursor: Int, limit: Int = 16): Completion? {
        if (cursor < 0 || cursor > text.length) return null
        var start = cursor
        while (start > 0 && isWord(text[start - 1])) start--
        // 光标在一个词的中间：不补（不然会把后半截弄乱）
        if (cursor < text.length && isWord(text[cursor])) return null
        val prefix = text.substring(start, cursor)
        if (prefix.isNotEmpty() && prefix[0] in '0'..'9') return null
        if (insideTextOrComment(text, cursor)) return null
        val sep = if (start > 0) text[start - 1] else ' '
        val items = ArrayList<Suggestion>()
        fun matches(name: String) = name.startsWith(prefix, ignoreCase = true) && name.trimEnd('(', '{') != prefix
        if (sep == '.' || sep == ':') {
            // 两个点（..）是字符串连接，不是取成员
            if (sep == '.' && start >= 2 && text[start - 2] == '.') return null
            var os = start - 1
            while (os > 0 && isWord(text[os - 1])) os--
            val owner = text.substring(os, start - 1)
            if (owner.isEmpty() && !(start >= 2 && (text[start - 2] == ')' || text[start - 2] == ']'))) return null
            if (owner.isNotEmpty() && owner[0] in '0'..'9') return null
            val table = if (sep == '.' && (os == 0 || (text[os - 1] != '.' && text[os - 1] != ':'))) TABLES[owner] else null
            val pool: List<String> = when {
                table != null -> table
                sep == ':' -> METHODS
                else -> PROPERTIES
            }
            for (name in pool) if (matches(name)) items.add(callable(name))
        } else {
            if (prefix.isEmpty()) return null
            // 当前这一行的缩进：整段写法里换行之后要接着用
            var ls = start
            while (ls > 0 && text[ls - 1] != '\n') ls--
            var le = ls
            while (le < start && (text[le] == ' ' || text[le] == '\t')) le++
            val ind = text.substring(ls, le)
            val lineStart = le == start
            if (lineStart) {
                val body = "\n$ind    \n${ind}end"
                val snippets = listOf(
                    Suggestion("function update(dt) … end", "function update(dt)$body", "\n${ind}end".length),
                    Suggestion("function init() … end", "function init()$body", "\n${ind}end".length),
                    Suggestion("function on_button(id, down) … end", "function on_button(id, down)$body", "\n${ind}end".length),
                    Suggestion("for i = 1, n do … end", "for i = 1, 10 do$body", "\n${ind}end".length),
                    Suggestion("for _, v in ipairs(t) do … end", "for _, v in ipairs() do$body", ") do$body".length),
                    Suggestion("if … then … end", "if  then$body", " then$body".length),
                    Suggestion("local m = scene.main()", "local m = scene.main()"),
                    Suggestion("while … do … end", "while  do$body", " do$body".length),
                )
                for (sn in snippets) if (sn.insert.startsWith(prefix) && sn.insert != prefix) items.add(sn)
            }
            for (name in TABLES.keys) if (matches(name)) items.add(Suggestion(name, name))
            for (name in GLOBALS) if (matches(name)) items.add(callable(name))
            for (name in KEYWORDS) if (matches(name)) items.add(Suggestion(name, name))
            // 这份代码里出现过的名字（变量、函数）
            val seen = HashSet<String>()
            for (it in items) seen.add(it.label)
            val spans = highlight(text)
            var si = 0
            var i = 0
            val n = text.length
            var found = 0
            while (i < n && found < 40) {
                while (si < spans.size && spans[si + 1] <= i) si += 3
                if (si < spans.size && spans[si] <= i && (spans[si + 2] == STRING || spans[si + 2] == COMMENT || spans[si + 2] == NUMBER)) { i = spans[si + 1]; continue }
                if (isWordStart(text[i]) && (i == 0 || !isWord(text[i - 1]))) {
                    var j = i + 1
                    while (j < n && isWord(text[j])) j++
                    if (i != start && j - i >= 2) {
                        val w = text.substring(i, j)
                        val member = i > 0 && (text[i - 1] == '.' || text[i - 1] == ':')
                        if (!member && w !in KEYWORDS && matches(w) && seen.add(w)) { items.add(Suggestion(w, w)); found++ }
                    }
                    i = j
                } else i++
            }
            if (prefix.startsWith("on_") || lineStart) for (cb in CALLBACKS) if (matches(cb) && seen.add(cb)) items.add(Suggestion(cb, cb))
        }
        if (items.isEmpty()) return null
        // 大小写完全对得上的排前面，其余保持原来的顺序
        val sorted = items.sortedBy { if (it.insert.startsWith(prefix)) 0 else 1 }
        return Completion(start, cursor, if (sorted.size > limit) sorted.subList(0, limit) else sorted)
    }

    /**
     * 刚在 cursor 处敲了回车（cursor 指向换行符之后）：新的一行开头该补多少缩进。
     * 跟上一行对齐；上一行是 then / do / else / function(…) / repeat / { 结尾的，再多缩进一层。
     */
    fun newlineIndent(text: String, cursor: Int): String {
        if (cursor <= 0 || cursor > text.length || text[cursor - 1] != '\n') return ""
        val lineEnd = cursor - 1
        var ls = lineEnd
        while (ls > 0 && text[ls - 1] != '\n') ls--
        val line = text.substring(ls, lineEnd)
        var k = 0
        while (k < line.length && (line[k] == ' ' || line[k] == '\t')) k++
        val ind = line.substring(0, k)
        // 去掉行尾的注释再看
        val spans = highlight(line)
        var codeEnd = line.length
        var i = 0
        while (i < spans.size) { if (spans[i + 2] == COMMENT) { codeEnd = spans[i]; break }; i += 3 }
        val code = line.substring(0, codeEnd).trimEnd()
        val last = Regex("[A-Za-z_]+$").find(code)?.value
        val opens = code.endsWith("{") || code.endsWith("(") || last == "then" || last == "do" || last == "else" || last == "repeat" ||
            (code.endsWith(")") && Regex("(^|[^A-Za-z0-9_])function\\b[^()]*\\([^()]*\\)$").containsMatchIn(code))
        return if (opens) "$ind    " else ind
    }
}
