package plymeld.mesh

import java.io.InputStream
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * One printable body from a 3MF: a mesh object placed by a build item (possibly via
 * components), already transformed into the build's frame.
 *
 * `name` follows the workspace convention: the part name from
 * `Metadata/model_settings.config` when present, else the object's `name` attribute,
 * else `object_<id>`. `group` is the name of the build-item object when the part came
 * through `<components>` (e.g. group "base", parts "plate", "clamp_left", "clamp_right").
 *
 * From `Metadata/model_settings.config` (QIDIStudio/Orca/Bambu projects), as written there:
 * [item] the build item the part belongs to (one printed object per item; two instances of one
 * object are two items); [objectSettings] the object's setting keys and [partSettings] the part's
 * (everything but name, matrix, and source_*); [subtype] "normal_part", "modifier_part",
 * "negative_part", ... ("normal_part" when the file does not say).
 */
class ThreeMfPart(
    val name: String, val group: String?, val objectId: Int, val soup: TriangleSoup,
    val item: Int = 0,
    /** The build item's object (the project's object id; [objectId] is the mesh's). */
    val itemObjectId: Int = objectId,
    val objectSettings: Map<String, String> = emptyMap(),
    val partSettings: Map<String, String> = emptyMap(),
    val subtype: String = "normal_part",
)

/** A plate of a project file (`<plate>` in model_settings.config): its number and the build objects on it. */
class ThreeMfPlate(val id: Int, val objectIds: Set<Int>)

/** [plates]: empty when the file does not describe plates (then every build item is on the one plate). */
class ThreeMfModel(val parts: List<ThreeMfPart>, val plates: List<ThreeMfPlate> = emptyList())

/**
 * Streaming 3MF reader (core spec + production extension paths). XML is read with
 * StAX and never held as a DOM — the workspace's 3MF XML runs to 490 MB uncompressed.
 *
 * Supported: `<object>` with `<mesh>` (vertices, triangles) or `<components>` (with
 * `transform` and production-extension `p:path` to another .model in the archive),
 * `<build><item transform>`, `Metadata/model_settings.config` names. Not yet: materials,
 * colors, slice extension, beam lattice.
 */
object ThreeMfReader {

    private class MeshData {
        var vx = FloatArray(3 * 1024); var nv = 0     // xyz interleaved, count in floats
        var tri = IntArray(3 * 1024); var nt = 0       // v1 v2 v3 interleaved, count in ints
    }

    private class Component(val objectId: Int, val path: String?, val transform: DoubleArray?)

    private class ObjectDef(val id: Int, val name: String?, val mesh: MeshData?, val components: List<Component>)

    private class ModelFile(val objects: Map<Int, ObjectDef>, val buildItems: List<Component>)

    fun read(path: Path): ThreeMfModel {
        ZipFile(path.toFile()).use { zip ->
            val root = "3D/3dmodel.model"
            val entry = zip.getEntry(root) ?: error("no $root in $path")
            val files = HashMap<String, ModelFile>()
            fun load(name: String): ModelFile = files.getOrPut(name) {
                val e = zip.getEntry(name) ?: error("no $name in $path (referenced by a component)")
                zip.getInputStream(e).use { parseModel(it, name) }
            }
            val main = zip.getInputStream(entry).use { parseModel(it, root) }
            files[root] = main
            val names = zip.getEntry("Metadata/model_settings.config")?.let { e -> zip.getInputStream(e).use { parseNames(it) } }
                ?: Names(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList())

            val parts = ArrayList<ThreeMfPart>()
            for ((k, item) in main.buildItems.withIndex()) {
                val topObj = main.objects[item.objectId] ?: error("build item refers to missing object ${item.objectId}")
                val groupName = names.objectNames[topObj.id] ?: topObj.name
                emit(main, root, topObj, item.transform ?: IDENTITY, groupName, names, ::load, parts, top = true, item = k, top0 = topObj.id,
                    objSettings = names.objectSettings[topObj.id] ?: emptyMap())
            }
            return ThreeMfModel(parts, names.plates)
        }
    }

    private class Names(
        val objectNames: Map<Int, String>, val partNames: Map<Int, String>,
        val objectSettings: Map<Int, Map<String, String>>, val partSettings: Map<Int, Map<String, String>>, val partSubtypes: Map<Int, String>,
        val plates: List<ThreeMfPlate>,
    )

    private fun emit(
        file: ModelFile, fileName: String, obj: ObjectDef, xf: DoubleArray, group: String?,
        names: Names, load: (String) -> ModelFile, out: MutableList<ThreeMfPart>, top: Boolean,
        item: Int, top0: Int, objSettings: Map<String, String>,
    ) {
        if (obj.mesh != null) {
            val name = names.partNames[obj.id] ?: names.objectNames[obj.id] ?: obj.name ?: "object_${obj.id}"
            out.add(ThreeMfPart(name, if (top) null else group, obj.id, toSoup(obj.mesh, xf), item, top0, objSettings,
                names.partSettings[obj.id] ?: emptyMap(), names.partSubtypes[obj.id] ?: "normal_part"))
        }
        for (c in obj.components) {
            val f = if (c.path != null) load(c.path.trimStart('/')) else file
            val child = f.objects[c.objectId] ?: error("component refers to missing object ${c.objectId} in ${c.path ?: fileName}")
            emit(f, c.path ?: fileName, child, compose(xf, c.transform ?: IDENTITY), group, names, load, out, top = false, item, top0, objSettings)
        }
    }

    // ---- transforms: 3MF "m00 m01 m02 m10 m11 m12 m20 m21 m22 tx ty tz", row-vector convention:
    // p' = p * M + t, i.e. x' = x*m00 + y*m10 + z*m20 + tx.
    private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)

    /** child applied first, then parent: p' = (p*C + tc)*P + tp. */
    private fun compose(parent: DoubleArray, child: DoubleArray): DoubleArray {
        val r = DoubleArray(12)
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += child[i * 3 + k] * parent[k * 3 + j]
            r[i * 3 + j] = s
        }
        for (j in 0 until 3) {
            var s = parent[9 + j]
            for (k in 0 until 3) s += child[9 + k] * parent[k * 3 + j]
            r[9 + j] = s
        }
        return r
    }

    private fun toSoup(m: MeshData, xf: DoubleArray): TriangleSoup {
        val count = m.nt / 3
        val out = FloatArray(count * 9)
        var w = 0
        for (t in 0 until count) {
            for (c in 0 until 3) {
                val vi = m.tri[t * 3 + c] * 3
                val x = m.vx[vi].toDouble(); val y = m.vx[vi + 1].toDouble(); val z = m.vx[vi + 2].toDouble()
                out[w++] = (x * xf[0] + y * xf[3] + z * xf[6] + xf[9]).toFloat()
                out[w++] = (x * xf[1] + y * xf[4] + z * xf[7] + xf[10]).toFloat()
                out[w++] = (x * xf[2] + y * xf[5] + z * xf[8] + xf[11]).toFloat()
            }
        }
        return TriangleSoup(out, count)
    }

    private fun parseTransform(s: String?): DoubleArray? {
        if (s == null) return null
        val v = s.trim().split(Regex("\\s+")).map { it.toDouble() }
        require(v.size == 12) { "transform must have 12 numbers, got ${v.size}: '$s'" }
        return v.toDoubleArray()
    }

    private fun parseModel(input: InputStream, name: String): ModelFile {
        val f = XMLInputFactory.newInstance()
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        val r = f.createXMLStreamReader(input)
        val objects = HashMap<Int, ObjectDef>()
        val build = ArrayList<Component>()
        var curId = -1; var curName: String? = null; var mesh: MeshData? = null
        var comps = ArrayList<Component>()
        var inBuild = false
        try {
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "object" -> {
                            curId = r.attr("id")!!.toInt(); curName = r.attr("name"); mesh = null; comps = ArrayList()
                        }
                        "mesh" -> mesh = MeshData()
                        "vertex" -> {
                            val m = mesh ?: error("$name: <vertex> outside <mesh>")
                            if (m.nv + 3 > m.vx.size) m.vx = m.vx.copyOf(m.vx.size * 2)
                            m.vx[m.nv++] = r.attr("x")!!.toFloat(); m.vx[m.nv++] = r.attr("y")!!.toFloat(); m.vx[m.nv++] = r.attr("z")!!.toFloat()
                        }
                        "triangle" -> {
                            val m = mesh ?: error("$name: <triangle> outside <mesh>")
                            if (m.nt + 3 > m.tri.size) m.tri = m.tri.copyOf(m.tri.size * 2)
                            m.tri[m.nt++] = r.attr("v1")!!.toInt(); m.tri[m.nt++] = r.attr("v2")!!.toInt(); m.tri[m.nt++] = r.attr("v3")!!.toInt()
                        }
                        "component" -> comps.add(Component(r.attr("objectid")!!.toInt(), r.attr("path"), parseTransform(r.attr("transform"))))
                        "build" -> inBuild = true
                        "item" -> if (inBuild) build.add(Component(r.attr("objectid")!!.toInt(), null, parseTransform(r.attr("transform"))))
                    }
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "object" -> {
                            val m = mesh
                            if (m != null) {
                                val nverts = m.nv / 3
                                for (i in 0 until m.nt) require(m.tri[i] in 0 until nverts) { "$name: object $curId triangle index ${m.tri[i]} out of range ($nverts vertices)" }
                            }
                            objects[curId] = ObjectDef(curId, curName, m, comps)
                        }
                        "build" -> inBuild = false
                    }
                }
            }
        } finally {
            r.close()
        }
        return ModelFile(objects, build)
    }

    /** Attribute by local name, ignoring namespace prefixes (p:path, etc.). */
    private fun XMLStreamReader.attr(local: String): String? {
        for (i in 0 until attributeCount) if (getAttributeLocalName(i) == local) return getAttributeValue(i)
        return null
    }

    private fun parseNames(input: InputStream): Names {
        val f = XMLInputFactory.newInstance()
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        val r = f.createXMLStreamReader(input)
        val objs = HashMap<Int, String>(); val parts = HashMap<Int, String>()
        val objSet = HashMap<Int, LinkedHashMap<String, String>>(); val partSet = HashMap<Int, LinkedHashMap<String, String>>()
        val subtypes = HashMap<Int, String>()
        val plates = ArrayList<ThreeMfPlate>()
        var objId = -1; var partId = -1
        // inside <plate>: its plater_id, and the object_id of each <model_instance>
        var inPlate = false; var inInstance = false; var plateId = -1; var plateObjs = LinkedHashSet<Int>()
        try {
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "object" -> { objId = r.attr("id")!!.toInt(); partId = -1 }
                        "part" -> { partId = r.attr("id")!!.toInt(); r.attr("subtype")?.let { subtypes[partId] = it } }
                        "plate" -> { inPlate = true; plateId = -1; plateObjs = LinkedHashSet() }
                        "model_instance" -> inInstance = true
                        "metadata" -> {
                            val key = r.attr("key")
                            val v = r.attr("value") ?: ""
                            if (inPlate) {
                                if (inInstance && key == "object_id") plateObjs += v.toInt()
                                else if (!inInstance && key == "plater_id") plateId = v.toInt()
                            } else if (key == "name") {
                                if (partId >= 0) parts[partId] = v else if (objId >= 0) objs[objId] = v
                            } else if (key != null && key != "matrix" && !key.startsWith("source_")) {
                                if (partId >= 0) partSet.getOrPut(partId) { LinkedHashMap() }[key] = v
                                else if (objId >= 0) objSet.getOrPut(objId) { LinkedHashMap() }[key] = v
                            }
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "part" -> partId = -1
                        "object" -> objId = -1
                        "model_instance" -> inInstance = false
                        "plate" -> { inPlate = false; plates += ThreeMfPlate(if (plateId >= 0) plateId else plates.size + 1, plateObjs) }
                    }
                }
            }
        } finally {
            r.close()
        }
        return Names(objs, parts, objSet, partSet, subtypes, plates)
    }
}
