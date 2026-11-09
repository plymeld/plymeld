package plymeld.printer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Printer requests are plain HTTP/1.1, as the printers' own clients send: no `Upgrade: h2c` asking a printer's small web
 * server to switch to HTTP/2 (the JDK client's default on a plain-http address). Checked on a server on this machine
 * receiving an upload-sized POST. Control: the JDK's default client, as plymeld used before, asks to upgrade on that POST.
 */
class JdkHttpVersionTest : StringSpec({
    "a printer request carries no HTTP/2 upgrade, a POST body included; control: the JDK default client asks for one" {
        val seen = ArrayList<Pair<String, String?>>()       // protocol, Upgrade header
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.requestBody.readAllBytes()
            synchronized(seen) { seen += ex.protocol to ex.requestHeaders.getFirst("Upgrade") }
            val ok = """{"code": 0}""".toByteArray()
            ex.sendResponseHeaders(200, ok.size.toLong()); ex.responseBody.use { it.write(ok) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/uploadGcode"
            JdkHttp().send(HttpCall("POST", url, mapOf("Content-Type" to "application/octet-stream"), ByteArray(1 shl 20), Duration.ofSeconds(10))).use { it.status shouldBe 200 }
            JdkHttp().send(HttpCall("GET", url.replace("uploadGcode", "detail"), emptyMap(), null, Duration.ofSeconds(10))).use { it.status shouldBe 200 }
            val ours = synchronized(seen) { seen.toList() }
            ours.size shouldBe 2
            ours.all { it.first == "HTTP/1.1" && it.second == null } shouldBe true
            // control: the default client, a fresh connection, the same upload-sized POST: it asks to upgrade
            seen.clear()
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(url)).POST(HttpRequest.BodyPublishers.ofByteArray(ByteArray(1 shl 20))).build(), HttpResponse.BodyHandlers.discarding())
            synchronized(seen) { seen.single().second } shouldBe "h2c"
        } finally { server.stop(0) }
    }
})
