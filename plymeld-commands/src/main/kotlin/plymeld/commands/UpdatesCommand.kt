package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import plymeld.config.UserDirs

/**
 * `plymeld updates`: whether plymeld checks for a newer release ([UpdateFeed.Pref], the same choice the window's settings
 * set), what the last check found, and a check now.
 */
class UpdatesCommand : CliktCommand(name = "updates") {
    private val set by option("--set", metavar = "off|notify|install",
        help = "turn checks on or off, for the command line and the window: off (the default: nothing is checked), notify (a newer release is said after a command, at most once a day, and in the window's bar) or install (as notify, and the window installs it at its next start; the command line never updates itself)")
        .choice(*UpdateFeed.Pref.Mode.entries.map { it.word }.toTypedArray())
    private val check by option("--check", help = "ask the release feed now, whatever the setting, and say whether a newer release is out").flag()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Whether plymeld checks for a newer release: the setting (off until you turn it on with --set), the feed it asks, and what the last check found; " +
            "--check asks now. Turned on, the command line asks the feed as it runs any command, at most once a day and in the background, " +
            "and says a newer release in one line on stderr after the command (only in a terminal); the window asks as it opens."

    override fun run() {
        val dirs = UserDirs.current()
        set?.let { w -> val m = UpdateFeed.Pref.Mode.entries.first { it.word == w }; UpdateFeed.Pref.save(m, dirs); echo("updates: $w (${m.says}); written to ${UpdateFeed.Pref.file(dirs)}") }
        val pref = UpdateFeed.Pref.load(dirs)
        if (set == null) echo("updates: ${pref.mode.word} (${pref.mode.says})" + (if (pref.mode == UpdateFeed.Pref.Mode.OFF) "; `plymeld updates --set notify` turns it on" else ""))
        echo("this is plymeld ${PlymeldVersion.value}")
        echo("feed: " + (pref.feed ?: "none (a development build has no feed; feed=URL in ${pref.file} sets one)"))
        val seen = UpdateFeed.Seen.load(dirs)
        if (!check) {
            if (seen.checked != null) echo("last checked ${seen.checked}: " + (seen.newest?.takeIf { UpdateFeed.compareVersions(it, PlymeldVersion.value) > 0 }?.let { "plymeld $it is out" } ?: "nothing newer"))
            return
        }
        val feed = pref.feed ?: error("no update feed to ask: this build has none; set feed=URL in ${pref.file}")
        val channel = UpdateFeed.channel() ?: error("no release feed for this system (${System.getProperty("os.name")}): releases are published for Windows and Linux")
        val newer = UpdateFeed.newest(UpdateFeed.fetch(feed, channel), PlymeldVersion.value)
        UpdateFeed.Seen(java.time.Instant.now(), newer?.version, seen.told).save(dirs)
        echo(newer?.let { "plymeld ${it.version} is out: https://github.com/plymeld/plymeld/releases" } ?: "no newer release: this is the latest")
    }
}
