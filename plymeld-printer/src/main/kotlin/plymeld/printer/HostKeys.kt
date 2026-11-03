package plymeld.printer

import org.apache.sshd.client.config.hosts.KnownHostEntry
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import java.net.SocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey

/**
 * The printer's SSH host key against the user's `known_hosts`: KNOWN when a
 * line for the host and port holds this key, CHANGED when lines for it hold other keys, UNKNOWN when none names it.
 * plymeld's own few lines over MINA's parsing of the file (hashed hosts and `[host]:port` included), so the decision
 * is testable without a network. Never silently accepts: UNKNOWN is accepted and recorded only with
 * `connection.ssh.accept_new`; CHANGED is refused always (a changed key is what a machine in between looks like).
 */
object HostKeys {
    enum class Decision { KNOWN, UNKNOWN, CHANGED }

    fun decide(knownHosts: Path, host: String, port: Int, key: PublicKey): Decision {
        if (!Files.isRegularFile(knownHosts)) return Decision.UNKNOWN
        val matching = KnownHostEntry.readKnownHostEntries(knownHosts).filter { it.isHostMatch(host, port) }
        if (matching.isEmpty()) return Decision.UNKNOWN
        for (e in matching) {
            if (e.marker != null) continue                       // @revoked / @cert-authority lines: never a match for a plain key
            val known = e.keyEntry?.resolvePublicKey(null, PublicKeyEntryResolver.IGNORING) ?: continue
            if (KeyUtils.compareKeys(known, key)) return Decision.KNOWN
        }
        return Decision.CHANGED
    }

    /** Appends a line for [host]:[port] and [key] to [knownHosts] (created with its directory when missing), as ssh writes one. */
    fun record(knownHosts: Path, host: String, port: Int, key: PublicKey) {
        knownHosts.parent?.let { Files.createDirectories(it) }
        val pattern = if (port == SshSettings.DEFAULT_PORT) host else "[$host]:$port"
        val line = "$pattern ${PublicKeyEntry.toString(key)}\n"
        val existing = if (Files.isRegularFile(knownHosts)) Files.readString(knownHosts) else ""
        Files.writeString(knownHosts, existing + (if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n") + line)
    }

    /** The one-line refusal for a host key that is not KNOWN; names the printer by its key, the host, and the file, never the key. */
    fun refusal(printer: String, host: String, port: Int, knownHosts: Path, decision: Decision): String = when (decision) {
        Decision.UNKNOWN -> "printer '$printer': the SSH host key of $host:$port is not in $knownHosts: connect once with ssh to record it, or set hardware.machine.$printer.connection.ssh.accept_new = \"true\" to trust it on first use (a risk: SECURITY.md)"
        Decision.CHANGED -> "printer '$printer': the SSH host key of $host:$port is not the one recorded in $knownHosts: not connecting (a changed key is what a machine in between looks like; remove the old line only if you know why it changed)"
        Decision.KNOWN -> "printer '$printer': the SSH host key of $host:$port is known"
    }

    /** MINA's verifier over [decide]: keeps the [decision] so a failed connect can be worded; records the key when [acceptNew] and UNKNOWN. */
    class Verifier(private val knownHosts: Path, private val acceptNew: Boolean, private val host: String, private val port: Int) : ServerKeyVerifier {
        @Volatile var decision: Decision? = null
            private set

        override fun verifyServerKey(session: ClientSession?, remoteAddress: SocketAddress?, serverKey: PublicKey): Boolean {
            val d = decide(knownHosts, host, port, serverKey)
            decision = d
            return when (d) {
                Decision.KNOWN -> true
                Decision.UNKNOWN -> if (acceptNew) { record(knownHosts, host, port, serverKey); true } else false
                Decision.CHANGED -> false
            }
        }
    }
}
