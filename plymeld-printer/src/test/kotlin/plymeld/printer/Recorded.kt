package plymeld.printer

import java.io.IOException

/**
 * A transport of recorded answers for the adapter tests: [answer] maps each call to its answer (or throws), and [sent]
 * keeps every call so a test asserts what went to the printer (the path, the body, the headers) and what did not.
 */
class Recorded(private val answer: (HttpCall) -> HttpAnswer) : Transport {
    val sent = ArrayList<HttpCall>()
    override fun send(call: HttpCall): HttpAnswer { sent += call; return answer(call) }

    val paths: List<String> get() = sent.map { "${it.method} ${it.path}" }
    fun bodyText(i: Int): String = String(sent[i].body ?: ByteArray(0), Charsets.UTF_8)

    companion object {
        /** A transport that cannot reach anything: every call fails as the JDK client would on a closed port. */
        fun unreachable() = Recorded { throw IOException("Connection refused") }

        /** A JSON answer with HTTP 200. */
        fun json(text: String) = HttpAnswer.of(200, text)
    }
}
