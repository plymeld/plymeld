package plymeld.printer

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.config.hosts.KnownHostEntry
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.util.security.SecurityUtils
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * The real [SshTransport]: Apache MINA SSHD (Apache-2.0), `sshd-core` only
 *. One client and one session per transport, opened on first use and closed
 * with it. A file is read with `cat` over an exec channel, so `sshd-sftp` is not needed. **Not tried against a real
 * printer from plymeld's side**: the host-key decision and the refusal wording are tested offline ([HostKeys]); the
 * connection, the authentication, and the command run are as MINA's API documents them.
 *
 * - The host key is checked against [knownHosts] (the user's `~/.ssh/known_hosts` unless a test gives another):
 *   unknown or changed is refused in one line naming the host and the file ([HostKeys.refusal]); unknown is accepted
 *   and recorded only with `connection.ssh.accept_new`.
 * - Credentials: a password (`ssh_password`), or the private keys in a file (`ssh_key_file`, loaded by MINA's own
 *   loader: OpenSSH and PEM forms as it supports them, without a passphrase; an encrypted key is not supported here).
 */
class MinaSsh(
    private val printer: String,
    private val s: SshSettings,
    private val timeout: Duration,
    private val knownHosts: Path = KnownHostEntry.getDefaultKnownHostsFile(),
) : SshTransport {
    private val verifier = HostKeys.Verifier(knownHosts, s.acceptNew, s.host, s.port)
    private var client: SshClient? = null
    private var session: ClientSession? = null

    private fun session(): ClientSession {
        session?.let { return it }
        val c = SshClient.setUpDefaultClient()
        c.serverKeyVerifier = verifier
        c.start()
        client = c
        val cs = try { c.connect(s.user, s.host, s.port).verify(timeout).session } catch (e: IOException) { refuse("could not open a shell on ${s.host}:${s.port} as ${s.user}", e) }
        s.password?.let { cs.addPasswordIdentity(it) }
        s.keyFile?.let { f -> for (k in keys(f)) cs.addPublicKeyIdentity(k) }
        try { cs.auth().verify(timeout) } catch (e: IOException) { refuse("the shell on ${s.host}:${s.port} did not accept the ${if (s.password != null) "password" else "key"} for ${s.user}", e) }
        session = cs
        return cs
    }

    private fun keys(file: String): List<java.security.KeyPair> {
        val path = FileCredential.expandHome(file)
        check(Files.isRegularFile(path)) { "printer '$printer': the SSH key file $path does not exist" }
        val pairs = try {
            Files.newInputStream(path).use { SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(path.toString()), it, FilePasswordProvider.EMPTY) }
        } catch (e: IOException) { error("printer '$printer': the SSH key file $path could not be read: ${e.message}") }
            catch (e: java.security.GeneralSecurityException) { error("printer '$printer': the SSH key file $path holds no key MINA can load (an encrypted key is not supported): ${e.message}") }
        return pairs?.toList()?.takeIf { it.isNotEmpty() } ?: error("printer '$printer': the SSH key file $path holds no key")
    }

    /** A failed connect: a host-key decision other than KNOWN is worded as such; anything else names the step and MINA's message. */
    private fun refuse(step: String, e: IOException): Nothing {
        val d = verifier.decision
        if (d != null && d != HostKeys.Decision.KNOWN) error(HostKeys.refusal(printer, s.host, s.port, knownHosts, d))
        error("printer '$printer': $step: ${e.message ?: e.javaClass.simpleName}")
    }

    override fun run(command: String): String = try {
        session().executeRemoteCommand(command, timeout)
    } catch (e: IOException) {
        error("printer '$printer': `$command` failed on ${s.host}: ${e.message ?: e.javaClass.simpleName}")
    }

    /** `cat` of [path] (single-quoted for the shell): one exec channel, no SFTP. */
    override fun read(path: String): String = run("cat '" + path.replace("'", "'\\''") + "'")

    override fun close() {
        session?.close(false)
        session = null
        client?.stop()
        client = null
    }
}
