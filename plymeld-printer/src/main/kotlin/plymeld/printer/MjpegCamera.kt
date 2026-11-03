package plymeld.printer

import java.time.Duration

/**
 * One JPEG from a printer's camera URL, whether the server answers with a single image (`image/jpeg`, a snapshot URL)
 * or an MJPEG stream (`multipart/x-mixed-replace`, read only until the wanted frame and then closed). Both printers
 * observed so far run an mjpg-streamer-style server on port 8080: the Creator 5's stream hands out a stale buffered
 * frame first (observed), so its adapter asks for [skip] = 1; the Q2's snapshot URL gives one image. The
 * frame is found by its JPEG markers (FFD8 ... FFD9); a JPEG
 * with an embedded thumbnail would end early (not seen on either camera).
 */
object MjpegCamera {
    private const val MAX_BYTES = 16 shl 20

    fun frame(transport: Transport, url: String, timeout: Duration, skip: Int, printer: String): CameraFrame {
        val answer = transport.sendOrRefuse(HttpCall("GET", url, timeout = timeout), printer)
        check(answer.status == 200) { "printer '$printer': the camera at $url answered HTTP ${answer.status}" }
        if (answer.contentType.startsWith("image/jpeg")) return CameraFrame(answer.bytes(), url)
        answer.stream.use { s ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var toSkip = skip
            var from = 0
            while (buf.size() < MAX_BYTES) {
                val n = s.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                val bytes = buf.toByteArray()
                while (true) {
                    val soi = indexOf(bytes, 0xFF, 0xD8, from)
                    if (soi < 0) break
                    val eoi = indexOf(bytes, 0xFF, 0xD9, soi + 2)
                    if (eoi < 0) break
                    from = eoi + 2
                    if (toSkip > 0) { toSkip--; continue }
                    return CameraFrame(bytes.copyOfRange(soi, eoi + 2), url)
                }
            }
            error("printer '$printer': no JPEG frame in the camera's answer from $url (${buf.size()} bytes read, ${answer.contentType.ifEmpty { "no content type" }})")
        }
    }

    private fun indexOf(bytes: ByteArray, a: Int, b: Int, from: Int): Int {
        var i = from
        while (i + 1 < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) == a && (bytes[i + 1].toInt() and 0xFF) == b) return i
            i++
        }
        return -1
    }
}
