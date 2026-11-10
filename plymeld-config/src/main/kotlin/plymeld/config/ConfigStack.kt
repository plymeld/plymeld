package plymeld.config

import java.nio.file.Path

/**
 * The config sources of one run, layered: config files in the
 * order given, then `--config-value`s in order. For the same path the later source wins, except that two
 * files of one numbered group may not set the same path ([ConfigFiles.readFiles]). A value
 * set for an object or part wins over the general one whatever the order — see [Config.scoped].
 */
class ConfigStack(given: List<ConfigFiles.Entry>) {
    /** A note for each entry read under a setting's new name or left out as removed ([Migrations]): the config's warnings. */
    val migrated = ArrayList<String>()
    /**
     * Every entry as given, in order (for a stack built from this one with some left out), a chosen value spelled the British
     * way read the American way ([Spelling]), a renamed setting under its new name ([Migrations], before the later source
     * wins, so an old name and a new one layer by their sources' order).
     */
    val all: List<ConfigFiles.Entry> = given.mapNotNull { g -> Migrations.migrate(Spelling.entry(g)).let { r -> r.note?.let(migrated::add); r.entry } }
    /** Each path's value from the last source that set it, in first-seen path order. */
    val effective: Map<String, ConfigFiles.Entry> = LinkedHashMap<String, ConfigFiles.Entry>().also { m -> for (e in all) { m.remove(e.key); m[e.key] = e } }
    /** Every entry that was overridden by a later source at the same path (for `config explain`). */
    val overridden: List<ConfigFiles.Entry> = all.filter { effective[it.key] !== it }

    fun entry(path: List<String>): ConfigFiles.Entry? = effective[ConfigFiles.pathText(path)]

    companion object {
        /** The stack for `--config-file` arguments (files or globs) and `--config-value` arguments, after plymeld's bundled files. */
        fun load(fileArgs: List<String>, valueArgs: List<String>, cwd: Path = Path.of("").toAbsolutePath(), bundled: Boolean = true): ConfigStack =
            ConfigStack((if (bundled) ConfigFiles.bundled() else emptyList()) +
                ConfigFiles.readFiles(fileArgs, cwd) + valueArgs.map { ConfigFiles.value(it) })
    }
}
