package plymeld.commands

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * The journal of edits: a JSON file beside the job's plan file (`<plan>.journal.json`)
 * holding each edit as the command that made it, the file it changed, and that file's whole text before and after.
 * `plymeld edit --journal` appends; `plymeld journal --undo` writes a file's "before" back and `--redo` its "after";
 * a cursor says how many entries are applied, so an undone edit stays listed until a new edit replaces it. The window's
 * undo and redo are these commands; the console's command lines replay the session.
 *
 * Whole texts, not line ranges: an edit's effect on a file is then restored exactly whatever else happened to the
 * lines around it, and undo refuses when the file no longer reads as the edit left it (someone else wrote it).
 */
class Journal(val path: Path, entries: List<Entry> = emptyList(), done: Int = entries.size) {
    class Entry(val at: String, val command: String, val file: String, val before: String, val after: String)

    var entries: List<Entry> = entries; private set
    /** How many entries, from the first, are applied; the rest were undone. */
    var done: Int = done; private set
    val canUndo: Boolean get() = done > 0
    val canRedo: Boolean get() = done < entries.size

    /** A new edit after the applied ones; what was undone is dropped (it cannot be redone over a different edit). */
    fun append(e: Entry) { entries = entries.take(done) + e; done = entries.size; save() }

    /** The last applied edit taken back: its file written as it was before. Refused when the file is not as the edit left it. */
    fun undo(): Entry {
        check(canUndo) { "nothing to undo in $path" }
        val e = entries[done - 1]
        val f = Path.of(e.file)
        val now = if (Files.exists(f)) Files.readString(f) else ""
        check(now == e.after) { "${e.file} is not as the edit left it (changed since \"${e.command}\"); not undoing over it" }
        Files.writeString(f, e.before)
        done--; save()
        return e
    }

    /** The last undone edit done again: its file written as the edit left it. Refused when the file is not as it was before the edit. */
    fun redo(): Entry {
        check(canRedo) { "nothing to redo in $path" }
        val e = entries[done]
        val f = Path.of(e.file)
        val now = if (Files.exists(f)) Files.readString(f) else ""
        check(now == e.before) { "${e.file} is not as it was before \"${e.command}\"; not redoing over it" }
        Files.writeString(f, e.after)
        done++; save()
        return e
    }

    fun save() {
        val root = JsonObject(mapOf(
            "about" to JsonPrimitive("plymeld's journal of edits: each as its command, the file, its text before and after; done = how many are applied"),
            "done" to JsonPrimitive(done),
            "edits" to JsonArray(entries.map { e -> JsonObject(mapOf("at" to JsonPrimitive(e.at), "command" to JsonPrimitive(e.command), "file" to JsonPrimitive(e.file), "before" to JsonPrimitive(e.before), "after" to JsonPrimitive(e.after))) })))
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(path, PRETTY.encodeToString(JsonObject.serializer(), root) + "\n")
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true }

        /** The journal at [path], empty when there is none yet. */
        fun read(path: Path): Journal {
            if (!Files.exists(path)) return Journal(path)
            val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
            val entries = root["edits"]?.jsonArray?.map { el -> val o = el.jsonObject
                Entry(o["at"]!!.jsonPrimitive.content, o["command"]!!.jsonPrimitive.content, o["file"]!!.jsonPrimitive.content, o["before"]!!.jsonPrimitive.content, o["after"]!!.jsonPrimitive.content) } ?: emptyList()
            return Journal(path, entries, root["done"]?.jsonPrimitive?.int ?: entries.size)
        }

        /** Where a job's journal lives: beside its plan file, `<plan>.journal.json`. */
        fun beside(planFile: Path): Path = planFile.resolveSibling(planFile.fileName.toString() + ".journal.json")

        fun now(): String = java.time.OffsetDateTime.now().withNano(0).toString()
    }
}
