package plymeld.printer

import plymeld.config.Config
import plymeld.config.Dimension
import plymeld.config.Site
import java.time.Duration

/**
 * How a printer is reached, read from its machine's `hardware.machine.<key>.connection.*`: the protocol, the address,
 * the port (the API's own when unset), how long to wait, the credentials (each resolved from the environment variable
 * NAMED in the config; the value is never in a config file) and the camera's URL. Two machines of one model are two of
 * these.
 */
class Connection(
    val machine: String,
    val protocol: Protocol,
    val host: String,
    val port: Int,
    val timeout: Duration,
    /** Credential name -> value, from the sources the config names (the environment, a secrets file); the required ones are all present (a missing one is refused at read). */
    val credentials: Map<String, String>,
    val cameraUrl: String?,
    /** The printer's shell (`connection.ssh.*`), when the config names an SSH user; null = none. */
    val ssh: SshSettings? = null,
)

/** Opens the [Printer] of a machine from the config. The one place that knows which adapter speaks which protocol. */
object Printers {
    /** 8 s: the loadout command's wait (plymeld's choice), until `connection.timeout` says otherwise. */
    val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(8)

    /** The protocol [machine]'s connection names (`connection.api`); refused when it names none or one plymeld does not speak. */
    fun protocol(c: Config, machine: String): Protocol {
        val path = "hardware.machine.$machine.connection"
        val api = (c.entry(listOf("hardware", "machine", machine, "connection", "api"))?.value as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("machine '$machine' has no connection: set $path.api (one of ${Protocol.ids.joinToString()}) and $path.host")
        return Protocol.byId(api) ?: error("machine '$machine': $path.api = \"$api\" is not a protocol plymeld speaks (one of ${Protocol.ids.joinToString()})")
    }

    /**
     * The connection of [machine] in [c]. Refused in one line when the machine has no `connection.api`, the api is
     * unknown, the host is unset, or a required credential has no source named or none of its sources (the environment
     * variable, [env]; the secrets file) has a value.
     */
    fun connection(c: Config, machine: String, env: (String) -> String? = System::getenv): Connection {
        val base = listOf("hardware", "machine", machine, "connection")
        fun text(vararg rest: String) = (c.entry(base + rest.toList())?.value as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val path = "hardware.machine.$machine.connection"
        val protocol = protocol(c, machine)
        val host = text("host") ?: error("machine '$machine' has no address: set $path.host")
        val site = Site(machine = machine)
        val port = c.entry(base + "port")?.let { c.quantity(it, Dimension.COUNT, site).toInt() } ?: protocol.defaultPort
        val timeout = c.entry(base + "timeout")?.let { Duration.ofMillis((c.quantity(it, Dimension.TIME, site) * 1000).toLong()) } ?: DEFAULT_TIMEOUT
        val credentials = LinkedHashMap<String, String>()
        for (name in protocol.required + protocol.optional) {
            val required = name in protocol.required
            // the sources the config names, the environment before the file; the first value wins
            val sources = listOfNotNull(
                text("credential", name, "env")?.let { EnvironmentCredential(it, env) },
                text("credential", name, "file")?.let { FileCredential(FileCredential.expandHome(it), name) })
            if (sources.isEmpty()) {
                if (required) error("printer '$machine' needs credential '$name' for ${protocol.id}: set $path.credential.$name.env to the name of the environment variable holding it, or $path.credential.$name.file to a secrets file with a '$name' line")
                continue
            }
            val value = Credentials.resolve(sources)
                ?: if (required) error("printer '$machine': credential '$name' comes from ${sources.joinToString(", then ") { it.words }}, and none has it") else continue
            credentials[name] = value
        }
        // the printer's shell: named by its user; the host and port default to the connection's host and 22
        val sshUser = text("ssh", "user")
        val ssh = sshUser?.let {
            val sshPort = c.entry(base + listOf("ssh", "port"))?.let { e -> c.quantity(e, Dimension.COUNT, site).toInt() } ?: SshSettings.DEFAULT_PORT
            val acceptNew = c.entry(base + listOf("ssh", "accept_new"))?.let { e -> c.boolean(e, site) } == true
            if (credentials["ssh_password"] == null && credentials["ssh_key_file"] == null)
                error("printer '$machine': $path.ssh.user is set, but no credential unlocks the shell: name $path.credential.ssh_password or $path.credential.ssh_key_file (.env or .file)")
            SshSettings(text("ssh", "host") ?: host, sshPort, it, credentials["ssh_password"], credentials["ssh_key_file"], text("ssh", "variables_file") ?: SshSettings.QIDI_VARIABLES_FILE, acceptNew)
        }
        return Connection(machine, protocol, host, port, timeout, credentials, text("camera", "url"), ssh)
    }

    /** The printer of [machine], over [transport] (the JDK's unless a test substitutes one) and, for its shell, [ssh] (the real one unless a test substitutes a fake). */
    fun open(c: Config, machine: String, env: (String) -> String? = System::getenv, transport: Transport = JdkHttp(), ssh: (Connection) -> SshTransport = ::sshTransport): Printer =
        open(connection(c, machine, env), transport, ssh)

    fun open(cn: Connection, transport: Transport = JdkHttp(), ssh: (Connection) -> SshTransport = ::sshTransport): Printer = when (cn.protocol) {
        Protocol.FLASHFORGE_HTTP -> FlashforgeHttp(cn.machine, cn.host, cn.port, cn.credentials.getValue("serial_number"), cn.credentials.getValue("check_code"), cn.timeout, transport, cn.cameraUrl)
        Protocol.MOONRAKER -> MoonrakerHttp(cn.machine, cn.host, cn.port, cn.credentials["api_key"], cn.timeout, transport, cn.cameraUrl,
            cn.ssh?.let { { ssh(cn) } }, cn.ssh?.variablesFile ?: SshSettings.QIDI_VARIABLES_FILE)
    }

    /** The real SSH transport ([MinaSsh], Apache MINA SSHD) for [cn]'s shell, against the user's `~/.ssh/known_hosts`. */
    fun sshTransport(cn: Connection): SshTransport =
        MinaSsh(cn.machine, cn.ssh ?: error("machine '${cn.machine}' names no shell (hardware.machine.${cn.machine}.connection.ssh.user)"), cn.timeout)
}
