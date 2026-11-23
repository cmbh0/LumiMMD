package com.lumi.mmd.engine

import com.lumi.mmd.engine.mcp.McpHost
import com.lumi.mmd.engine.mcp.McpServer
import com.lumi.mmd.engine.mcp.McpTools
import com.lumi.mmd.engine.mcp.SceneTools
import com.lumi.mmd.engine.mcp.ToolResult
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.render.RenderSettings
import com.lumi.mmd.engine.scene.Editor
import com.lumi.mmd.engine.scene.MeshSession
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.scene.Scene
import com.lumi.mmd.engine.scene.SceneRuntime
import com.lumi.mmd.engine.script.Script
import com.lumi.mmd.engine.script.ScriptCallbacks
import com.lumi.mmd.engine.util.Json
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MCP 服务按规范应答：真的开一个端口，用原始的 HTTP 请求去问。
 * 两代协议（2026-07-28 无状态的、以前先握手的）、旧的 SSE 传输、密钥的两种给法、出错时的状态码，
 * 还有工具参数表是不是各家客户端都收的那种写法。
 */
class McpServerTest {
    private class FakeTools : McpTools {
        override val instructions = "test instructions"
        override fun list(): List<Map<String, Any?>> = listOf(
            linkedMapOf("name" to "echo", "description" to "Echo the text back.", "inputSchema" to linkedMapOf("type" to "object",
                "properties" to linkedMapOf("text" to linkedMapOf("type" to "string", "description" to "Text.")), "required" to listOf("text"))),
            linkedMapOf("name" to "boom", "description" to "Always fails.", "inputSchema" to linkedMapOf("type" to "object", "properties" to linkedMapOf<String, Any?>())),
            linkedMapOf("name" to "画像", "description" to "Returns a picture.", "inputSchema" to linkedMapOf("type" to "object", "properties" to linkedMapOf<String, Any?>())),
        )
        override fun call(name: String, args: Map<String, Any?>): ToolResult = when (name) {
            "echo" -> ToolResult("echo: ${args["text"]}")
            "boom" -> throw IllegalStateException("it broke")
            "画像" -> ToolResult("picture", byteArrayOf(1, 2, 3), "image/png")
            else -> ToolResult("Unknown tool: $name", isError = true)
        }
    }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: String) {
        val json: Map<String, Any?> get() = Json.parse(body) as Map<String, Any?>
        val result: Map<String, Any?> get() = json["result"] as? Map<String, Any?> ?: error("没有 result：$status $body")
        val errorCode: Int get() = ((json["error"] as? Map<String, Any?> ?: error("没有 error：$status $body"))["code"] as Number).toInt()
    }

    private val server = McpServer(FakeTools(), "test-server", "1.2.3")
    private val key = "abcd2345"

    @Before fun start() { server.start(18765, key) }
    @After fun stop() { server.stop() }

    private fun readLine(ins: InputStream): String {
        val sb = ByteArrayOutputStream()
        while (true) { val c = ins.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.write(c) }
        return sb.toString("UTF-8")
    }

    /** 读一个应答（按 Content-Length），连接留着。 */
    private fun readReply(ins: InputStream): Reply {
        val status = readLine(ins).split(' ')[1].toInt()
        val headers = HashMap<String, String>()
        while (true) { val h = readLine(ins); if (h.isEmpty()) break; headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim() }
        val len = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(len)
        var got = 0
        while (got < len) { val n = ins.read(body, got, len - got); if (n < 0) break; got += n }
        return Reply(status, headers, String(body, Charsets.UTF_8))
    }

    private fun writeRequest(s: Socket, method: String, path: String, body: String?, headers: Map<String, String>) {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n")
        if (bytes != null) sb.append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
        for ((k, v) in headers) sb.append("$k: $v\r\n")
        sb.append("\r\n")
        s.getOutputStream().write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (bytes != null) s.getOutputStream().write(bytes)
        s.getOutputStream().flush()
    }

    private fun http(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): Reply =
        Socket("127.0.0.1", server.port).use { s ->
            s.soTimeout = 10000
            writeRequest(s, method, path, body, headers)
            readReply(BufferedInputStream(s.getInputStream()))
        }

    private fun post(body: String, path: String = "/mcp/$key", headers: Map<String, String> = emptyMap()) =
        http("POST", path, body, mapOf("Accept" to "application/json, text/event-stream") + headers)

    private val meta = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}"

    /** 新协议的一个请求：版本、方法、名字在请求头里再写一遍。 */
    private fun modern(method: String, params: String = "", name: String? = null, id: String = "1", headers: Map<String, String>? = null): Reply {
        val h = headers ?: (mapOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to method) + (if (name != null) mapOf("Mcp-Name" to name) else emptyMap()))
        return post("{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"$method\",\"params\":{$params${if (params.isEmpty()) "" else ","}$meta}}", headers = h)
    }

    // ------------------------------------------------------------------ 先握手的那几版

    @Test fun handshakeNegotiatesEveryLegacyVersion() {
        for (v in listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")) {
            val r = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$v","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
            assertEquals(200, r.status)
            assertEquals("application/json", r.headers["content-type"])
            assertEquals(v, r.result["protocolVersion"])
            assertEquals("test-server", (r.result["serverInfo"] as Map<*, *>)["name"])
            assertEquals("1.2.3", (r.result["serverInfo"] as Map<*, *>)["version"])
            assertEquals("test instructions", r.result["instructions"])
            assertNotNull((r.result["capabilities"] as Map<*, *>)["tools"])
            // 服务不保存会话：不发会话号，客户端也就不会在应用重启后拿着失效的会话号被拒绝
            assertNull(r.headers["mcp-session-id"])
        }
        // 不认识的版本：给出我们会的最新的握手版本，由客户端决定
        val r = post("""{"jsonrpc":"2.0","id":"x","method":"initialize","params":{"protocolVersion":"1999-01-01","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
        assertEquals("2025-11-25", r.result["protocolVersion"])
        assertEquals("x", r.json["id"])
    }

    @Test fun legacyRequestsAndNotifications() {
        assertEquals(202, post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)
        assertEquals(200, post("""{"jsonrpc":"2.0","id":2,"method":"ping"}""", headers = mapOf("MCP-Protocol-Version" to "2025-06-18")).status)

        val list = post("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")
        val tools = list.result["tools"] as List<*>
        assertEquals(listOf("echo", "boom", "画像"), tools.map { (it as Map<*, *>)["name"] })
        assertFalse("旧协议的应答里不该有新协议的字段", list.result.containsKey("resultType"))

        val call = post("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"echo","arguments":{"text":"你好"}}}""")
        assertEquals(false, call.result["isError"])
        assertEquals("echo: 你好", ((call.result["content"] as List<*>)[0] as Map<*, *>)["text"])
        assertEquals(1, server.callCount)
        assertEquals("echo", server.lastCall)

        // 工具自己出错：正常应答 + isError，AI 才看得到原因
        val boom = post("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"boom"}}""")
        assertEquals(200, boom.status)
        assertEquals(true, boom.result["isError"])
        assertTrue(boom.body, boom.body.contains("it broke"))

        // 图片
        val pic = post("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"画像","arguments":{}}}""")
        val image = (pic.result["content"] as List<*>)[1] as Map<*, *>
        assertEquals("image", image["type"]); assertEquals("image/png", image["mimeType"]); assertEquals("AQID", image["data"])

        // 没有这个工具、没有这个方法：协议错误
        assertEquals(-32602, post("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"nope","arguments":{}}}""").errorCode)
        assertEquals(-32601, post("""{"jsonrpc":"2.0","id":8,"method":"no/such"}""").errorCode)
        // 请求体不是 JSON
        val bad = post("{oops")
        assertEquals(400, bad.status); assertEquals(-32700, bad.errorCode); assertNull(bad.json["id"])
        // 参数整个写成一段 JSON 文本的客户端也认
        assertEquals("echo: a", ((post("""{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"echo","arguments":"{\"text\":\"a\"}"}}""").result["content"] as List<*>)[0] as Map<*, *>)["text"])
        // 一次发一组（2025-03-26）
        val batch = post("""[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"},{"jsonrpc":"2.0","id":2,"method":"ping"}]""")
        assertEquals(2, (Json.parse(batch.body) as List<*>).size)
    }

    // ------------------------------------------------------------------ 2026-07-28（无状态）

    /** 每条请求都计时：应用里靠这个区分"是我们慢"还是"请求在路上被系统拦住了"；握手和工具清单必须是毫秒级的。 */
    @Test fun requestsAreTimedAndTheToolListIsInstant() {
        val seen = ArrayList<String>()
        server.onRequest = { method, _ -> synchronized(seen) { seen.add(method) } }
        val n0 = server.requestCount
        val t0 = System.nanoTime()
        post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
        post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        for (i in 0 until 20) post("""{"jsonrpc":"2.0","id":${i + 2},"method":"tools/list"}""")
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(n0 + 22, server.requestCount)
        assertEquals("tools/list", server.lastRequest)
        assertTrue("处理一条 tools/list 用了 ${server.lastRequestMs} ms", server.lastRequestMs >= 0f && server.lastRequestMs < 50f)
        assertTrue("握手 + 20 次工具清单一共 $ms ms", ms < 3000.0)
        synchronized(seen) { assertEquals("initialize", seen.first()); assertEquals(22, seen.size) }
        // 新协议的请求一样计数
        modern("tools/list")
        assertEquals(n0 + 23, server.requestCount)
        server.onRequest = null
    }

    @Test fun modernDiscoverListAndCall() {
        val d = modern("server/discover")
        assertEquals(200, d.status)
        assertEquals("complete", d.result["resultType"])
        assertEquals(listOf("2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"), d.result["supportedVersions"])
        assertNotNull((d.result["capabilities"] as Map<*, *>)["tools"])
        assertEquals("test instructions", d.result["instructions"])
        assertTrue((d.result["ttlMs"] as Number).toInt() >= 0)
        assertTrue(d.result["cacheScope"] in listOf("public", "private"))
        val info = (d.result["_meta"] as Map<*, *>)["io.modelcontextprotocol/serverInfo"] as Map<*, *>
        assertEquals("test-server", info["name"]); assertEquals("1.2.3", info["version"])
        assertNull("新协议没有会话", d.headers["mcp-session-id"])

        val l = modern("tools/list", id = "\"abc\"")
        assertEquals("abc", l.json["id"])
        assertEquals("complete", l.result["resultType"])
        assertEquals(3, (l.result["tools"] as List<*>).size)
        assertTrue(l.result.containsKey("ttlMs") && l.result.containsKey("cacheScope"))

        val c = modern("tools/call", "\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}", name = "echo")
        assertEquals(200, c.status)
        assertEquals("complete", c.result["resultType"])
        assertEquals("echo: hi", ((c.result["content"] as List<*>)[0] as Map<*, *>)["text"])

        // 名字里有非 ASCII 字符时，请求头里是 base64 的写法
        val b64 = java.util.Base64.getEncoder().encodeToString("画像".toByteArray(Charsets.UTF_8))
        val p = modern("tools/call", "\"name\":\"画像\",\"arguments\":{}", name = "=?base64?$b64?=")
        assertEquals(p.body, 200, p.status)

        // 通知：202，不回内容
        val n = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1,$meta}}",
            headers = mapOf("MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "notifications/cancelled"))
        assertEquals(202, n.status); assertEquals("", n.body)
    }

    @Test fun modernErrorsUseTheStatusCodesFromTheSpec() {
        val v = mapOf("MCP-Protocol-Version" to "2026-07-28")
        // _meta 里缺字段
        val noMeta = post("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", headers = v + mapOf("Mcp-Method" to "tools/list"))
        assertEquals(400, noMeta.status); assertEquals(-32602, noMeta.errorCode)
        // 请求头缺了 / 和正文对不上
        val noHeader = modern("tools/list", headers = emptyMap())
        assertEquals(400, noHeader.status); assertEquals(-32020, noHeader.errorCode)
        val wrongMethod = modern("tools/list", headers = v + mapOf("Mcp-Method" to "tools/call"))
        assertEquals(400, wrongMethod.status); assertEquals(-32020, wrongMethod.errorCode)
        val noName = modern("tools/call", "\"name\":\"echo\",\"arguments\":{\"text\":\"x\"}")
        assertEquals(400, noName.status); assertEquals(-32020, noName.errorCode)
        val wrongName = modern("tools/call", "\"name\":\"echo\",\"arguments\":{\"text\":\"x\"}", name = "boom")
        assertEquals(400, wrongName.status); assertEquals(-32020, wrongName.errorCode)
        // 版本不支持：告诉客户端我们会哪些
        val future = post("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2031-01-01","io.modelcontextprotocol/clientCapabilities":{}}}}""",
            headers = mapOf("MCP-Protocol-Version" to "2031-01-01", "Mcp-Method" to "tools/list"))
        assertEquals(400, future.status); assertEquals(-32022, future.errorCode)
        val data = (future.json["error"] as Map<*, *>)["data"] as Map<*, *>
        assertEquals("2031-01-01", data["requested"])
        assertTrue((data["supported"] as List<*>).contains("2026-07-28"))
        // 没有这个方法：404（新协议里 ping 也没有了）
        for (m in listOf("no/such", "ping", "resources/list")) {
            val r = modern(m)
            assertEquals(m, 404, r.status); assertEquals(-32601, r.errorCode)
        }
        // 没有这个工具
        val nope = modern("tools/call", "\"name\":\"nope\",\"arguments\":{}", name = "nope")
        assertEquals(400, nope.status); assertEquals(-32602, nope.errorCode)
        // id 不能是 null
        val nullId = modern("tools/list", id = "null")
        assertEquals(400, nullId.status); assertEquals(-32600, nullId.errorCode)
        // 工具自己出错不是协议错误
        val boom = modern("tools/call", "\"name\":\"boom\",\"arguments\":{}", name = "boom")
        assertEquals(200, boom.status); assertEquals(true, boom.result["isError"])
    }

    // ------------------------------------------------------------------ 密钥、来源、地址

    @Test fun keyInUrlOrInHeader() {
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        assertEquals(200, post(ping).status)
        assertEquals(200, post(ping, "/mcp", mapOf("Authorization" to "Bearer $key")).status)
        assertEquals(200, post(ping, "/mcp", mapOf("authorization" to "bearer $key")).status)
        assertEquals(200, post(ping, "/mcp", mapOf("X-API-Key" to key)).status)
        assertEquals(200, post(ping, "/mcp?token=$key").status)
        // 把 Streamable HTTP 的客户端指到了 /sse 的地址：一样能用
        assertEquals(200, post(ping, "/sse/$key").status)

        val none = post(ping, "/mcp")
        assertEquals(401, none.status)
        assertTrue(none.headers["www-authenticate"]!!.startsWith("Bearer"))
        assertEquals("application/json", none.headers["content-type"])
        assertEquals("unauthorized", none.json["error"])
        assertEquals(401, post(ping, "/mcp", mapOf("Authorization" to "Bearer wrong")).status)
        val wrongPath = post(ping, "/mcp/wrongkey")
        assertEquals(404, wrongPath.status); assertEquals("application/json", wrongPath.headers["content-type"])
        // 没有 OAuth：客户端来探测时得到干净的 404
        val wk = http("GET", "/.well-known/oauth-protected-resource")
        assertEquals(404, wk.status); assertEquals("application/json", wk.headers["content-type"])
    }

    @Test fun rejectsForeignWebOrigins() {
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        // 外面网站的页面：带着密钥也不行（DNS rebinding）
        assertEquals(403, post(ping, headers = mapOf("Origin" to "http://evil.example.com")).status)
        assertEquals(403, post(ping, headers = mapOf("Origin" to "https://evil.example.com:8765")).status)
        // 本机、局域网的页面、桌面应用自己的页面可以
        for (o in listOf("http://localhost:6274", "http://127.0.0.1:5173", "http://192.168.1.20:3000", "http://[::1]:8080", "vscode-webview://abc", "http://nas.local")) {
            val r = post(ping, headers = mapOf("Origin" to o))
            assertEquals(o, 200, r.status)
            assertEquals(o, r.headers["access-control-allow-origin"])
        }
        // 浏览器的预检
        val pre = http("OPTIONS", "/mcp/$key", headers = mapOf("Origin" to "http://localhost:6274", "Access-Control-Request-Method" to "POST", "Access-Control-Request-Headers" to "content-type, mcp-protocol-version, mcp-method"))
        assertEquals(204, pre.status)
        assertTrue(pre.headers["access-control-allow-methods"]!!.contains("POST"))
        assertTrue(pre.headers["access-control-allow-headers"]!!.contains("mcp-method"))
    }

    @Test fun methodsAndKeepAlive() {
        // 没有「服务端主动推送」的流，也没有会话可以结束：405
        val get = http("GET", "/mcp/$key", headers = mapOf("Accept" to "text/event-stream", "MCP-Protocol-Version" to "2025-11-25"))
        assertEquals(405, get.status); assertTrue(get.headers["allow"]!!.contains("POST"))
        assertEquals(405, http("DELETE", "/mcp/$key").status)
        assertEquals(200, http("GET", "/").status)

        // 一条连接上连着发几个请求
        Socket("127.0.0.1", server.port).use { s ->
            s.soTimeout = 10000
            val ins = BufferedInputStream(s.getInputStream())
            for (i in 1..3) {
                writeRequest(s, "POST", "/mcp/$key", """{"jsonrpc":"2.0","id":$i,"method":"ping"}""", emptyMap())
                val r = readReply(ins)
                assertEquals(200, r.status)
                assertEquals(i, (r.json["id"] as Number).toInt())
                assertEquals("keep-alive", r.headers["connection"])
            }
        }
        // 停了服务，留着的连接也一起断
        val s = Socket("127.0.0.1", server.port)
        s.soTimeout = 10000
        writeRequest(s, "POST", "/mcp/$key", """{"jsonrpc":"2.0","id":1,"method":"ping"}""", emptyMap())
        val ins = BufferedInputStream(s.getInputStream())
        assertEquals(200, readReply(ins).status)
        server.stop()
        assertEquals(-1, ins.read())
        s.close()
        assertFalse(server.isRunning)
    }

    // ------------------------------------------------------------------ 旧的 HTTP+SSE 传输

    @Test fun legacySseTransport() {
        Socket("127.0.0.1", server.port).use { s ->
            s.soTimeout = 10000
            writeRequest(s, "GET", "/sse/$key", null, mapOf("Accept" to "text/event-stream"))
            val ins = BufferedInputStream(s.getInputStream())
            assertTrue(readLine(ins).startsWith("HTTP/1.1 200"))
            val headers = HashMap<String, String>()
            while (true) { val h = readLine(ins); if (h.isEmpty()) break; headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim() }
            assertEquals("text/event-stream", headers["content-type"])
            assertEquals("chunked", headers["transfer-encoding"])
            /** 读下一个事件的 (event, data)；跳过分块的长度行和心跳。 */
            fun event(): Pair<String, String> {
                var ev = ""; var data = ""
                while (true) {
                    val line = readLine(ins)
                    when {
                        line.startsWith("event: ") -> ev = line.substring(7)
                        line.startsWith("data: ") -> data = line.substring(6)
                        line.isEmpty() && data.isNotEmpty() -> return ev to data
                    }
                }
            }
            val (ev, endpoint) = event()
            assertEquals("endpoint", ev)
            assertTrue(endpoint, endpoint.startsWith("/messages/$key?sessionId="))

            val accepted = http("POST", endpoint, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
            assertEquals(202, accepted.status)
            val (ev2, msg) = event()
            assertEquals("message", ev2)
            val init = Json.parse(msg) as Map<*, *>
            assertEquals("2024-11-05", (init["result"] as Map<*, *>)["protocolVersion"])

            assertEquals(202, http("POST", endpoint, """{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)
            assertEquals(202, http("POST", endpoint, """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"text":"sse"}}}""").status)
            val (_, msg2) = event()
            assertTrue(msg2, msg2.contains("echo: sse"))
        }
        // 会话不存在
        assertEquals(404, http("POST", "/messages/$key?sessionId=nope", "{}").status)
    }

    // ------------------------------------------------------------------ 工具的参数表

    /** WebSocket：每个文本帧一条 JSON-RPC 消息。客户端的帧要带掩码，长消息可以分片，ping 要回 pong。 */
    @Test fun webSocketTransport() {
        // 没有密钥连不上；不是 WebSocket 握手的请求会被告知要升级
        assertEquals(401, http("GET", "/ws", null, mapOf("Upgrade" to "websocket", "Connection" to "Upgrade", "Sec-WebSocket-Key" to "dGhlIHNhbXBsZSBub25jZQ==", "Sec-WebSocket-Version" to "13")).status)
        assertEquals(426, http("GET", "/ws/$key").status)
        Socket("127.0.0.1", server.port).use { s ->
            s.soTimeout = 10000
            writeRequest(s, "GET", "/ws/$key", null, mapOf("Upgrade" to "websocket", "Connection" to "Upgrade",
                "Sec-WebSocket-Key" to "dGhlIHNhbXBsZSBub25jZQ==", "Sec-WebSocket-Version" to "13", "Sec-WebSocket-Protocol" to "mcp"))
            val ins = BufferedInputStream(s.getInputStream())
            assertTrue(readLine(ins).startsWith("HTTP/1.1 101"))
            val headers = HashMap<String, String>()
            while (true) { val h = readLine(ins); if (h.isEmpty()) break; headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim() }
            // RFC 6455 里的例子：这个 key 对应的应答值
            assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", headers["sec-websocket-accept"])
            assertEquals("mcp", headers["sec-websocket-protocol"])
            val out = s.getOutputStream()
            fun frame(opcode: Int, data: ByteArray, fin: Boolean = true) {
                val b = ByteArrayOutputStream()
                b.write((if (fin) 0x80 else 0) or opcode)
                if (data.size < 126) b.write(0x80 or data.size) else { b.write(0x80 or 126); b.write(data.size ushr 8); b.write(data.size and 255) }
                val mask = byteArrayOf(0x11, 0x22, 0x33, 0x44)
                b.write(mask)
                for (i in data.indices) b.write(data[i].toInt() xor mask[i and 3].toInt())
                out.write(b.toByteArray()); out.flush()
            }
            fun read(): Pair<Int, String> {
                val b0 = ins.read(); val b1 = ins.read()
                assertEquals("服务端的帧不带掩码", 0, b1 and 0x80)
                var len = b1 and 0x7F
                if (len == 126) len = (ins.read() shl 8) or ins.read()
                else if (len == 127) { var l = 0L; repeat(8) { l = (l shl 8) or ins.read().toLong() }; len = l.toInt() }
                val data = ByteArray(len); var got = 0
                while (got < len) { val n = ins.read(data, got, len - got); if (n < 0) break; got += n }
                return (b0 and 0x0F) to String(data, Charsets.UTF_8)
            }
            fun send(text: String) = frame(1, text.toByteArray(Charsets.UTF_8))

            send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
            val (op, init) = read()
            assertEquals(1, op)
            assertEquals("2025-06-18", ((Json.parse(init) as Map<*, *>)["result"] as Map<*, *>)["protocolVersion"])
            // 通知没有应答；下一个应答应该是 tools/list 的
            send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            val list = Json.parse(read().second) as Map<*, *>
            assertEquals(2.0, (list["id"] as Number).toDouble(), 0.0)
            assertEquals(3, ((list["result"] as Map<*, *>)["tools"] as List<*>).size)
            // ping 夹在分片的消息中间；长消息（> 125 字节）用两字节长度
            val call = """{"jsonrpc":"2.0","id":"c","method":"tools/call","params":{"name":"echo","arguments":{"text":"${"长".repeat(100)}"}}}""".toByteArray(Charsets.UTF_8)
            frame(1, call.copyOfRange(0, 40), fin = false)
            frame(9, "hi".toByteArray())
            frame(0, call.copyOfRange(40, call.size))
            assertEquals(10 to "hi", read())
            val (_, reply) = read()
            assertTrue(reply, reply.contains("echo: ${"长".repeat(100)}") && reply.contains("\"id\":\"c\""))
            send("not json")
            assertTrue(read().second.contains("-32700"))
            send("""[{"jsonrpc":"2.0","id":7,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/x"}]""")
            assertEquals(1, (Json.parse(read().second) as List<*>).size)
            frame(8, byteArrayOf(0x03, 0xE8.toByte()))
            assertEquals(8, read().first)
            assertEquals(-1, ins.read())
        }
    }

    /** stdio：一行一条消息，应答也是一行一条；通知和空行没有应答。 */
    @Test fun stdioTransport() {
        val input = listOf(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            "",
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"text":"第一行\n第二行"}}}""",
            "oops",
            """{"jsonrpc":"2.0","id":3,"method":"nope"}""",
        ).joinToString("\n") + "\n"
        val out = ByteArrayOutputStream()
        server.serveStdio(input.byteInputStream(Charsets.UTF_8), out)
        val lines = out.toString("UTF-8").trimEnd('\n').split('\n')
        assertEquals(4, lines.size)
        val replies = lines.map { Json.parse(it) as Map<*, *> }
        assertEquals("2024-11-05", (replies[0]["result"] as Map<*, *>)["protocolVersion"])
        assertEquals("echo: 第一行\n第二行", (((replies[1]["result"] as Map<*, *>)["content"] as List<*>)[0] as Map<*, *>)["text"])
        assertEquals(-32700, ((replies[2]["error"] as Map<*, *>)["code"] as Number).toInt())
        assertEquals(-32601, ((replies[3]["error"] as Map<*, *>)["code"] as Number).toInt())
        assertNull(server.handleMessage("""{"jsonrpc":"2.0","method":"notifications/cancelled"}"""))
    }

    /** 不讲 MCP 的客户端：普通的 HTTP 接口。 */
    @Test fun restApi() {
        assertEquals(401, http("GET", "/api/tools").status)
        assertEquals(404, http("GET", "/api/wrongkey/tools").status)
        // 密钥放路径里 / 请求头里 / 查询串里都行
        val list = http("GET", "/api/$key/tools")
        assertEquals(200, list.status)
        val fns = list.json["tools"] as List<*>
        assertEquals(3, fns.size)
        val f0 = fns[0] as Map<*, *>
        assertEquals("function", f0["type"])
        assertEquals("echo", (f0["function"] as Map<*, *>)["name"])
        assertEquals("object", ((f0["function"] as Map<*, *>)["parameters"] as Map<*, *>)["type"])
        assertEquals(200, http("GET", "/api/tools", null, mapOf("Authorization" to "Bearer $key")).status)
        val raw = http("GET", "/api/tools?format=mcp&token=$key")
        assertTrue(((raw.json["tools"] as List<*>)[0] as Map<*, *>).containsKey("inputSchema"))

        val r = http("POST", "/api/$key/tools/echo", """{"text":"rest"}""")
        assertEquals(200, r.status)
        assertEquals(true, r.json["ok"])
        assertEquals("echo: rest", r.json["text"])
        // 工具自己报错：还是 200，ok=false，原因在 text 里
        val boom = http("POST", "/api/tools/boom", "", mapOf("X-API-Key" to key))
        assertEquals(200, boom.status)
        assertEquals(false, boom.json["ok"])
        assertTrue(boom.body, (boom.json["text"] as String).contains("it broke"))
        // 中文名字的工具（路径里是百分号编码）和图片
        val pic = http("POST", "/api/$key/tools/%E7%94%BB%E5%83%8F", "{}")
        assertEquals("picture", pic.json["text"])
        assertEquals("image/png", (pic.json["image"] as Map<*, *>)["mimeType"])
        assertEquals("AQID", (pic.json["image"] as Map<*, *>)["data"])

        assertEquals(404, http("POST", "/api/$key/tools/nope", "{}").status)
        assertEquals(400, http("POST", "/api/$key/tools/echo", "[1]").status)
        assertEquals(400, http("POST", "/api/$key/tools/echo", "{oops").status)
        assertEquals(405, http("GET", "/api/$key/tools/echo").status)

        val api = http("GET", "/api/$key/openapi.json")
        assertEquals(200, api.status)
        assertEquals("3.1.0", api.json["openapi"])
        val paths = api.json["paths"] as Map<*, *>
        assertEquals(3, paths.size)
        val op = (paths["/tools/echo"] as Map<*, *>)["post"] as Map<*, *>
        assertEquals("echo", op["operationId"])
        assertEquals("http://127.0.0.1:${server.port}/api/$key", ((api.json["servers"] as List<*>)[0] as Map<*, *>)["url"])
        // 首页列出所有接法
        val home = http("GET", "/").json
        assertEquals("/ws", (home["mcp"] as Map<*, *>)["websocket"])
        assertEquals("/api/tools", (home["rest"] as Map<*, *>)["tools"])
    }

    private class Host(val dir: File) : McpHost, ScriptCallbacks {
        override val scene = Scene()
        override val editor = Editor(scene)
        override val runtime = SceneRuntime(scene, RenderSettings(), this)
        override fun <T> render(block: () -> T): T = block()
        override val meshSession: MeshSession? get() = null
        override fun beginMesh(model: ModelInstance): MeshSession = throw UnsupportedOperationException()
        override fun endMesh() {}
        override fun libraryFiles(): List<Map<String, Any?>> = emptyList()
        override fun resolve(path: String): File? = null
        override fun addModel(data: ModelData, kind: Int): ModelInstance { val m = ModelInstance(data, kind); scene.add(m); return m }
        override fun removeModel(model: ModelInstance) { scene.remove(model) }
        override fun saveModel(model: ModelInstance, name: String?): String = ""
        override fun saveMotion(name: String, bytes: ByteArray): String = ""
        override fun screenshot(maxSide: Int): ByteArray? = null
        override fun changed() {}
        override fun setPlaying(on: Boolean) {}
        override fun seekSeconds(seconds: Float) {}
        override fun advance(seconds: Float) {}
        override fun scriptNames(): List<String> = emptyList()
        override fun readScript(name: String): String? = null
        override fun saveScript(name: String, code: String): String = ""
        override fun displayPath(path: String): String = path
        override fun scriptLog(): List<String> = emptyList()
        override fun scriptLogTotal(): Long = 0
        override fun beforeAudioEdit() {}
        override fun audioChanged() {}
        override fun scriptLog(script: Script?, text: String, error: Boolean) {}
    }

    /**
     * 参数表只用各家都收的写法：客户端要把它转成 OpenAI / Gemini / Claude 的函数声明，
     * 「整数或字符串」这样的类型数组、没有类型的参数、空的对象，都会让整份工具清单被拒收。
     */
    @Test fun toolSchemasArePortable() {
        val tools = SceneTools(Host(File("."))).list()
        assertTrue(tools.size >= 48)
        val problems = ArrayList<String>()
        val allowed = setOf("type", "description", "properties", "required", "items", "enum", "minItems", "maxItems")
        fun walk(node: Any?, where: String, top: Boolean) {
            val o = node as? Map<*, *> ?: run { problems.add("$where: not an object"); return }
            val type = o["type"]
            if (type !is String) { problems.add("$where: type must be one string, got $type"); return }
            if (!top && (o["description"] as? String).isNullOrBlank() && !where.endsWith("[]")) problems.add("$where: no description")
            for (k in o.keys) if (k !in allowed) problems.add("$where: keyword $k")
            when (type) {
                "object" -> {
                    val props = o["properties"] as? Map<*, *>
                    if (props == null) problems.add("$where: object without properties")
                    else {
                        if (!top && props.isEmpty()) problems.add("$where: nested object with empty properties")
                        for ((k, v) in props) walk(v, "$where.$k", false)
                        (o["required"] as? List<*>)?.let { req ->
                            if (req.isEmpty()) problems.add("$where: empty required")
                            for (r in req) if (r !in props.keys) problems.add("$where: required '$r' is not a property")
                        }
                    }
                }
                "array" -> if (o["items"] == null) problems.add("$where: array without items") else walk(o["items"], "$where[]", false)
                "string", "number", "integer", "boolean" -> {}
                else -> problems.add("$where: type $type")
            }
        }
        val names = HashSet<String>()
        for (t in tools) {
            val name = t["name"] as String
            assertTrue("重名的工具 $name", names.add(name))
            if (!Regex("[A-Za-z0-9_-]{1,64}").matches(name)) problems.add("$name: tool name")
            val d = t["description"] as? String
            if (d.isNullOrBlank()) problems.add("$name: no description") else if (d.length > 1024) problems.add("$name: description is ${d.length} chars (limit 1024)")
            walk(t["inputSchema"], name, true)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
