package com.lumi.mmd.engine.mcp

import com.lumi.mmd.engine.mesh.EditMesh
import com.lumi.mmd.engine.mesh.MeshPrimitives
import com.lumi.mmd.engine.model.MaterialData
import com.lumi.mmd.engine.scene.MeshSession
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.util.jArr

/* 建模相关的 MCP 工具（从 SceneTools 里拆出来的另一半）。 */

private fun SceneTools.session(): MeshSession = host.meshSession ?: fail("Not editing a mesh. Call mesh_begin (existing model) or create_model first.")

private fun SceneTools.materialIndex(s: MeshSession, v: Any?, default: Int = 0): Int = when (v) {
    null -> default
    is Number -> v.toInt().also { if (it !in s.mesh.materials.indices) fail("Material index $it out of range (0..${s.mesh.materials.size - 1}).") }
    else -> {
        var i = s.mesh.materials.indexOfFirst { it.name == v.toString() }
        if (i < 0) asIndex(v.toString())?.let { n -> if (n !in s.mesh.materials.indices) fail("Material index $n out of range (0..${s.mesh.materials.size - 1})."); i = n }
        if (i < 0) fail("No material named '$v'. Use mesh_material list.")
        i
    }
}

private fun SceneTools.boneIndexIn(s: MeshSession, v: Any?): Int = when (v) {
    null -> -1
    is Number -> v.toInt().also { if (it !in s.mesh.bones.indices) fail("Bone index $it out of range.") }
    else -> {
        var i = s.mesh.bones.indexOfFirst { it.name == v.toString() }
        if (i < 0) asIndex(v.toString())?.let { n -> if (n !in s.mesh.bones.indices) fail("Bone index $n out of range."); i = n }
        if (i < 0) fail("No bone named '$v' in this model.")
        i
    }
}

private fun SceneTools.scale3(a: Map<String, Any?>, key: String): FloatArray? = when (val v = a[key]) {
    is Number -> floatArrayOf(v.toFloat(), v.toFloat(), v.toFloat())
    is String -> v.trim().toFloatOrNull()?.let { floatArrayOf(it, it, it) } ?: arrOf(v)?.let { scaleOf(it, key) }
    is List<*> -> scaleOf(v, key)
    else -> null
}

/** [s] = 三个方向一样大，[x,y,z] = 各方向分别给。 */
private fun SceneTools.scaleOf(l: List<Any?>, key: String): FloatArray = when {
    l.size == 1 -> num(l[0], key).let { floatArrayOf(it, it, it) }
    l.size >= 3 -> FloatArray(3) { num(l[it], key) }
    else -> fail("'$key' must be [s] (uniform) or [x,y,z]")
}

private fun SceneTools.placement(a: Map<String, Any?>): FloatArray? {
    val p = a.vec("position", 3); val r = a.vec("rotation", 3); val sc = scale3(a, "scale")
    if (p == null && r == null && sc == null) return null
    return MeshSession.placement(p?.get(0) ?: 0f, p?.get(1) ?: 0f, p?.get(2) ?: 0f, r?.get(0) ?: 0f, r?.get(1) ?: 0f, r?.get(2) ?: 0f, sc?.get(0) ?: 1f, sc?.get(1) ?: 1f, sc?.get(2) ?: 1f)
}

private fun SceneTools.meshSummary(s: MeshSession): Map<String, Any?> {
    val st = s.stats()
    val mn = FloatArray(3); val mx = FloatArray(3)
    val has = s.mesh.bounds(mn, mx)
    return linkedMapOf("model" to s.model.id, "points" to st.points, "faces" to st.faces,
        "selected" to linkedMapOf("mode" to arrayOf("point", "edge", "face")[s.mesh.selMode], "points" to st.selPoints, "edges" to st.selEdges, "faces" to st.selFaces),
        "bounds" to if (has) linkedMapOf("min" to vecOut(mn), "max" to vecOut(mx)) else null)
}

private fun SceneTools.materialList(s: MeshSession): List<Map<String, Any?>> {
    val m = s.mesh
    val count = IntArray(m.materials.size)
    for (f in 0 until m.faceCount) if (!m.fDead[f] && m.fMat[f] in count.indices) count[m.fMat[f]]++
    return m.materials.mapIndexed { i, md ->
        linkedMapOf("index" to i, "name" to md.name, "color" to vecOut(md.diffuse), "alpha" to round(md.diffuse[3]), "faces" to count[i],
            "texture" to md.texture.takeIf { it in m.textures.indices }?.let { m.textures[it].path })
    }
}

/** [[a,b],[c,d],...] → 扁平的 FloatArray。 */
private fun SceneTools.pairs(v: Any?, what: String): FloatArray {
    val pts = arrOf(v) ?: fail("Missing `$what`.")
    val out = FloatArray(pts.size * 2)
    for ((i, p) in pts.withIndex()) {
        val l = p.jArr() ?: fail("`$what` items must be 2-element arrays")
        if (l.size < 2) fail("`$what` items must be 2-element arrays")
        out[i * 2] = num(l[0], what); out[i * 2 + 1] = num(l[1], what)
    }
    return out
}

internal fun SceneTools.registerMesh() {
    tool("create_model", "Create a new empty model and start editing its mesh. Then add geometry with mesh_add_primitive / mesh_add_geometry.",
        linkedMapOf("name" to pStr("Model name."), "kind" to pEnum("character / stage / accessory (default accessory).", "character", "stage", "accessory"))) { a ->
        ok(host.render {
            val name = a.s("name")?.takeIf { it.isNotBlank() } ?: "新模型"
            val em = EditMesh.createEmpty(name)
            val m = host.addModel(em.build().data, kindOf(a.s("kind")) ?: ModelInstance.KIND_ACCESSORY)
            m.name = name
            val s = host.beginMesh(m)
            host.changed()
            meshSummary(s) + linkedMapOf("materials" to materialList(s))
        })
    }

    tool("mesh_begin", "Start editing the mesh of an existing model (the model is shown in its rest pose while editing).", linkedMapOf("model" to pModel)) { a ->
        ok(host.render { val s = host.beginMesh(model(a)); host.changed(); meshSummary(s) + linkedMapOf("materials" to materialList(s)) })
    }

    tool("mesh_end", "Finish mesh editing. By default the model is saved as a .pmx file in the library (path returned).",
        linkedMapOf("save" to pBool("Save to the library (default true)."), "name" to pStr("File name without extension (default: model name)."))) { a ->
        ok(host.render {
            val s = session()
            val id = s.model.id
            host.endMesh()
            val cur = host.scene.findById(id) ?: fail("The model disappeared.")
            val path = if (a.b("save", true)) host.saveModel(cur, a.s("name")) else null
            host.changed()
            linkedMapOf("model" to cur.id, "saved" to path, "vertices" to cur.data.vertexCount, "triangles" to cur.data.triangleCount)
        })
    }

    tool("mesh_info", "Inspect the mesh being edited. detail=summary: counts, bounds, materials, selection. detail=points: point positions. detail=faces: faces with their point indices, normal, centre and material. Use offset / limit to page.",
        linkedMapOf("detail" to pEnum("What to return (default summary).", "summary", "points", "faces"), "selected_only" to pBool("Only selected elements."),
            "offset" to pInt("First index to return."), "limit" to pInt("Max elements (default 200, max 2000)."))) { a ->
        ok(host.render {
            val s = session()
            val m = s.mesh
            val off = a.i("offset", 0).coerceAtLeast(0); val lim = a.i("limit", 200).coerceIn(1, 2000)
            val only = a.b("selected_only", false)
            when (a.s("detail") ?: "summary") {
                "points" -> {
                    m.syncSelection()
                    val out = ArrayList<List<Any>>()
                    var i = off
                    while (i < m.pointCount && out.size < lim) {
                        if (!m.pDead[i] && (!only || m.pSel[i])) out.add(listOf(i, round(m.pos[i * 3]), round(m.pos[i * 3 + 1]), round(m.pos[i * 3 + 2])))
                        i++
                    }
                    linkedMapOf("format" to "[index, x, y, z]", "points" to out, "next_offset" to if (i < m.pointCount) i else null)
                }
                "faces" -> {
                    m.syncSelection()
                    val out = ArrayList<Map<String, Any?>>()
                    val n = FloatArray(3); val c = FloatArray(3)
                    var i = off
                    while (i < m.faceCount && out.size < lim) {
                        if (!m.fDead[i] && (!only || m.fSel[i])) {
                            m.faceNormal(i, n); m.faceCenter(i, c)
                            out.add(linkedMapOf("index" to i, "points" to List(m.fSize[i]) { k -> m.cPoint[m.fStart[i] + k] }, "normal" to vecOut(n), "center" to vecOut(c), "material" to m.fMat[i]))
                        }
                        i++
                    }
                    linkedMapOf("faces" to out, "next_offset" to if (i < m.faceCount) i else null)
                }
                else -> meshSummary(s) + linkedMapOf("materials" to materialList(s), "bones" to m.bones.size, "can_undo" to s.canUndo)
            }
        })
    }

    val common = linkedMapOf(
        "position" to pVec(3, "Where to put it (model space)."), "rotation" to pVec(3, "Euler degrees [x,y,z]."),
        "scale" to pArr(linkedMapOf("type" to "number"), "Extra scale: [x,y,z], or a single value [s] for a uniform scale."),
        "material" to pRef("Material index or name (default 0)."), "bone" to pRef("Bind the new geometry to this bone so that it follows it (default: first bone)."),
    )
    tool("mesh_add_primitive", "Add a basic shape to the mesh and select it. box / sphere / cylinder / cone / torus are centred on `position`; plane lies flat at `position`; lathe and prism start at y=0 before placement. Sizes are in model units (a character is about 20 tall).",
        linkedMapOf(
            "type" to pEnum("Shape.", "box", "sphere", "cylinder", "cone", "torus", "plane", "lathe", "prism"),
            "size" to pVec(3, "box: [width(x), height(y), depth(z)]; plane: [width(x), depth(z), 0]."),
            "radius" to pNum("sphere / cylinder / cone base / torus ring radius."), "radius_top" to pNum("cylinder: top radius (default = radius)."),
            "tube" to pNum("torus: tube radius."), "height" to pNum("cylinder / cone / prism height."),
            "segments" to pInt("Segments around (default 24)."), "rings" to pInt("sphere: rings top to bottom (default segments/2); torus: sides of the tube (default 12); plane: subdivisions (default 1)."),
            "profile" to pArr(pVec(2, "[radius, y]"), "lathe: outline points from bottom to top along the OUTER surface; revolved around the Y axis. radius 0 closes to a tip."),
            "outline" to pArr(pVec(2, "[x, z]"), "prism: polygon in the XZ plane (may be concave), extruded up by `height`."),
            "caps" to pBool("lathe: close open ends with flat caps."),
        ) + common, listOf("type")) { a ->
        ok(host.render {
            val s = session()
            val xf = placement(a)
            val mat = materialIndex(s, a["material"])
            val bone = boneIndexIn(s, a["bone"])
            val seg = a.i("segments", 24)
            val beforeF = s.mesh.faceCount; val beforeP = s.mesh.pointCount
            val n = when (a.s("type")) {
                "box" -> { val z = a.vec("size", 3) ?: floatArrayOf(1f, 1f, 1f); s.addBox(z[0], z[1], z[2], xf, mat, bone) }
                "sphere" -> s.addSphere(a.f("radius", 1f), seg, a.i("rings", maxOf(2, seg / 2)), xf, mat, bone)
                "cylinder" -> s.addCylinder(a.f("radius", 1f), a.f("radius_top", a.f("radius", 1f)), a.f("height", 2f), seg, xf, mat, bone)
                "cone" -> s.addCylinder(a.f("radius", 1f), 0f, a.f("height", 2f), seg, xf, mat, bone)
                "torus" -> s.addTorus(a.f("radius", 1f), a.f("tube", 0.25f), seg, a.i("rings", 12), xf, mat, bone)
                "plane" -> { val z = a.vec("size", 3) ?: floatArrayOf(1f, 1f, 0f); val d = a.i("rings", 1); s.addPlane(z[0], z[1], d, d, xf, mat, bone) }
                "lathe" -> { val prof = pairs(a["profile"], "profile"); s.addShape(bone) { MeshPrimitives.lathe(it, prof, seg, a.b("caps", false), mat, xf) } }
                "prism" -> { val ol = pairs(a["outline"], "outline"); s.addShape(bone) { MeshPrimitives.prism(it, ol, a.f("height", 1f), mat, xf) } }
                else -> fail("Unknown type. Use box, sphere, cylinder, cone, torus, plane, lathe or prism.")
            }
            host.changed()
            linkedMapOf("added_faces" to n, "face_range" to listOf(beforeF, s.mesh.faceCount - 1), "point_range" to listOf(beforeP, s.mesh.pointCount - 1)) + meshSummary(s)
        })
    }

    tool("mesh_add_geometry", "Add arbitrary geometry from vertex and face lists and select it. Faces may have 3 or more vertices.",
        linkedMapOf(
            "vertices" to pArr(pVec(3, "[x,y,z]"), "Vertex positions."), "faces" to pArr(pArr(linkedMapOf("type" to "integer"), "Vertex indices of one face."), "Faces as lists of indices into `vertices`."),
            "uvs" to pArr(pVec(2, "[u,v]"), "Optional per-vertex texture coordinates."),
            "winding" to pEnum("Order of each face's vertices seen from outside: cw (MMD default) or ccw (OBJ / Blender / glTF style).", "cw", "ccw"),
            "smooth" to pBool("Smooth shading (default true). false = flat faces."),
        ) + common, listOf("vertices", "faces")) { a ->
        val vs = arrOf(a["vertices"]) ?: fail("Missing vertices")
        val fs = arrOf(a["faces"]) ?: fail("Missing faces")
        val verts = FloatArray(vs.size * 3)
        for ((i, v) in vs.withIndex()) {
            val l = v.jArr() ?: fail("vertices must be [x,y,z] arrays")
            if (l.size < 3) fail("vertices must be [x,y,z]")
            for (k in 0 until 3) verts[i * 3 + k] = num(l[k], "vertex coordinate")
        }
        val faces = fs.map { f -> val l = f.jArr() ?: fail("faces must be arrays of indices"); IntArray(l.size) { num(l[it], "face index").toInt() } }
        val uvs = a["uvs"]?.let { pairs(it, "uvs") }
        ok(host.render {
            val s = session()
            val beforeF = s.mesh.faceCount; val beforeP = s.mesh.pointCount
            val xf = placement(a)
            val mat = materialIndex(s, a["material"])
            val n = s.addShape(boneIndexIn(s, a["bone"])) { MeshPrimitives.custom(it, verts, faces, uvs, a.s("winding") == "ccw", a.b("smooth", true), mat, xf) }
            host.changed()
            linkedMapOf("added_faces" to n, "skipped_faces" to (faces.size - n), "face_range" to listOf(beforeF, s.mesh.faceCount - 1), "point_range" to listOf(beforeP, s.mesh.pointCount - 1)) + meshSummary(s)
        })
    }

    tool("mesh_select", "Change the selection of the mesh being edited. Most mesh_op operations act on the selection.",
        linkedMapOf(
            "action" to pEnum("all / none / invert / linked (whole connected pieces) / grow / faces (by index) / points (by index) / normal (faces facing a direction) / box (inside a box) / material.",
                "all", "none", "invert", "linked", "grow", "faces", "points", "normal", "box", "material"),
            "indices" to pArr(linkedMapOf("type" to "integer"), "faces / points: indices to select."),
            "direction" to pVec(3, "normal: direction the faces should point to, e.g. [0,1,0] = facing up."), "angle" to pNum("normal: tolerance in degrees (default 30)."),
            "min" to pVec(3, "box: minimum corner."), "max" to pVec(3, "box: maximum corner."), "elements" to pEnum("box: select points or faces (default faces, by face centre).", "points", "faces"),
            "material" to pRef("material: index or name. With normal: restrict to this material."), "add" to pBool("Add to the current selection instead of replacing it."),
            "mode" to pEnum("Switch the selection mode first.", "point", "edge", "face"),
        ), listOf("action")) { a ->
        ok(host.render {
            val s = session()
            val add = a.b("add", false)
            when (a.s("mode")) { "point" -> s.selMode = EditMesh.SEL_POINT; "edge" -> s.selMode = EditMesh.SEL_EDGE; "face" -> s.selMode = EditMesh.SEL_FACE }
            fun indices(): IntArray = (arrOf(a["indices"]) ?: fail("Missing indices")).map { num(it, "index").toInt() }.toIntArray()
            when (a.s("action")) {
                "all" -> s.selectAll()
                "none" -> s.clearSelection()
                "invert" -> s.invertSelection()
                "linked" -> s.selectLinked()
                "grow" -> s.growSelection()
                "faces" -> s.selectFaces(indices(), add)
                "points" -> s.selectPointList(indices(), add)
                "normal" -> { val d = a.vec("direction", 3) ?: fail("Missing direction"); s.selectFacesByNormal(d[0], d[1], d[2], a.f("angle", 30f), add, if (a["material"] != null) materialIndex(s, a["material"]) else -1) }
                "box" -> s.selectInBox(a.vec("min", 3) ?: fail("Missing min"), a.vec("max", 3) ?: fail("Missing max"), a.s("elements") != "points", add)
                "material" -> s.selectMaterial(materialIndex(s, a["material"]), add)
                else -> fail("Unknown action.")
            }
            host.changed()
            meshSummary(s)
        })
    }

    tool("mesh_transform", "Move, rotate and/or scale the selected points. Scale and rotation pivot on the selection centre; order: scale, rotate, translate.",
        linkedMapOf("translate" to pVec(3, "Offset [x,y,z]."), "rotate" to pVec(3, "Euler degrees [x,y,z]."), "scale" to pArr(linkedMapOf("type" to "number"), "[x,y,z], or a single value [s] for a uniform scale."))) { a ->
        ok(host.render {
            val s = session()
            val t = a.vec("translate", 3) ?: FloatArray(3)
            val r = a.vec("rotate", 3) ?: FloatArray(3)
            val sc = scale3(a, "scale") ?: floatArrayOf(1f, 1f, 1f)
            val n = s.transformSelection(t[0], t[1], t[2], r[0], r[1], r[2], sc[0], sc[1], sc[2])
            if (n == 0) fail("Nothing is selected. Use mesh_select first.")
            host.changed()
            linkedMapOf("moved_points" to n) + meshSummary(s)
        })
    }

    tool("mesh_set_points", "Set exact positions of individual points.", linkedMapOf("points" to pArr(pVec(4, "[index, x, y, z]"), "Points to move.")), listOf("points")) { a ->
        val list = arrOf(a["points"]) ?: fail("Missing points")
        val idx = IntArray(list.size); val pos = FloatArray(list.size * 3)
        for ((i, p) in list.withIndex()) {
            val l = p.jArr() ?: fail("points must be [index,x,y,z]")
            if (l.size < 4) fail("points must be [index,x,y,z]")
            idx[i] = num(l[0], "index").toInt()
            for (k in 0 until 3) pos[i * 3 + k] = num(l[k + 1], "coordinate")
        }
        ok(host.render { val s = session(); val n = s.setPoints(idx, pos); host.changed(); linkedMapOf("moved_points" to n) + meshSummary(s) })
    }

    tool("mesh_op", "Run a modelling operation on the current selection.\n" +
        "extrude: pull the selected faces out along their normal by `distance` (they stay selected: repeat, or shape with mesh_transform).\n" +
        "inset: shrink each selected face by `amount` (0..1), optional `depth` along the normal.\n" +
        "subdivide / triangulate: split the selected faces. loop_cut: cut a ring of quads from the selected edge at `t` (0..1).\n" +
        "delete: remove the selection. duplicate: copy the selected faces in place. flip: reverse face direction.\n" +
        "merge: collapse the selected points to their centre. weld: merge points closer than `epsilon` (whole mesh if nothing is selected).\n" +
        "fill: make a face from the selected points. mirror: mirrored copy across `axis` (whole mesh if nothing is selected). solidify: give thin surfaces a `thickness`.\n" +
        "smooth: relax the selected points (`factor`, `iterations`). shade_smooth / shade_flat: shading of the selected faces. project_uv: box-project texture coordinates. dissolve: remove the selected edges, merging faces.",
        linkedMapOf(
            "op" to pEnum("Operation.", "extrude", "inset", "subdivide", "triangulate", "loop_cut", "delete", "duplicate", "flip", "merge", "weld", "fill", "mirror", "solidify", "smooth", "shade_smooth", "shade_flat", "project_uv", "dissolve"),
            "distance" to pNum("extrude: distance (default 0)."), "amount" to pNum("inset: 0..1 (default 0.2)."), "depth" to pNum("inset: offset along the normal."),
            "t" to pNum("loop_cut: position 0..1 (default 0.5)."), "epsilon" to pNum("weld: distance (default 0.001)."), "axis" to pEnum("mirror axis (default x = left / right).", "x", "y", "z"),
            "thickness" to pNum("solidify: thickness."), "factor" to pNum("smooth: 0..1 (default 0.5)."), "iterations" to pInt("smooth: repeats (default 1)."),
        ), listOf("op")) { a ->
        ok(host.render {
            val s = session()
            val op = a.s("op") ?: fail("Missing op")
            val n: Int = when (op) {
                "extrude" -> s.extrude(a.f("distance", 0f))
                "inset" -> s.inset(a.f("amount", 0.2f), a.f("depth", 0f))
                "subdivide" -> s.subdivide()
                "triangulate" -> s.triangulate()
                "loop_cut" -> s.loopCut(a.f("t", 0.5f))
                "delete" -> s.deleteSelected()
                "duplicate" -> s.duplicate()
                "flip" -> s.flipNormals()
                "merge" -> s.mergeToCenter()
                "weld" -> s.weld(a.f("epsilon", 0.001f)).let { if (it == 0) -1 else it }
                "fill" -> if (s.fill()) 1 else 0
                "mirror" -> s.mirror(when (a.s("axis")) { "y" -> 1; "z" -> 2; else -> 0 })
                "solidify" -> s.solidify(a.f("thickness", 0.1f))
                "smooth" -> s.smooth(a.f("factor", 0.5f), a.i("iterations", 1))
                "shade_smooth" -> s.setSmoothShading(true)
                "shade_flat" -> s.setSmoothShading(false)
                "project_uv" -> s.projectUV()
                "dissolve" -> s.dissolveEdges()
                else -> fail("Unknown op '$op'.")
            }
            if (n == 0) fail("'$op' had nothing to work on: select the right kind of elements first (faces for extrude / inset / subdivide, one edge for loop_cut, 3+ points for fill, 2+ points for merge).")
            host.changed()
            linkedMapOf("op" to op, "affected" to maxOf(n, 0)) + meshSummary(s)
        })
    }

    tool("mesh_material", "Manage the materials of the mesh being edited. list: show materials. add: create one (returns its index). set: change properties. assign: give the selected faces a material.",
        linkedMapOf(
            "action" to pEnum("What to do.", "list", "add", "set", "assign"), "material" to pRef("Material index or name (set / assign)."), "name" to pStr("Name (add / set)."),
            "color" to pVec(3, "Diffuse colour [r,g,b], each 0..1."), "alpha" to pNum("Opacity 0..1."), "edge" to pBool("Draw the toon outline."), "edge_color" to pVec(3, "Outline colour."),
            "double_sided" to pBool("Render both sides."), "specular" to pNum("Highlight strength 0..1."), "texture" to pStr("Image path relative to the model's folder in the library; empty string removes it."),
        ), listOf("action")) { a ->
        ok(host.render {
            val s = session()
            fun apply(m: MaterialData) {
                a.s("name")?.takeIf { it.isNotBlank() }?.let { m.name = it }
                a.vec("color", 3)?.let { for (k in 0 until 3) { m.diffuse[k] = it[k].coerceIn(0f, 1f); m.ambient[k] = m.diffuse[k] * 0.5f } }
                if (a["alpha"] is Number) m.diffuse[3] = a.f("alpha", 1f).coerceIn(0f, 1f)
                if (a["edge"] is Boolean) m.flags = if (a.b("edge", true)) m.flags or MaterialData.FLAG_EDGE else m.flags and MaterialData.FLAG_EDGE.inv()
                a.vec("edge_color", 3)?.let { for (k in 0 until 3) m.edgeColor[k] = it[k].coerceIn(0f, 1f) }
                if (a["double_sided"] is Boolean) m.flags = if (a.b("double_sided", false)) m.flags or MaterialData.FLAG_DOUBLE_SIDED else m.flags and MaterialData.FLAG_DOUBLE_SIDED.inv()
                if (a["specular"] is Number) m.specular.fill(a.f("specular", 0f).coerceIn(0f, 1f))
            }
            when (a.s("action")) {
                "list" -> {}
                "add" -> {
                    val c = a.vec("color", 3) ?: floatArrayOf(0.8f, 0.8f, 0.82f)
                    val i = s.addMaterial(a.s("name") ?: "", c[0], c[1], c[2], a.f("alpha", 1f))
                    s.editMaterial(i) { apply(it) }
                    a.s("texture")?.let { s.setMaterialTexture(i, it) }
                    host.changed()
                    return@render linkedMapOf("index" to i, "materials" to materialList(s))
                }
                "set" -> {
                    val i = materialIndex(s, a["material"] ?: fail("Missing material"))
                    s.editMaterial(i) { apply(it) }
                    a.s("texture")?.let { s.setMaterialTexture(i, it) }
                }
                "assign" -> if (s.assignMaterial(materialIndex(s, a["material"] ?: fail("Missing material"))) == 0) fail("No faces selected.")
                else -> fail("Unknown action.")
            }
            host.changed()
            linkedMapOf("materials" to materialList(s))
        })
    }

    tool("mesh_bind", "Bind the selected points fully to one bone so that this part of the mesh follows the bone (e.g. make a new hat follow 頭).", linkedMapOf("bone" to pRef("Bone index or name.")), listOf("bone")) { a ->
        ok(host.render {
            val s = session()
            val b = boneIndexIn(s, a["bone"] ?: fail("Missing bone"))
            val n = s.bindToBone(b)
            if (n == 0) fail("Nothing is selected.")
            host.changed()
            linkedMapOf("bound_points" to n, "bone" to s.mesh.bones[b].name)
        })
    }

    tool("mesh_history", "Undo or redo the last mesh edit.", linkedMapOf("action" to pEnum("undo or redo", "undo", "redo")), listOf("action")) { a ->
        ok(host.render {
            val s = session()
            val done = if (a.s("action") == "redo") s.redo() else s.undo()
            host.changed()
            linkedMapOf("done" to done) + meshSummary(s)
        })
    }

    tool("save_model", "Save a model as a .pmx file in the library (works for any model, also while editing). Returns the library path.",
        linkedMapOf("model" to pModel, "name" to pStr("File name without extension."))) { a ->
        ok(host.render { val m = model(a); linkedMapOf("path" to host.saveModel(m, a.s("name")), "vertices" to m.data.vertexCount) })
    }
}
