package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import plymeld.config.UserDirs

/**
 * `plymeld update`: installs the newest release over this one ([Installs]): a Velopack install (the Windows installer
 * or portable zip, the AppImage) through Velopack's updater as this command exits; the Linux command line's tar in
 * place. Other plymeld processes running from a Windows install lock its files: the update is refused, naming them,
 * unless `--stop-others` stops them first.
 */
class UpdateCommand : CliktCommand(name = "update") {
    private val stopOthers by option("--stop-others", help = "stop the other plymeld processes running from this install first (a window, a command in another terminal): on Windows their files cannot be replaced while they run").flag()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Install the newest plymeld release over this one. An install from the Windows installer, the portable zip or the AppImage is updated by its " +
            "updater as this command exits (the window included); the Linux command line's own download (plymeld-linux-x64.tar.gz) has its files " +
            "replaced in place, the download checked against the checksum published with it and the new executable's --version. " +
            "On Windows, other plymeld processes running from the install are named and the update refused, unless --stop-others stops them. " +
            "A build from source is not updated. Whether plymeld only says a newer release is out: `plymeld updates`."

    override fun run() {
        if (System.getProperty(IN_WINDOW) != null) error("in the window, an update is installed from its bar (⬆ VERSION · install), which stops the window for it")
        val installed = PlymeldVersion.value
        val pref = UpdateFeed.Pref.load()
        val feed = pref.feed ?: error("no update feed to ask: this build has none (a build from source is updated from source); set feed=URL in ${pref.file}")
        val vp = Installs.velopack()
        val tar = if (vp == null) Installs.Tar.folder() else null
        if (vp == null && tar == null) error("this plymeld is not an install plymeld can update (a build from source, or a copy of the files): install a release from https://github.com/plymeld/plymeld/releases")
        val channel = vp?.channel ?: "linux"
        val newest = UpdateFeed.newest(UpdateFeed.fetch(feed, channel), vp?.version ?: installed, vp?.id ?: UpdateFeed.PACKAGE_ID)
        UpdateFeed.Seen(java.time.Instant.now(), newest?.version, UpdateFeed.Seen.load().told).save()
        if (newest == null) { echo("plymeld ${vp?.version ?: installed} is the newest release: nothing to install"); return }
        echo("plymeld ${newest.version} is out (this is ${vp?.version ?: installed}): installing")
        if (vp != null) velopack(vp, newest, feed) else tar(tar!!, newest.version, feed)
    }

    private fun others(root: java.nio.file.Path, blocking: Boolean) {
        val others = Installs.running(root)
        if (others.isEmpty()) return
        if (!blocking) { echo("${others.size} other plymeld process(es) keep running the old version until they exit: " + others.joinToString("; ") { it.describe() }); return }
        if (!stopOthers) error("other plymeld processes are running from this install, and Windows keeps their files from being replaced: " +
            others.joinToString("; ") { it.describe() } + ". Close them, or run `plymeld update --stop-others` to stop them")
        echo("stopping: " + others.joinToString("; ") { it.describe() })
        val left = Installs.stop(others)
        if (left.isNotEmpty()) error("these would not stop: " + left.joinToString("; ") { it.describe() })
    }

    private fun velopack(vp: Installs.Velopack, r: UpdateFeed.Release, feed: String) {
        others(vp.root, blocking = System.getProperty("os.name").lowercase().contains("windows"))
        var shown = -1L
        val pkg = Installs.downloadPackage(r, "${feed.trimEnd('/')}/${r.fileName}", vp) { done, total ->
            if (total > 0) { val pct = 100 * done / total; if (pct / 10 != shown) { shown = pct / 10; echo("downloading ${pct} %") } }
        }
        Installs.apply(vp, pkg, restart = false)
        echo("plymeld ${r.version} installs as this command exits; run plymeld again in a moment")
    }

    private fun tar(folder: java.nio.file.Path, version: String, feed: String) {
        others(folder, blocking = false)
        val name = Installs.Tar.name() ?: error("no command-line download is published for this machine (${System.getProperty("os.arch")})")
        Installs.Tar.update(folder, version, feed, name) { echo(it) }
    }

    companion object {
        /** Set by the window as it starts: an update there is the bar's, which stops the window for the updater. */
        const val IN_WINDOW = "plymeld.window"
    }
}
