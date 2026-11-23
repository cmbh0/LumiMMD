package com.lumi.mmd.engine.mcp

import com.lumi.mmd.engine.util.Base64Lite
import com.lumi.mmd.engine.util.Json
import com.lumi.mmd.engine.util.Log
import com.lumi.mmd.engine.util.jObj
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 一个工具调用的结果。 */
class ToolResult(val text: String, val image: ByteArray? = null, val imageMime: String = "image/jpeg", val isError: Boolean = false)

/** MCP 服务对外提供的工具集。 */
interface McpTools {
    /** 工具清单：每项含 name / description / inputSchema。 */
    fun list(): List<Map<String, Any?>>
    fun call(name: String, args: Map<String, Any?>): ToolResult
    /** 给 AI 的总说明（坐标系、单位、常用流程）。 */
    val instructions: String
}

/**
 * 一个内置的 MCP（Model Context Protocol）服务端：让 AI 客户端通过局域网操作本应用。
 *
 * 纯 Java 套接字实现，不依赖任何库。按 MCP 规范做，新旧客户端都能连：
 *
 *  - **Streamable HTTP**（`POST /mcp`，现在的客户端都用这个），同一个地址同时支持两代协议：
 *    - 2026-07-28（无状态）：没有握手，每个请求在 `_meta` 和请求头里带协议版本，用 `server/discover` 查询服务信息；
 *    - 2025-11-25 / 2025-06-18 / 2025-03-26 / 2024-11-05：先 `initialize` 握手再干活。
 *    客户端怎么开口就按哪一代应答（规范里叫 dual-era）。服务本身不保存会话，所以不发 `Mcp-Session-Id`，
 *    应用重启之后客户端也不用重连。
 *  - **旧的 HTTP + SSE**（`GET /sse` 建立事件流，再往它给出的地址 POST 消息），给只会这一种的老客户端。
 *
 * 密钥两种给法都认：写在地址里（`/mcp/<密钥>`），或者放在请求头里（`Authorization: Bearer <密钥>`，地址用 `/mcp`）。
 * 所有的出错应答都是 JSON，状态码按规范给（401 / 403 / 404 / 405 …），客户端才看得懂是哪里不对。
 */
class McpServer(private val tools: McpTools, private val serverName: String, private val serverVersion: String) {
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "mcp-conn").also { it.isDaemon = true } }
    /** 旧的 HTTP+SSE 传输：会话号 → 等着从事件流发出去的消息。 */
    private val sessions = ConcurrentHashMap<String, LinkedBlockingQueue<String>>()
    /** 正连着的连接（停服务的时候一起关掉，免得旧连接拿着旧密钥继续用）。 */
    private val conns: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    @Volatile var port = 0
        private set
    @Volatile var token = ""
        private set
    private val calls = AtomicInteger()
    /** 累计处理的工具调用数（界面上显示"AI 正在操作"）。 */
    val callCount: Int get() = calls.get()
    @Volatile var lastCall = ""
        private set
    /** 每次工具调用之后回调（任意线程）。 */
    var onCall: ((name: String, ok: Boolean) -> Unit)? = null

    /** 最近处理的一条请求：方法名、服务端从收到到答完用了多久（毫秒）、什么时候（System.currentTimeMillis）。给界面看「慢在哪」。 */
    @Volatile var lastRequest = ""
        private set
    @Volatile var lastRequestMs = 0f
        private set
    @Volatile var lastRequestAt = 0L
        private set
    private val requests = AtomicInteger()
    /** 累计处理的请求数（握手、列工具、调工具都算）。 */
    val requestCount: Int get() = requests.get()
    /** 每处理完一条请求回调（任意线程）。 */
    var onRequest: ((method: String, ms: Float) -> Unit)? = null

    private fun noteRequest(method: String, startNanos: Long) {
        val ms = (System.nanoTime() - startNanos) / 1e6f
        lastRequest = method; lastRequestMs = ms; lastRequestAt = System.currentTimeMillis()
        requests.incrementAndGet()
        try { onRequest?.invoke(method, ms) } catch (_: Throwable) {}
    }

    /**
     * 工具清单在运行期间不变：只生成一次。握手和列工具是客户端连上来最先做的事，
     * 这两步不碰场景、不等渲染线程，应用在后台、渲染停着的时候也是立刻答。
     */
    private val toolList: List<Map<String, Any?>> by lazy { tools.list() }
    private val toolNames: Set<String> by lazy { toolList.mapTo(HashSet()) { it["name"] as String } }

    val isRunning: Boolean get() = socket?.isClosed == false

    /**
     * 启动。
     * @param preferredPort 想用的端口；被占用时依次往后试 20 个，都不行就让系统随便给一个
     * @param token 访问密钥；空串 = 不设密钥
     */
    @Synchronized
    fun start(preferredPort: Int, token: String) {
        stop()
        var ss: ServerSocket? = null
        for (p in preferredPort until preferredPort + 20) {
            try {
                ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(p), 32)
                break
            } catch (e: Exception) {
                try { ss?.close() } catch (_: Exception) {}
                ss = null
            }
        }
        if (ss == null) { ss = ServerSocket(); ss.bind(InetSocketAddress(0), 32) }
        socket = ss
        port = ss.localPort
        this.token = token
        val server = ss
        Thread({
            while (!server.isClosed) {
                try {
                    val c = server.accept()
                    pool.execute { handle(c, server) }
                } catch (e: SocketException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "接受连接出错", e)
                }
            }
        }, "mcp-accept").also { it.isDaemon = true }.start()
        Log.i(TAG, "MCP 服务已启动，端口 $port")
    }

    @Synchronized
    fun stop() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        for (q in sessions.values) q.offer(CLOSE)
        sessions.clear()
        for (c in conns.toList()) try { c.close() } catch (_: Exception) {}
        conns.clear()
    }

    // ------------------------------------------------------------------ HTTP

    private class Request(
        val method: String, val path: String, val query: String, val headers: Map<String, String>, val body: ByteArray,
        /** 这个请求答完之后连接还留不留着。 */
        val keepAlive: Boolean, val http11: Boolean,
    ) {
        fun header(name: String): String? = headers[name]
        fun queryParam(name: String): String? {
            for (p in query.split('&')) {
                val i = p.indexOf('=')
                if ((if (i < 0) p else p.substring(0, i)) == name) return try { java.net.URLDecoder.decode(if (i < 0) "" else p.substring(i + 1), "UTF-8") } catch (_: Exception) { null }
            }
            return null
        }
        val accept: String get() = headers["accept"]?.lowercase() ?: ""
    }

    private class HttpError(val status: Int, message: String) : Exception(message)

    /** JSON-RPC 层面的错误（会变成 error 应答）。 */
    private class RpcError(val code: Int, message: String, val data: Any? = null) : Exception(message)

    private fun readLine(ins: InputStream): String? {
        val sb = ByteArrayOutputStream(128)
        while (true) {
            val c = ins.read()
            if (c < 0) return if (sb.size() == 0) null else sb.toString("ISO-8859-1")
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.write(c)
            if (sb.size() > 16384) throw HttpError(431, "header line too long")
        }
        return sb.toString("ISO-8859-1")
    }

    private fun readFully(ins: InputStream, buf: ByteArray): Boolean {
        var got = 0
        while (got < buf.size) { val n = ins.read(buf, got, buf.size - got); if (n < 0) return false; got += n }
        return true
    }

    /** 读一个请求；连接已经关了（或者半路断了）返回 null。 */
    private fun readRequest(ins: InputStream, out: OutputStream): Request? {
        var line = readLine(ins) ?: return null
        while (line.isEmpty()) line = readLine(ins) ?: return null
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) throw HttpError(400, "malformed request line")
        val http11 = !(parts.getOrNull(2) ?: "HTTP/1.0").equals("HTTP/1.0", true)
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(ins) ?: return null
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) {
                val k = h.substring(0, i).trim().lowercase()
                val v = h.substring(i + 1).trim()
                val old = headers[k]
                headers[k] = if (old == null) v else "$old, $v"
            }
            if (headers.size > 200) throw HttpError(431, "too many headers")
        }
        val chunked = headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true
        val len = headers["content-length"]?.trim()?.toLongOrNull()
        if (len != null && (len < 0 || len > MAX_BODY)) throw HttpError(413, "request body too large")
        // curl 等客户端发大一点的请求体之前会先问一句「可以发吗」，不回答的话它要干等一秒
        if (headers["expect"]?.lowercase()?.contains("100-continue") == true && (chunked || (len ?: 0L) > 0L)) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
        var body = ByteArray(0)
        if (chunked) {
            val buf = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(ins) ?: return null
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw HttpError(400, "malformed chunk size")
                if (size == 0) { while (true) { val t = readLine(ins) ?: break; if (t.isEmpty()) break }; break }
                if (size < 0 || buf.size().toLong() + size > MAX_BODY) throw HttpError(413, "request body too large")
                val chunk = ByteArray(size)
                if (!readFully(ins, chunk)) return null
                buf.write(chunk)
                readLine(ins)
            }
            body = buf.toByteArray()
        } else if (len != null && len > 0) {
            body = ByteArray(len.toInt())
            if (!readFully(ins, body)) return null
        }
        val target = parts[1]
        // 请求目标也可能是完整地址（经过代理时）：只留路径
        val local = if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
            val s = target.indexOf('/', target.indexOf("://") + 3); if (s < 0) "/" else target.substring(s)
        } else target
        val q = local.indexOf('?')
        val conn = headers["connection"]?.lowercase() ?: ""
        val keep = if (http11) !conn.contains("close") else conn.contains("keep-alive")
        return Request(parts[0].uppercase(), if (q >= 0) local.substring(0, q) else local, if (q >= 0) local.substring(q + 1) else "", headers, body, keep, http11)
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
        406 -> "Not Acceptable"; 413 -> "Payload Too Large"; 431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"; 503 -> "Service Unavailable"
        else -> if (status < 400) "OK" else "Error"
    }

    /** 跨域请求（浏览器里的客户端）要用的应答头。 */
    private fun cors(req: Request?, sb: StringBuilder) {
        val origin = req?.header("origin")
        if (origin.isNullOrEmpty()) sb.append("Access-Control-Allow-Origin: *\r\n")
        else sb.append("Access-Control-Allow-Origin: ").append(origin).append("\r\nVary: Origin\r\n")
        sb.append("Access-Control-Expose-Headers: WWW-Authenticate\r\n")
    }

    private fun respond(out: OutputStream, req: Request?, status: Int, contentType: String?, body: ByteArray, extra: Map<String, String> = emptyMap()) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        if (status != 204) sb.append("Content-Length: ").append(body.size).append("\r\n")
        if (req?.keepAlive == true) sb.append("Connection: keep-alive\r\nKeep-Alive: timeout=").append(KEEP_ALIVE_HINT_S).append("\r\n")
        else sb.append("Connection: close\r\n")
        cors(req, sb)
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        val headBytes = sb.toString().toByteArray(Charsets.ISO_8859_1)
        if (req?.method == "HEAD" || status == 204 || body.isEmpty()) out.write(headBytes)
        else {
            val all = ByteArray(headBytes.size + body.size)
            System.arraycopy(headBytes, 0, all, 0, headBytes.size)
            System.arraycopy(body, 0, all, headBytes.size, body.size)
            out.write(all)
        }
        out.flush()
    }

    private fun respondJson(out: OutputStream, req: Request?, status: Int, json: String, extra: Map<String, String> = emptyMap()) =
        respond(out, req, status, "application/json", json.toByteArray(Charsets.UTF_8), extra)

    /** 传输层面的出错（地址不对、没带密钥 …）：一个小的 JSON，error 是给程序看的，message 是给人看的。 */
    private fun respondProblem(out: OutputStream, req: Request?, status: Int, error: String, message: String, extra: Map<String, String> = emptyMap()) =
        respondJson(out, req, status, Json.stringify(linkedMapOf("error" to error, "message" to message)), extra)

    private fun handle(c: Socket, server: ServerSocket) {
        conns.add(c)
        try {
            if (server.isClosed) return
            c.tcpNoDelay = true
            val ins = BufferedInputStream(c.getInputStream(), 16384)
            val out = c.getOutputStream()
            while (!server.isClosed) {
                c.soTimeout = IDLE_TIMEOUT_MS
                val req = try {
                    readRequest(ins, out)
                } catch (e: SocketTimeoutException) {
                    null
                } catch (e: HttpError) {
                    respondProblem(out, null, e.status, "bad_request", e.message ?: "bad request")
                    null
                } ?: return
                if (!serve(c, req, ins, out) || !req.keepAlive) return
            }
        } catch (e: Exception) {
            if (e !is SocketException && e !is java.io.IOException) Log.w(TAG, "处理请求出错：${e.message}")
        } finally {
            conns.remove(c)
            try { c.close() } catch (_: Exception) {}
        }
    }

    private enum class Auth { OK, MISSING, WRONG, WRONG_PATH }

    private fun same(a: String, b: String): Boolean = java.security.MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /**
     * 密钥对不对。两种给法都认：
     *  - 地址的最后一段：/mcp/<密钥>
     *  - 请求头：Authorization: Bearer <密钥>（或者 X-API-Key；实在只能填地址的客户端也可以写成 /mcp?token=<密钥>）
     */
    private fun authorize(req: Request, pathToken: String?): Auth {
        val t = token
        if (t.isEmpty()) return Auth.OK
        if (pathToken != null) return if (same(pathToken, t)) Auth.OK else Auth.WRONG_PATH
        val given = ArrayList<String>(2)
        req.header("authorization")?.trim()?.let { a ->
            given.add(if (a.length > 7 && a.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) a.substring(7).trim() else a)
        }
        req.header("x-api-key")?.trim()?.let { given.add(it) }
        for (k in arrayOf("token", "key", "api_key", "access_token")) req.queryParam(k)?.let { given.add(it) }
        if (given.isEmpty()) return Auth.MISSING
        return if (given.any { same(it, t) }) Auth.OK else Auth.WRONG
    }

    private fun deny(out: OutputStream, req: Request, auth: Auth) {
        if (auth == Auth.WRONG_PATH) {
            respondProblem(out, req, 404, "not_found", "Not found: the access key in the URL is wrong. Copy the complete address shown in the app. (地址里的密钥不对，请复制应用里显示的完整地址)")
        } else {
            val challenge = "Bearer realm=\"$serverName\"" + if (auth == Auth.WRONG) ", error=\"invalid_token\"" else ""
            respondProblem(out, req, 401, if (auth == Auth.WRONG) "invalid_token" else "unauthorized",
                "Unauthorized: this MCP server needs its access key. Either use the URL form /mcp/<key>, or send the header 'Authorization: Bearer <key>'. " +
                    "(需要密钥：用 /mcp/<密钥> 这样的地址，或者加请求头 Authorization: Bearer <密钥>)",
                mapOf("WWW-Authenticate" to challenge))
        }
    }

    /** 地址里的主机名（去掉端口、方括号），小写。 */
    private fun hostOf(authority: String): String {
        var h = authority.substringBefore('/').substringAfterLast('@').trim().lowercase()
        if (h.startsWith("[")) return h.substring(1).substringBefore(']')
        if (h.count { it == ':' } == 1) h = h.substringBefore(':')
        return h
    }

    /**
     * 请求的来源（浏览器发的 Origin 头）是不是本机 / 局域网里的页面。
     * 没有这个头（所有不是浏览器的客户端）算可信；外面网站上的页面不算，带着密钥也不行——
     * 这是规范要求的检查，防的是别的网页借用户的浏览器偷偷连过来（DNS rebinding）。
     */
    private fun localOrigin(origin: String?): Boolean {
        if (origin.isNullOrEmpty()) return true
        // "null"：本地文件页面、沙盒里的页面。设了密钥时放行（后面还要验密钥），没设密钥就不放
        if (origin == "null") return token.isNotEmpty()
        val sep = origin.indexOf("://")
        if (sep <= 0) return false
        val scheme = origin.substring(0, sep).lowercase()
        if (scheme != "http" && scheme != "https") return true     // 桌面应用自己的页面（vscode-webview://、app://、tauri:// …）
        val h = hostOf(origin.substring(sep + 3))
        if (h == "localhost" || h.endsWith(".localhost") || h == "::1" || h.startsWith("127.")) return true
        if (h.contains(':')) return h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")   // IPv6 的内网地址
        val p = h.split('.')
        if (p.size == 4 && p.all { it.toIntOrNull() != null }) {
            val a = p[0].toInt(); val b = p[1].toInt()
            return a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        return !h.contains('.') || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".internal")
    }

    /** 处理一个请求。返回 false = 这条连接不能再接着用了。 */
    private fun serve(c: Socket, req: Request, ins: InputStream, out: OutputStream): Boolean {
        val segs = req.path.split('/').filter { it.isNotEmpty() }
        val head = segs.getOrNull(0)?.lowercase() ?: ""
        val pathToken = segs.getOrNull(1)

        if (req.method == "OPTIONS") {                              // 浏览器的跨域预检
            respond(out, req, 204, null, ByteArray(0), linkedMapOf(
                "Access-Control-Allow-Methods" to "GET, POST, DELETE, OPTIONS",
                "Access-Control-Allow-Headers" to (req.header("access-control-request-headers") ?: CORS_HEADERS),
                "Access-Control-Allow-Private-Network" to "true",
                "Access-Control-Max-Age" to "600",
            ))
            return true
        }
        val get = req.method == "GET" || req.method == "HEAD"
        if ((head == "" || head == "health" || head == "healthz") && segs.size <= 1 && get) {
            respondJson(out, req, 200, Json.stringify(linkedMapOf(
                "name" to serverName, "version" to serverVersion, "status" to "ok",
                "mcp" to linkedMapOf("streamableHttp" to "/mcp", "sse" to "/sse", "websocket" to "/ws", "protocolVersions" to ALL_VERSIONS, "auth" to if (token.isEmpty()) "none" else "key"),
                "rest" to linkedMapOf("tools" to "/api/tools", "call" to "/api/tools/{name}", "openapi" to "/api/openapi.json"),
                "message" to "$serverName $serverVersion: MCP server is running. Put the complete address shown in the app (with the key) into your AI client. " +
                    "(MCP 服务正在运行，请把应用里显示的完整地址填到 AI 客户端里)",
            )))
            return true
        }

        if (!localOrigin(req.header("origin"))) {
            respondProblem(out, req, 403, "forbidden_origin",
                "Forbidden: this MCP server only accepts browser requests from pages on this machine or the local network (Origin: ${req.header("origin")}).")
            return true
        }

        // WebSocket：ws://地址/ws/密钥（也接受直接把 /mcp 的地址换成 ws:// 来连）
        val upgrade = req.header("upgrade")?.lowercase() == "websocket"
        if (head == "ws" || (upgrade && (head == "mcp" || head == ""))) {
            if (segs.size > 2) { respondProblem(out, req, 404, "not_found", "Not found: ${req.path}"); return true }
            val auth = authorize(req, pathToken)
            if (auth != Auth.OK) { deny(out, req, auth); return true }
            if (!upgrade || req.method != "GET" || req.header("sec-websocket-key").isNullOrEmpty()) {
                respondProblem(out, req, 426, "upgrade_required", "This is the MCP WebSocket endpoint: connect with ws://, one JSON-RPC message per text frame.",
                    mapOf("Upgrade" to "websocket", "Connection" to "Upgrade"))
                return true
            }
            serveWebSocket(c, req, ins, out)
            return false
        }
        if (head == "api") { serveApi(req, out, segs); return true }

        when (head) {
            "mcp", "sse", "" -> {
                if (segs.size > 2) { respondProblem(out, req, 404, "not_found", "Not found: ${req.path}"); return true }
                val auth = authorize(req, pathToken)
                if (auth != Auth.OK) { deny(out, req, auth); return true }
                when {
                    req.method == "POST" -> servePost(req, out)
                    get -> {
                        // /sse 是旧传输的事件流。/mcp 上的 GET：新客户端（带着协议版本头）想要的是「服务端主动推送」的流，
                        // 这里没有可推的东西，按规范回 405；没带版本头的多半是把 SSE 客户端指到了 /mcp，也给它事件流。
                        val wantsStream = req.accept.contains("text/event-stream") || head == "sse"
                        val legacySse = head == "sse" || (req.header("mcp-protocol-version") == null && req.header("mcp-session-id") == null)
                        if (req.method == "GET" && wantsStream && legacySse) {
                            serveSse(c, req, out, pathToken != null && token.isNotEmpty())
                            return false
                        }
                        respondProblem(out, req, 405, "method_not_allowed",
                            "This is an MCP endpoint (Streamable HTTP): send JSON-RPC requests with POST. It does not offer a server-to-client event stream.",
                            mapOf("Allow" to "POST, OPTIONS"))
                    }
                    // 没有会话，也就没有「结束会话」
                    else -> respondProblem(out, req, 405, "method_not_allowed", "Method ${req.method} is not supported here (this server keeps no sessions).", mapOf("Allow" to "POST, OPTIONS"))
                }
            }
            "messages", "message" -> {
                if (req.method != "POST") { respondProblem(out, req, 405, "method_not_allowed", "POST only.", mapOf("Allow" to "POST, OPTIONS")); return true }
                // 会话号是连上事件流（那时验过密钥）才拿得到的随机串，凭它就够了
                val q = sessions[req.queryParam("sessionId") ?: req.queryParam("session_id") ?: ""]
                if (q == null) { respondProblem(out, req, 404, "session_not_found", "Unknown or closed SSE session. Reconnect to the /sse endpoint."); return true }
                val parsed = try { Json.parse(bodyText(req)) } catch (e: Exception) {
                    respondJson(out, req, 400, Json.stringify(error(null, PARSE_ERROR, "Parse error: ${e.message}")))
                    return true
                }
                respond(out, req, 202, "text/plain", "Accepted".toByteArray())
                // 应答从事件流发回去；放到别的线程做，这条连接好接着收下一个请求
                pool.execute {
                    try { legacy(parsed)?.let { q.offer(Json.stringify(it)) } } catch (e: Throwable) { Log.w(TAG, "处理消息出错", e) }
                }
            }
            else -> respondProblem(out, req, 404, "not_found",
                // /.well-known/oauth-* 也走到这里：这个服务不用 OAuth，客户端看到 404 就知道了
                "Not found: ${req.path}. The MCP endpoint is /mcp (Streamable HTTP); /sse is the legacy HTTP+SSE transport; /ws is WebSocket; /api/tools is the plain REST form.")
        }
        return true
    }

    // ------------------------------------------------------------------ 其它接法：WebSocket / stdio / REST

    /** 一条（或一批）JSON-RPC 消息 → 应答；通知没有应答，返回 null。WebSocket 和 stdio 共用。 */
    private fun rpc(parsed: Any?): Any? =
        if (parsed is List<*>) {
            if (parsed.isEmpty()) error(null, INVALID_REQUEST, "Invalid Request: empty batch") else parsed.mapNotNull { legacy(it) }.ifEmpty { null }
        } else legacy(parsed)

    /**
     * 处理一条 JSON-RPC 消息的文本，返回应答的文本（通知返回 null）。
     * 给不走网络的接法用：stdio（见 [serveStdio]）或者别的进程内通道。握手方式同 initialize 那几版协议。
     */
    fun handleMessage(text: String): String? {
        val parsed = try { Json.parse(text.removePrefix("\uFEFF")) } catch (e: Exception) {
            return Json.stringify(error(null, PARSE_ERROR, "Parse error: ${e.message}"))
        }
        return rpc(parsed)?.let { Json.stringify(it) }
    }

    /**
     * MCP 的 stdio 传输：从 input 一行读一条 JSON-RPC 消息，应答一行一条写到 output。读到流结束就返回。
     * 调用方要保证别的输出（日志）不会写进 output。
     */
    fun serveStdio(input: InputStream, output: OutputStream) {
        val reader = input.bufferedReader(Charsets.UTF_8)
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isBlank()) continue
            val reply = try { handleMessage(line) } catch (e: Throwable) { Json.stringify(error(null, INTERNAL_ERROR, "Internal error: ${e.message}")) } ?: continue
            synchronized(output) { output.write((reply + "\n").toByteArray(Charsets.UTF_8)); output.flush() }
        }
    }

    /** WebSocket：握手之后，每个文本帧是一条 JSON-RPC 消息，应答也各用一个文本帧发回去。 */
    private fun serveWebSocket(c: Socket, req: Request, ins: InputStream, out: OutputStream) {
        val key = req.header("sec-websocket-key")!!.trim()
        val accept = Base64Lite.encode(java.security.MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.ISO_8859_1)))
        val sb = StringBuilder("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ").append(accept).append("\r\n")
        // 客户端点名要 mcp 这个子协议时照着回，没要就不带
        val protocols = req.header("sec-websocket-protocol")?.split(',')?.map { it.trim() } ?: emptyList()
        protocols.firstOrNull { it.equals("mcp", true) }?.let { sb.append("Sec-WebSocket-Protocol: ").append(it).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1)); out.flush()
        c.soTimeout = 0
        val message = java.io.ByteArrayOutputStream()
        val header = ByteArray(8)
        fun send(opcode: Int, data: ByteArray) {
            val h = java.io.ByteArrayOutputStream(data.size + 10)
            h.write(0x80 or opcode)
            when {
                data.size < 126 -> h.write(data.size)
                data.size < 65536 -> { h.write(126); h.write(data.size ushr 8); h.write(data.size and 255) }
                else -> { h.write(127); for (i in 7 downTo 0) h.write(((data.size.toLong() ushr (i * 8)) and 255).toInt()) }
            }
            h.write(data)
            synchronized(out) { out.write(h.toByteArray()); out.flush() }
        }
        while (true) {
            val h2 = ByteArray(2)
            if (!readFully(ins, h2)) return
            header[0] = h2[0]; header[1] = h2[1]
            val fin = (header[0].toInt() and 0x80) != 0
            val opcode = header[0].toInt() and 0x0F
            val masked = (header[1].toInt() and 0x80) != 0
            var len = (header[1].toInt() and 0x7F).toLong()
            if (len == 126L) { val b = ByteArray(2); if (!readFully(ins, b)) return; len = (((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255)).toLong() }
            else if (len == 127L) { val b = ByteArray(8); if (!readFully(ins, b)) return; len = 0; for (x in b) len = (len shl 8) or (x.toLong() and 255) }
            if (len < 0 || len > MAX_BODY || message.size() + len > MAX_BODY) { send(8, byteArrayOf(0x03, 0xF1.toByte())); return }      // 1009：消息太大
            val mask = ByteArray(4)
            if (masked && !readFully(ins, mask)) return
            val data = ByteArray(len.toInt())
            if (!readFully(ins, data)) return
            if (masked) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (opcode) {
                0x8 -> { try { send(8, if (data.size >= 2) data.copyOf(2) else ByteArray(0)) } catch (_: Exception) {}; return }
                0x9 -> send(0xA, data)
                0xA -> {}
                0x0, 0x1, 0x2 -> {
                    message.write(data)
                    if (!fin) continue
                    val text = String(message.toByteArray(), Charsets.UTF_8)
                    message.reset()
                    val reply = try { handleMessage(text) } catch (e: Throwable) { Json.stringify(error(null, INTERNAL_ERROR, "Internal error: ${e.message}")) }
                    if (reply != null) send(1, reply.toByteArray(Charsets.UTF_8))
                }
                else -> { send(8, byteArrayOf(0x03, 0xEA.toByte())); return }                                                            // 1002：协议错误
            }
        }
    }

    /**
     * 不讲 MCP 的客户端用的普通 HTTP 接口（任何能发 HTTP 的程序、OpenAPI 工具服务器、函数调用）：
     *   GET  /api/tools            工具清单，OpenAI 函数调用的格式（?format=mcp 给 MCP 的原样）
     *   POST /api/tools/<名字>     调用一个工具，请求体就是参数对象
     *   GET  /api/openapi.json     OpenAPI 3.1 描述（每个工具一个操作）
     * 密钥可以放在路径里（/api/<密钥>/tools）、Authorization: Bearer、X-API-Key 或 ?token=。
     */
    private fun serveApi(req: Request, out: OutputStream, segs: List<String>) {
        var rest = segs.drop(1)
        var pathToken: String? = null
        if (rest.isNotEmpty() && rest[0] != "tools" && rest[0] != "openapi.json") { pathToken = rest[0]; rest = rest.drop(1) }
        val auth = authorize(req, pathToken)
        if (auth != Auth.OK) { deny(out, req, auth); return }
        val get = req.method == "GET" || req.method == "HEAD"
        val what = rest.getOrNull(0) ?: ""
        when {
            what == "openapi.json" && rest.size == 1 -> {
                if (!get) { respondProblem(out, req, 405, "method_not_allowed", "GET only.", mapOf("Allow" to "GET, OPTIONS")); return }
                respondJson(out, req, 200, Json.stringify(openApi(req, pathToken)))
            }
            what == "tools" && rest.size == 1 -> {
                if (!get) { respondProblem(out, req, 405, "method_not_allowed", "GET lists the tools; POST /api/tools/{name} calls one.", mapOf("Allow" to "GET, OPTIONS")); return }
                val list = toolList
                val body: Any = if (req.queryParam("format") == "mcp") linkedMapOf("tools" to list)
                else linkedMapOf("tools" to list.map { t ->
                    linkedMapOf("type" to "function", "function" to linkedMapOf("name" to t["name"], "description" to t["description"], "parameters" to t["inputSchema"]))
                })
                respondJson(out, req, 200, Json.stringify(body))
            }
            what == "tools" && rest.size == 2 -> {
                if (req.method != "POST") { respondProblem(out, req, 405, "method_not_allowed", "POST the arguments as a JSON object.", mapOf("Allow" to "POST, OPTIONS")); return }
                val text = bodyText(req)
                val args: Any? = if (text.isBlank()) emptyMap<String, Any?>() else try { Json.parse(text) } catch (e: Exception) {
                    respondProblem(out, req, 400, "bad_json", "The request body is not valid JSON: ${e.message}"); return
                }
                if (args !is Map<*, *>) { respondProblem(out, req, 400, "bad_arguments", "The request body must be a JSON object with the tool arguments."); return }
                val name = try { java.net.URLDecoder.decode(rest[1].replace("+", "%2B"), "UTF-8") } catch (_: Exception) { rest[1] }
                val r = try { callTool(linkedMapOf("name" to name, "arguments" to args)) } catch (e: RpcError) {
                    respondProblem(out, req, 404, "unknown_tool", e.message ?: "Unknown tool"); return
                }
                val content = r["content"] as List<*>
                val reply = linkedMapOf<String, Any?>("ok" to (r["isError"] != true), "text" to (content[0] as Map<*, *>)["text"])
                (content.getOrNull(1) as? Map<*, *>)?.let { reply["image"] = linkedMapOf("mimeType" to it["mimeType"], "data" to it["data"]) }
                respondJson(out, req, 200, Json.stringify(reply))
            }
            else -> respondProblem(out, req, 404, "not_found", "Not found: ${req.path}. REST endpoints: GET /api/tools, POST /api/tools/{name}, GET /api/openapi.json.")
        }
    }

    private fun openApi(req: Request, pathToken: String?): Map<String, Any?> {
        val host = req.header("host") ?: "localhost:$port"
        val base = "http://$host/api" + (if (pathToken != null) "/$pathToken" else "")
        val paths = LinkedHashMap<String, Any?>()
        for (t in toolList) {
            val name = t["name"] as String
            val desc = t["description"] as? String ?: ""
            paths["/tools/$name"] = linkedMapOf("post" to linkedMapOf(
                "operationId" to name,
                "summary" to desc.substringBefore(". ").take(120),
                "description" to desc,
                "requestBody" to linkedMapOf("required" to false, "content" to linkedMapOf("application/json" to linkedMapOf("schema" to t["inputSchema"]))),
                "responses" to linkedMapOf("200" to linkedMapOf("description" to "The tool result. ok=false means the tool reported an error; read text for the reason.",
                    "content" to linkedMapOf("application/json" to linkedMapOf("schema" to linkedMapOf("\$ref" to "#/components/schemas/ToolResult"))))),
            ))
        }
        val doc = linkedMapOf<String, Any?>(
            "openapi" to "3.1.0",
            "info" to linkedMapOf("title" to serverName, "version" to serverVersion, "description" to tools.instructions),
            "servers" to listOf(linkedMapOf("url" to base)),
            "paths" to paths,
            "components" to linkedMapOf(
                "schemas" to linkedMapOf("ToolResult" to linkedMapOf("type" to "object", "required" to listOf("ok", "text"), "properties" to linkedMapOf(
                    "ok" to linkedMapOf("type" to "boolean"),
                    "text" to linkedMapOf("type" to "string"),
                    "image" to linkedMapOf("type" to "object", "properties" to linkedMapOf("mimeType" to linkedMapOf("type" to "string"), "data" to linkedMapOf("type" to "string", "description" to "base64")))))),
                "securitySchemes" to linkedMapOf("bearer" to linkedMapOf("type" to "http", "scheme" to "bearer")),
            ),
        )
        if (token.isNotEmpty() && pathToken == null) doc["security"] = listOf(linkedMapOf("bearer" to emptyList<String>()))
        return doc
    }

    private fun bodyText(req: Request): String = String(req.body, Charsets.UTF_8).removePrefix("\uFEFF")

    /** 旧的 HTTP+SSE 传输：一条一直开着的事件流，应答都从这里发回去。 */
    private fun serveSse(c: Socket, req: Request, out: OutputStream, tokenInPath: Boolean) {
        val sid = java.util.UUID.randomUUID().toString().replace("-", "")
        val q = LinkedBlockingQueue<String>()
        sessions[sid] = q
        val chunked = req.http11
        fun send(text: String) {
            val data = text.toByteArray(Charsets.UTF_8)
            if (chunked) {
                val head = (Integer.toHexString(data.size) + "\r\n").toByteArray(Charsets.ISO_8859_1)
                val all = ByteArray(head.size + data.size + 2)
                System.arraycopy(head, 0, all, 0, head.size)
                System.arraycopy(data, 0, all, head.size, data.size)
                all[all.size - 2] = '\r'.code.toByte(); all[all.size - 1] = '\n'.code.toByte()
                out.write(all)
            } else out.write(data)
            out.flush()
        }
        try {
            c.soTimeout = 0
            val sb = StringBuilder("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache, no-transform\r\nConnection: close\r\nX-Accel-Buffering: no\r\n")
            if (chunked) sb.append("Transfer-Encoding: chunked\r\n")
            cors(req, sb)
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            val endpoint = (if (tokenInPath) "/messages/$token" else "/messages") + "?sessionId=$sid"
            send("event: endpoint\ndata: $endpoint\n\n")
            while (isRunning) {
                val msg = q.poll(15, TimeUnit.SECONDS)
                if (msg === CLOSE) break
                send(if (msg == null) ": ping\n\n" else "event: message\ndata: $msg\n\n")
            }
            if (chunked) { out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); out.flush() }
        } catch (_: Exception) {
        } finally {
            sessions.remove(sid)
        }
    }

    // ------------------------------------------------------------------ Streamable HTTP

    private fun servePost(req: Request, out: OutputStream) {
        val parsed = try { Json.parse(bodyText(req)) } catch (e: Exception) {
            respondJson(out, req, 400, Json.stringify(error(null, PARSE_ERROR, "Parse error: ${e.message}")))
            return
        }
        when (parsed) {
            is List<*> -> {
                // 一次发一组消息：只有 2025-03-26 那一版有，照旧支持
                if (parsed.isEmpty()) { reply(req, out, 400, error(null, INVALID_REQUEST, "Invalid Request: empty batch")); return }
                val replies = parsed.mapNotNull { legacy(it) }
                if (replies.isEmpty()) respond(out, req, 202, null, ByteArray(0)) else reply(req, out, 200, replies)
            }
            is Map<*, *> -> {
                val o = parsed.jObj()!!
                val method = o["method"] as? String
                if (method == null) {
                    // 客户端对「服务端请求」的应答：我们从不发请求，收下即可
                    if (o.containsKey("result") || o.containsKey("error")) respond(out, req, 202, null, ByteArray(0))
                    else reply(req, out, 400, error(o["id"].takeIf { it is String || it is Number }, INVALID_REQUEST, "Invalid Request: not a JSON-RPC message"))
                    return
                }
                if (isModern(method, o, req)) {
                    val (status, body) = modern(method, o, req)
                    if (body == null) respond(out, req, status, null, ByteArray(0)) else reply(req, out, status, body)
                } else {
                    val r = legacy(o)
                    if (r == null) respond(out, req, 202, null, ByteArray(0))
                    else {
                        val code = (r["error"].jObj()?.get("code") as? Number)?.toInt()
                        reply(req, out, if (code == PARSE_ERROR || code == INVALID_REQUEST) 400 else 200, r)
                    }
                }
            }
            else -> reply(req, out, 400, error(null, INVALID_REQUEST, "Invalid Request: expected a JSON-RPC message object"))
        }
    }

    /** 发一个 JSON-RPC 应答。客户端只收事件流（Accept 里没有 application/json）时包成一条 SSE 消息。 */
    private fun reply(req: Request, out: OutputStream, status: Int, body: Any?) {
        val json = Json.stringify(body)
        val accept = req.accept
        val jsonOk = accept.isEmpty() || accept.contains("application/json") || accept.contains("*/*") || accept.contains("application/*")
        if (!jsonOk && accept.contains("text/event-stream") && status == 200) {
            respond(out, req, 200, "text/event-stream", "event: message\ndata: $json\n\n".toByteArray(Charsets.UTF_8), mapOf("Cache-Control" to "no-cache", "X-Accel-Buffering" to "no"))
        } else respondJson(out, req, status, json)
    }

    // ------------------------------------------------------------------ JSON-RPC

    private fun error(id: Any?, code: Int, message: String, data: Any? = null): LinkedHashMap<String, Any?> {
        val e = linkedMapOf<String, Any?>("code" to code, "message" to message)
        if (data != null) e["data"] = data
        return linkedMapOf("jsonrpc" to "2.0", "id" to id, "error" to e)
    }

    private fun serverInfo(): Map<String, Any?> = linkedMapOf("name" to serverName, "version" to serverVersion)

    /**
     * 这个请求是不是新一代协议（2026-07-28 起，无状态）的。
     * 新协议的请求在请求头 MCP-Protocol-Version 和 _meta 里带着版本；`initialize` 一定是旧协议的握手。
     */
    private fun isModern(method: String, o: Map<String, Any?>, req: Request): Boolean {
        if (method == "initialize") return false
        if (method == "server/discover") return true
        val hv = req.header("mcp-protocol-version")
        if (hv != null && hv !in LEGACY_VERSIONS) return true
        return o["params"].jObj()?.get("_meta").jObj()?.get(META_VERSION) != null
    }

    /** 请求头里镜像的值：含非 ASCII 字符时客户端会写成 =?base64?…?=。 */
    private fun headerValue(v: String): String {
        if (v.startsWith("=?base64?") && v.endsWith("?=") && v.length >= 11) {
            return try { String(Base64Lite.decode(v.substring(9, v.length - 2)), Charsets.UTF_8) } catch (_: Exception) { v }
        }
        return v
    }

    /**
     * 新协议（2026-07-28）的一个请求。返回 (HTTP 状态码, 应答)；应答为 null = 通知，不用回内容。
     * 检查的顺序和状态码照规范：_meta 缺字段 → -32602；请求头和正文对不上 → -32020；版本不支持 → -32022；
     * 没有这个方法 → -32601 + 404。
     */
    private fun modern(method: String, o: Map<String, Any?>, req: Request): Pair<Int, Map<String, Any?>?> {
        val t0 = System.nanoTime()
        try { return modernImpl(method, o, req) } finally { noteRequest(method, t0) }
    }

    private fun modernImpl(method: String, o: Map<String, Any?>, req: Request): Pair<Int, Map<String, Any?>?> {
        if (!o.containsKey("id")) return 202 to null
        val id = o["id"]
        if (!(id is String || (id is Number && id.toDouble() % 1.0 == 0.0))) return 400 to error(null, INVALID_REQUEST, "Invalid Request: id must be a string or an integer")
        if (o["jsonrpc"] != "2.0") return 400 to error(id, INVALID_REQUEST, "Invalid Request: jsonrpc must be \"2.0\"")
        val rawParams = o["params"]
        if (rawParams != null && rawParams !is Map<*, *>) return 400 to error(id, INVALID_PARAMS, "Invalid params: params must be an object")
        val params = rawParams.jObj() ?: emptyMap()
        val meta = params["_meta"].takeIf { it is Map<*, *> }.jObj()
        val version = meta?.get(META_VERSION) as? String
        if (version == null || meta[META_CAPABILITIES] !is Map<*, *>) {
            return 400 to error(id, INVALID_PARAMS, "Invalid params: every request must carry params._meta[\"$META_VERSION\"] (string) and params._meta[\"$META_CAPABILITIES\"] (object)")
        }
        val hv = req.header("mcp-protocol-version")
        if (hv != version) return 400 to error(id, HEADER_MISMATCH, "Header mismatch: the MCP-Protocol-Version header (${hv ?: "missing"}) must equal the protocol version in _meta ($version)")
        val hm = req.header("mcp-method")
        if (hm != method) return 400 to error(id, HEADER_MISMATCH, "Header mismatch: the Mcp-Method header (${hm ?: "missing"}) must equal the request method ($method)")
        if (method == "tools/call" || method == "resources/read" || method == "prompts/get") {
            val name = params[if (method == "resources/read") "uri" else "name"] as? String
            if (name != null) {
                val hn = req.header("mcp-name")
                if (hn == null || headerValue(hn) != name) return 400 to error(id, HEADER_MISMATCH, "Header mismatch: the Mcp-Name header (${hn ?: "missing"}) must equal the name in the request body ($name)")
            }
        }
        if (version !in MODERN_VERSIONS) {
            return 400 to error(id, UNSUPPORTED_VERSION, "Unsupported protocol version", linkedMapOf("supported" to ALL_VERSIONS, "requested" to version))
        }
        return try {
            val result = linkedMapOf<String, Any?>("resultType" to "complete")
            when (method) {
                "server/discover" -> {
                    result["supportedVersions"] = ALL_VERSIONS
                    result["capabilities"] = linkedMapOf("tools" to linkedMapOf<String, Any?>())
                    result["instructions"] = tools.instructions
                    result["ttlMs"] = LIST_TTL_MS
                    result["cacheScope"] = "private"
                }
                "tools/list" -> {
                    result["tools"] = toolList
                    result["ttlMs"] = LIST_TTL_MS
                    result["cacheScope"] = "private"
                }
                "tools/call" -> result.putAll(callTool(params))
                else -> return 404 to error(id, METHOD_NOT_FOUND, "Method not found: $method")
            }
            result["_meta"] = linkedMapOf("io.modelcontextprotocol/serverInfo" to serverInfo())
            200 to linkedMapOf("jsonrpc" to "2.0", "id" to id, "result" to result)
        } catch (e: RpcError) {
            (if (e.code == METHOD_NOT_FOUND) 404 else 400) to error(id, e.code, e.message ?: "error", e.data)
        } catch (e: Throwable) {
            Log.w(TAG, "处理 $method 出错", e)
            200 to error(id, INTERNAL_ERROR, "Internal error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 旧协议（2025-11-25 及以前，先 initialize 握手）的一条消息。返回应答；通知、客户端发来的应答返回 null。
     * 服务不保存会话：握手只是商量版本、交换信息。
     */
    private fun legacy(msg: Any?): Map<String, Any?>? {
        val t0 = System.nanoTime()
        try { return legacyImpl(msg) } finally { ((msg as? Map<*, *>)?.get("method") as? String)?.let { noteRequest(it, t0) } }
    }

    private fun legacyImpl(msg: Any?): Map<String, Any?>? {
        if (msg !is Map<*, *>) return error(null, INVALID_REQUEST, "Invalid Request: expected a JSON-RPC message object")
        val o = msg.jObj()!!
        val method = o["method"] as? String ?: return null
        val id = o["id"]
        val notification = id == null && (!o.containsKey("id") || method.startsWith("notifications/"))
        if (notification) return null
        if (!(id is String || id is Number)) return error(null, INVALID_REQUEST, "Invalid Request: id must be a string or a number")
        val params = o["params"].takeIf { it is Map<*, *> }.jObj() ?: emptyMap()
        return try {
            val result: Map<String, Any?> = when (method) {
                "initialize" -> {
                    // 客户端要的版本我们会就用它的，否则给出我们会的最新的旧版本，由客户端决定接不接受
                    val want = params["protocolVersion"] as? String
                    linkedMapOf(
                        "protocolVersion" to (if (want != null && want in LEGACY_VERSIONS) want else LEGACY_VERSIONS[0]),
                        "capabilities" to linkedMapOf("tools" to linkedMapOf("listChanged" to false)),
                        "serverInfo" to serverInfo(),
                        "instructions" to tools.instructions,
                    )
                }
                "ping" -> linkedMapOf()
                "tools/list" -> linkedMapOf("tools" to toolList)
                "tools/call" -> callTool(params)
                // 下面几样我们没有（握手时也没有声明），但有的客户端不看声明照样来问：给空清单，免得它当成连接出错
                "resources/list" -> linkedMapOf("resources" to emptyList<Any>())
                "resources/templates/list" -> linkedMapOf("resourceTemplates" to emptyList<Any>())
                "prompts/list" -> linkedMapOf("prompts" to emptyList<Any>())
                "logging/setLevel" -> linkedMapOf()
                else -> return error(id, METHOD_NOT_FOUND, "Method not found: $method")
            }
            linkedMapOf("jsonrpc" to "2.0", "id" to id, "result" to result)
        } catch (e: RpcError) {
            error(id, e.code, e.message ?: "error", e.data)
        } catch (e: Throwable) {
            Log.w(TAG, "处理 $method 出错", e)
            error(id, INTERNAL_ERROR, "Internal error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * tools/call。工具名不认识、参数不是对象 = 协议错误（-32602）；
     * 工具自己执行失败 = 正常应答里带 isError，让 AI 看到原因自己改。
     */
    private fun callTool(params: Map<String, Any?>): Map<String, Any?> {
        val name = params["name"] as? String ?: throw RpcError(INVALID_PARAMS, "Invalid params: missing tool name (params.name)")
        val args: Map<String, Any?> = when (val raw = params["arguments"]) {
            null -> emptyMap()
            is Map<*, *> -> raw.jObj()!!
            // 有的客户端把参数整个写成一段 JSON 文本
            is String -> if (raw.isBlank()) emptyMap() else (try { Json.parse(raw) } catch (_: Exception) { null }).takeIf { it is Map<*, *> }.jObj()
                ?: throw RpcError(INVALID_PARAMS, "Invalid params: arguments must be an object")
            else -> throw RpcError(INVALID_PARAMS, "Invalid params: arguments must be an object")
        }
        if (!toolNames.contains(name)) throw RpcError(INVALID_PARAMS, "Unknown tool: $name")
        val r = try {
            tools.call(name, args)
        } catch (e: Throwable) {
            Log.w(TAG, "工具 $name 出错", e)
            ToolResult("Error: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
        calls.incrementAndGet()
        lastCall = name
        try { onCall?.invoke(name, !r.isError) } catch (_: Throwable) {}
        val content = ArrayList<Map<String, Any?>>()
        content.add(linkedMapOf("type" to "text", "text" to r.text))
        if (r.image != null) content.add(linkedMapOf("type" to "image", "data" to Base64Lite.encode(r.image), "mimeType" to r.imageMime))
        return linkedMapOf("content" to content, "isError" to r.isError)
    }

    companion object {
        private const val TAG = "McpServer"
        private const val MAX_BODY = 32L * 1024 * 1024
        /** 连接空着多久就关掉；告诉客户端的时间留出余量，免得它正要复用时我们刚好关了。 */
        private const val IDLE_TIMEOUT_MS = 120_000
        private const val KEEP_ALIVE_HINT_S = 30
        /** 工具清单可以缓存多久（清单在运行期间不变）。 */
        private const val LIST_TTL_MS = 60_000

        /** 新一代（无状态、每个请求自带版本）的协议版本。 */
        private val MODERN_VERSIONS = listOf("2026-07-28")
        /** 先 initialize 握手的那几版，新的在前。 */
        private val LEGACY_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
        private val ALL_VERSIONS = MODERN_VERSIONS + LEGACY_VERSIONS

        private const val META_VERSION = "io.modelcontextprotocol/protocolVersion"
        private const val META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"

        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
        private const val INTERNAL_ERROR = -32603
        private const val HEADER_MISMATCH = -32020
        private const val UNSUPPORTED_VERSION = -32022

        private const val CLOSE = "\u0000close"
        private const val CORS_HEADERS = "Content-Type, Accept, Authorization, X-API-Key, MCP-Protocol-Version, Mcp-Method, Mcp-Name, Mcp-Session-Id, Last-Event-ID"

        /** 生成一个好念好抄的随机密钥。 */
        fun randomToken(length: Int = 8): String {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val r = java.security.SecureRandom()
            return String(CharArray(length) { chars[r.nextInt(chars.length)] })
        }

        /** 本机在局域网里的 IPv4 地址（Wi-Fi / 热点 / 有线）。 */
        fun localAddresses(): List<String> {
            val out = ArrayList<String>()
            try {
                val ifs = java.net.NetworkInterface.getNetworkInterfaces() ?: return out
                for (ni in ifs) {
                    try { if (!ni.isUp || ni.isLoopback) continue } catch (_: Exception) { continue }
                    for (a in ni.inetAddresses) {
                        if (a is java.net.Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) out.add(a.hostAddress ?: continue)
                    }
                }
            } catch (_: Exception) {
            }
            // 常见的局域网段排前面
            return out.sortedBy { if (it.startsWith("192.168.")) 0 else if (it.startsWith("10.") || it.startsWith("172.")) 1 else 2 }
        }
    }
}
