package plymeld.ui

import plymeld.commands.Installs
import plymeld.commands.UpdateFeed

/**
 * Updates of the window through Velopack: the feed read through [UpdateFeed] and the package downloaded and handed to
 * Velopack's updater through [Installs], both shared with the command line (`plymeld update`). Nothing here depends
 * on how the package was built; `vpk pack` (tools/package-velopack.cmd) makes the installer, the portable zip, and the
 * feed.
 *
 * The rules: nothing is checked until the user turns it on ([UpdateFeed.Pref], the same choice as `plymeld updates
 * --set`: `notify` says there is an update and waits for a click; `install` downloads and installs at the next start;
 * `off`, the default, does nothing); the window checks as it opens; a build that is not a Velopack install
 * ([Installs.velopack] null: a Gradle run, a plain unpacked folder) is never updated. Deltas are not used yet: the
 * full package is downloaded (the updater can `patch` deltas; a later step).
 */
object Updates {
    /** An update the feed offers: the release and where its package is. */
    class Available(val release: UpdateFeed.Release, val url: String)

    /**
     * Velopack's hooks: the installed app is run with `--veloapp-install|updated|obsolete|uninstall VERSION` around an
     * install, an update, and an uninstall, and must exit at once (15 to 30 s, or it is killed); plymeld only puts the
     * command line on the user's PATH or takes it off ([CliPath]). `VELOPACK_FIRSTRUN` and `VELOPACK_RESTART` are
     * environment variables of a normal start, not hooks.
     */
    fun isHook(args: Array<String>): Boolean = args.firstOrNull()?.startsWith("--veloapp-") == true

    /** Reads the feed for the install's channel and says whether a newer full release is there. Throws on a network or feed error. */
    fun check(feedUrl: String, install: Installs.Velopack): Available? {
        val r = UpdateFeed.newest(UpdateFeed.fetch(feedUrl, install.channel), install.version, install.id) ?: return null
        return Available(r, "${feedUrl.trimEnd('/')}/${r.fileName}")
    }
}
