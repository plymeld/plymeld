package plymeld.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The generated JSON Schema accepts each declared value at every scope it may be set at, and refuses it elsewhere, as an
 * editor resolves it: by `properties`, `additionalProperties` and `$ref`. Found: the object scope was an allOf
 * over the closed plan table, so an editor refused every part-scoped value, `band.*`, `support.spot.*` and `pose.turn`.
 */
class SchemaJsonScopesTest : StringSpec({
    val doc = SchemaJson.document()
    val defs = doc.getValue("\$defs") as JsonObject

    fun deref(e: JsonElement): JsonElement {
        var n = e
        while (n is JsonObject && "\$ref" in n) n = defs.getValue(n.getValue("\$ref").jsonPrimitive.content.removePrefix("#/\$defs/"))
        return n
    }

    /** A node that may be a value or a table (`anyOf`, as a filament's `technique.<key>.<key>` is): its table branch for descending, else itself. */
    fun asTable(o: JsonObject): JsonObject = (o["anyOf"] as? JsonArray)?.map { deref(it) as JsonObject }?.firstOrNull { "properties" in it } ?: o

    /** Whether [path] (dotted, keys filled in) reaches a value in the document, not a table and not a refused key. */
    fun accepts(path: String): Boolean {
        var n: JsonElement = doc
        for (s in path.split('.')) {
            val o = asTable(deref(n) as? JsonObject ?: return false)
            val props = o["properties"] as? JsonObject
            n = props?.get(s) ?: (o["additionalProperties"]?.takeIf { it != JsonPrimitive(false) } ?: return false)
        }
        val leaf = deref(n) as JsonObject
        // a value, or a node that may be one (anyOf with a branch that is no table)
        return "properties" !in leaf || (leaf["anyOf"] as? JsonArray)?.any { "properties" !in (deref(it) as JsonObject) } == true
    }

    fun filled(segments: List<String>) = segments.joinToString(".") { if (it == "<key>") "k" else it }

    /** [d]'s path as written at [scope], or null for an object-level declaration at another scope than an object's. */
    fun at(d: Schema.Decl, scope: Schema.Scope): String? {
        val s = d.segments
        if (s.size > 3 && s[1] == "object" && s[2] == "<key>") return if (scope == Schema.Scope.OBJECT) filled(s) else null
        if (s[0] != "job" && s[0] != "plan") return if (scope == Schema.Scope.PLATE) filled(s) else null
        val rest = filled(s.drop(1))
        return when (scope) {
            Schema.Scope.PLATE -> "${s[0]}.$rest"
            Schema.Scope.OBJECT -> "${s[0]}.object.cube.$rest"
            Schema.Scope.PART -> if (s[0] == "plan") "plan.object.cube.part.body.$rest" else null
        }
    }

    "no allOf: a member closing the table on its own refuses the keys the others add" {
        SchemaJson.text() shouldNotContain "allOf"
    }

    "every declared value is accepted at each scope it may be set at, and refused at the others" {
        val wrong = mutableListOf<String>()
        for (d in Schema.decls) for (scope in Schema.Scope.entries) {
            val path = at(d, scope) ?: continue
            val objectLevel = d.segments.size > 3 && d.segments[1] == "object"
            val allowed = objectLevel || scope in Schema.scopesOf(d.segments)
            if (accepts(path) != allowed) wrong += "$path: ${if (allowed) "refused, allowed at $scope" else "accepted, not allowed at $scope"}"
        }
        wrong.shouldBeEmpty()
    }

    "the values the allOf refused are accepted" {
        accepts("plan.object.cube.part.body.layer.walls.outer.line.width") shouldBe true
        accepts("plan.object.cube.pose.turn") shouldBe true
        accepts("plan.object.cube.band.1.technique") shouldBe true
        accepts("plan.object.cube.support.spot.a.min") shouldBe true
    }

    "control: plate-only values, a part's pose, and an undeclared key are refused" {
        accepts("plan.object.cube.objects.order") shouldBe false
        accepts("plan.object.cube.part.body.pose.turn") shouldBe false
        accepts("plan.object.cube.layer.walls.nonsense") shouldBe false
        accepts("plan.object.cube.part.body.layer.walls.count") shouldBe false      // retired (the thickness now)
        accepts("plan.pose") shouldBe true
        accepts("plan.object.cube.pose") shouldBe false
    }
})
