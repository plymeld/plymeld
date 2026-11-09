package plymeld.printer

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * One HTTP exchange with a printer, as an adapter builds it: [method], the full [url], [headers] (credentials ride
 * here for some APIs: never log a call as it is), an optional [body] and how long to wait for the answer.
 */
class HttpCall(val method: String, val url: String, val headers: Map<String, String> = emptyMap(), val body: ByteArray? = null, val timeout: Duration) {
    val path: String get() = URI(url).path.ifEmpty { "/" } + (URI(url).rawQuery?.let { "?$it" } ?: "")
}

/** A printer's answer: the status, the headers (lower-case names), and the body as a stream (the camera reads it until it has a frame). */
class HttpAnswer(val status: Int, val headers: Map<String, String>, val stream: InputStream) : AutoCloseable {
    fun bytes(): ByteArray = stream.use { it.readAllBytes() }
    fun text(): String = String(bytes(), Charsets.UTF_8)
    val contentType: String get() = headers["content-type"] ?: ""
    override fun close() = stream.close()

    companion object {
        fun of(status: Int, body: String, contentType: String = "application/json"): HttpAnswer =
            HttpAnswer(status, mapOf("content-type" to contentType), ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
        fun of(status: Int, body: ByteArray, contentType: String): HttpAnswer =
            HttpAnswer(status, mapOf("content-type" to contentType), ByteArrayInputStream(body))
    }
}

/**
 * The one way an adapter reaches a printer: every request goes through
 * [send], so a test substitutes recorded answers and reads what was sent. [JdkHttp] is the real one. A transport
 * throws [IOException] when the printer cannot be reached; [sendOrRefuse] turns that into plymeld's one-line refusal.
 */
fun interface Transport {
    fun send(call: HttpCall): HttpAnswer
}

/**
 * JDK 21's `java.net.http`, one client per transport; the connect timeout is the call's own timeout. HTTP/1.1 only: the
 * JDK's default asks a plain-http server to switch to HTTP/2 on each new connection (`Upgrade: h2c`), which a printer's
 * small web server does not speak; the printers' own clients send HTTP/1.1.
 */
class JdkHttp : Transport {
    private val clients = HashMap<Duration, HttpClient>()

    override fun send(call: HttpCall): HttpAnswer {
        val client = clients.getOrPut(call.timeout) { HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(call.timeout).build() }
        val b = HttpRequest.newBuilder(URI(call.url)).timeout(call.timeout)
        call.headers.forEach { (k, v) -> b.header(k, v) }
        b.method(call.method, call.body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream())
        return HttpAnswer(r.statusCode(), r.headers().map().mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(", ") }, r.body())
    }
}

/**
 * [call] sent, or the refusal naming [printer] (its key), the host, and the request when the printer cannot be reached.
 * The message carries the exception's text, which names the address, never a credential.
 */
fun Transport.sendOrRefuse(call: HttpCall, printer: String): HttpAnswer = try {
    send(call)
} catch (e: IOException) {
    val u = URI(call.url)
    val cut = CUT_SHORT.find(e.message ?: "")
    // the printer reached and answering, but closing the connection before its answer's body: it refused the request, its
    // reason lost with the body (a Flashforge Creator 5 refusing an upload: "could not reach" sent the user looking for
    // a network fault)
    if (cut != null) error("printer '$printer' at ${u.host}:${u.port} answered ${call.path} but closed the connection before its answer " +
        "(${cut.groupValues[2]} of ${cut.groupValues[1]} bytes arrived): it refused the request without saying why. The printer's own screen may say; " +
        "`plymeld printer status` shows its state")
    error("printer '$printer': could not reach ${u.host}:${u.port} (${call.path}): ${e.message ?: e.javaClass.simpleName}")
}

/** The JDK's words for an answer whose body ended before its declared length. */
private val CUT_SHORT = Regex("fixed content-length: (\\d+), bytes received: (\\d+)")

/** An answer's body as text for a refusal: its first [max] characters, one line; "" when it has none or cannot be read. */
fun HttpAnswer.said(max: Int = 200): String = try {
    text().replace(Regex("\\s+"), " ").trim().let { if (it.length > max) it.take(max) + "…" else it }
} catch (_: IOException) { "" }

/** A `multipart/form-data` body: text [fields] then one [file] (field name, file name, bytes), with a fixed [boundary] so tests can read it. */
object Multipart {
    fun body(boundary: String, fields: List<Pair<String, String>>, file: Triple<String, String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun text(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) text("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        val (field, name, bytes) = file
        text("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$name\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        out.write(bytes)
        text("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }

    fun contentType(boundary: String) = "multipart/form-data; boundary=$boundary"
}
