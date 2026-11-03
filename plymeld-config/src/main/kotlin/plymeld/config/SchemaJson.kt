package plymeld.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The config schema as a JSON Schema document, generated
 * from [Schema] so it cannot drift from what plymeld reads. Editors use it for TOML (a
 * `#:schema plymeld.schema.json` first line) and JSON alike. Every quantity also takes a formula
 * (text starting with `=`); keys are free text; `job` and `plan` repeat under
 * `object.<key>` (and `plan` under `object.<key>.part.<key>`) as per-object and per-part scopes, each
 * with only the values [Schema.scopesOf] allows there.
 */
object SchemaJson {
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun text(): String = json.encodeToString(JsonElement.serializer(), document()) + "\n"

    private class Node {
        val children = LinkedHashMap<String, Node>()
        var decl: Schema.Decl? = null
    }

    private fun tree(paths: List<Pair<List<String>, Schema.Decl>>): Node {
        val root = Node()
        for ((segments, d) in paths) {
            var n = root
            for (s in segments) n = n.children.getOrPut(s) { Node() }
            n.decl = d
        }
        return root
    }

    /** A declaration written at an object's own level, such as `plan.object.<key>.pose.turn`. */
    private fun objectLevel(d: Schema.Decl) = d.segments.size > 3 && d.segments[1] == "object" && d.segments[2] == "<key>"

    fun document(): JsonObject {
        val root = tree(Schema.decls.map { it.segments to it })
        val defs = LinkedHashMap<String, JsonElement>()
        // each scope as its own closed table, from Schema.scopesOf: job and plan for the
        // plate; job.object / plan.object for one object (the values allowed there plus the object-level declarations);
        // plan.part for one part. No allOf over a closed $ref: such a member closes the table on its own and refuses the
        // keys the others add (the object-level ones, and pose, a choice for the plate but a table for an object)
        for (scope in listOf("job", "plan")) {
            val own = Schema.decls.filter { it.segments[0] == scope && !objectLevel(it) }
            fun at(s: Schema.Scope) = own.filter { s in Schema.scopesOf(it.segments) }.map { it.segments.drop(1) to it }
            val objectDecls = Schema.decls.filter { it.segments[0] == scope && objectLevel(it) }.map { it.segments.drop(3) to it }
            defs[scope] = node(tree(at(Schema.Scope.PLATE)), "object" to keyed("#/\$defs/$scope.object",
                "per-object values, by object key (`name`, `name#2` ...); they win over the general ones"))
            defs["$scope.object"] = node(tree(at(Schema.Scope.OBJECT) + objectDecls),
                if (scope == "plan") "part" to keyed("#/\$defs/plan.part", "per-part values, by the part's name; they win over the object's") else null)
            if (scope == "plan") defs["plan.part"] = node(tree(at(Schema.Scope.PART)), null)
        }
        return obj(
            "\$schema" to str("https://json-schema.org/draft/2020-12/schema"),
            "title" to str("plymeld config"),
            "description" to str("plymeld's config. Generated from plymeld-config Schema.kt by `plymeld schema`; do not edit."),
            "type" to str("object"),
            "additionalProperties" to JsonPrimitive(false),
            // a JSON config's own `$schema` (which schema an editor checks it against) is allowed at the top; plymeld ignores it
            "properties" to JsonObject(linkedMapOf<String, JsonElement>("\$schema" to obj("description" to str("the schema an editor checks this file against (ignored by plymeld)"), "type" to str("string"))) +
                root.children.mapValues { (k, n) ->
                    when (k) { "job" -> ref("#/\$defs/job"); "plan" -> ref("#/\$defs/plan"); else -> node(n, null) }
                }),
            "\$defs" to JsonObject(defs))
    }

    /** A node of the path tree, closed to keys it does not declare; [extra]: one more table under it (a scope's `object` or `part`). */
    private fun node(n: Node, extra: Pair<String, JsonElement>?): JsonElement {
        // a path that is a value AND has values under it (a filament's technique.<key>.<key> is a value, and
        // technique.<key>.<key>.<key> one more level down): either shape is accepted
        val asLeaf = n.decl?.let { leaf(it) }
        if (asLeaf != null && n.children.isEmpty()) return asLeaf
        val props = LinkedHashMap<String, JsonElement>()
        var wildcard: JsonElement? = null
        for ((k, c) in n.children) {
            val sub = node(c, null)
            if (k == "<key>") wildcard = sub else props[k] = sub
        }
        extra?.let { (k, v) -> props[k] = v }
        val asObject = obj("type" to str("object"), "additionalProperties" to (wildcard ?: JsonPrimitive(false)), "properties" to JsonObject(props))
        return if (asLeaf == null) asObject else obj("anyOf" to JsonArray(listOf(asLeaf, asObject)))
    }

    /** A table of tables by key, each the scope [to]. */
    private fun keyed(to: String, description: String) = obj("type" to str("object"), "description" to str(description), "additionalProperties" to ref(to))

    private fun leaf(d: Schema.Decl): JsonElement {
        val desc = str(d.meaning)
        return when (val t = d.type) {
            // a quantity with its unit in quotes, or a bare number where it has none to give; a boolean true / false, quoted or not
            is Schema.Type.Quantity -> obj("description" to str("${d.meaning} — ${t.label}; or a formula (= ...)"), "type" to JsonArray(listOf(str("string"), str("number"))))
            is Schema.Type.Bool -> obj("description" to str("${d.meaning} — true or false; or a formula (= ...)"), "anyOf" to JsonArray(listOf(obj("type" to str("boolean")),
                obj("enum" to JsonArray(listOf(str("true"), str("false")))), obj("type" to str("string"), "pattern" to str("^\\s*=")))))
            is Schema.Type.Text -> obj("description" to desc, "type" to str("string"))
            is Schema.Type.Choice -> obj("description" to desc, "enum" to JsonArray(t.options.map(::str)))
            is Schema.Type.Ref -> obj("description" to str("${d.meaning} (${t.label})"), "type" to str("string"))
            is Schema.Type.Choices -> obj("description" to desc, "type" to str("array"), "items" to obj("enum" to JsonArray(t.options.map(::str))))
            is Schema.Type.Numbers -> obj("description" to str("${d.meaning} — ${t.size} numbers: an array, or one quoted value separated by spaces"),
                "anyOf" to JsonArray(listOf(obj("type" to str("string")), obj("type" to str("array"), "items" to obj("type" to str("number")), "minItems" to JsonPrimitive(t.size), "maxItems" to JsonPrimitive(t.size)))))
            is Schema.Type.Point -> obj("description" to str("${d.meaning} — ${t.label}; or an array of 3 numbers (${t.dim.base})"),
                "anyOf" to JsonArray(listOf(obj("type" to str("string")), obj("type" to str("array"), "items" to obj("type" to str("number")), "minItems" to JsonPrimitive(3), "maxItems" to JsonPrimitive(3)))))
        }
    }

    private fun ref(to: String) = obj("\$ref" to str(to))
    private fun str(s: String) = JsonPrimitive(s)
    private fun obj(vararg kv: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*kv))
}
