package plymeld.ui

import java.nio.file.Files
import java.nio.file.Path

/**
 * The command line on the user's PATH in a Windows install. Velopack installs the window as `{root}\current\plymeld-ui.exe`
 * and names its own launcher `{root}\plymeld.exe` after the package id; the command line is `{root}\current\cli\plymeld.exe`,
 * so `plymeld` in a new terminal is the command line once `{root}\current\cli` is on the user's PATH. Velopack keeps
 * `current` in place across updates, so the entry stays valid. Done from Velopack's hooks ([Updates.isHook]): added at
 * install and after an update, taken out before an uninstall. Only the user's own PATH (HKCU\Environment) is touched,
 * kept as written (its `%VAR%` entries unexpanded) apart from the one entry.
 */
object CliPath {
    /** [path] (a PATH value, `;`-separated) with [dir] at its end, unless it is there already (case and a trailing `\` aside). */
    fun withEntry(path: String, dir: String): String =
        if (entries(path).any { same(it, dir) }) path else entries(path).plus(dir).joinToString(";")

    /** [path] without [dir] (case and a trailing `\` aside); every other entry kept as it was, in order. */
    fun withoutEntry(path: String, dir: String): String = entries(path).filterNot { same(it, dir) }.joinToString(";")

    private fun entries(path: String): List<String> = path.split(';').filter { it.isNotBlank() }
    private fun same(a: String, b: String) = a.trim().trimEnd('\\').equals(b.trim().trimEnd('\\'), ignoreCase = true)

    /**
     * Velopack's hook in [args] (`--veloapp-install|updated|obsolete|uninstall VERSION`) for the command line's PATH entry:
     * on Windows only, and only when the install has the command line (`cli\plymeld.exe` beside this executable). A
     * failure is reported on stderr and the hook still exits: an install is never held up by its PATH.
     */
    fun hook(args: Array<String>, exe: Path? = plymeld.commands.Installs.currentExe(), os: String = System.getProperty("os.name")) {
        if (!os.lowercase().contains("windows")) return
        val dir = exe?.toAbsolutePath()?.parent?.resolve("cli") ?: return
        if (!Files.isRegularFile(dir.resolve("plymeld.exe"))) return
        val change: (String) -> String = when (args.firstOrNull()) {
            "--veloapp-install", "--veloapp-updated" -> { p -> withEntry(p, dir.toString()) }
            "--veloapp-uninstall" -> { p -> withoutEntry(p, dir.toString()) }
            else -> return
        }
        val before = powershell(READ, emptyMap()) ?: return
        val after = change(before)
        if (after != before) powershell(WRITE, mapOf("PLYMELD_NEW_PATH" to after))
    }

    // the user's PATH as stored (REG_EXPAND_SZ, its %VAR% entries unexpanded), on stdout as UTF-8
    private const val READ = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; " +
        "\$k=[Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment'); " +
        "if(\$k){[Console]::Out.Write(\$k.GetValue('Path','',[Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames))}"

    // written back as REG_EXPAND_SZ (or removed when empty); setting and clearing a variable through .NET then sends
    // WM_SETTINGCHANGE, so terminals started after this see the new PATH without a sign-out
    private const val WRITE = "\$k=[Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Environment'); " +
        "if(\$env:PLYMELD_NEW_PATH){\$k.SetValue('Path',\$env:PLYMELD_NEW_PATH,[Microsoft.Win32.RegistryValueKind]::ExpandString)} " +
        "else{\$k.DeleteValue('Path',\$false)}; \$k.Close(); " +
        "[Environment]::SetEnvironmentVariable('PLYMELD_PATH_CHANGED','1','User'); " +
        "[Environment]::SetEnvironmentVariable('PLYMELD_PATH_CHANGED',\$null,'User')"

    /** Runs [script] in Windows PowerShell with [env] added; its stdout, or null (said on stderr) when it fails. */
    private fun powershell(script: String, env: Map<String, String>): String? {
        val root = System.getenv("SystemRoot") ?: "C:\\Windows"
        val exe = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString()
        // stderr apart, so a warning never becomes part of the PATH read
        val pb = ProcessBuilder(exe, "-NoProfile", "-NonInteractive", "-Command", script).redirectError(ProcessBuilder.Redirect.INHERIT)
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        if (p.waitFor() != 0) { System.err.println("plymeld: the command line's PATH entry was not changed (PowerShell exited ${p.exitValue()})"); return null }
        return out
    }
}
