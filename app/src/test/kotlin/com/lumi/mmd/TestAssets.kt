package com.lumi.mmd

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 测试用的小素材，全部由代码生成（仓库里不放任何第三方模型）：
 *  - 一个带 3 根骨骼、2 个材质、1 个表情、1 段自带动画的小人偶（glTF，缓冲区内嵌 base64）；
 *  - 一段让它弯腰 + 变大头的 VMD；一个 VPD 姿势；一个 OBJ + MTL 的小舞台；
 *  - 一个文件名用 Shift-JIS 编码的 zip（日本素材包的典型情况）。
 */
object TestAssets {
    const val BONE_SPINE = "spine"
    const val MORPH_BIG_HEAD = "bigHead"

    // ------------------------------------------------------------------ glTF 小人偶

    fun riggedGltf(): String {
        val bin = ByteArrayOutputStream()
        val views = ArrayList<String>()
        val accessors = ArrayList<String>()
        fun pad() { while (bin.size() % 4 != 0) bin.write(0) }

        fun floats(data: FloatArray, type: String, minMax: Boolean = false): Int {
            pad()
            val off = bin.size()
            val bb = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in data) bb.putFloat(v)
            bin.write(bb.array())
            views.add("""{"buffer":0,"byteOffset":$off,"byteLength":${data.size * 4}}""")
            val comps = when (type) { "SCALAR" -> 1; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> error(type) }
            var extra = ""
            if (minMax) {
                val mn = FloatArray(comps) { Float.MAX_VALUE }
                val mx = FloatArray(comps) { -Float.MAX_VALUE }
                for (i in data.indices) { val c = i % comps; mn[c] = minOf(mn[c], data[i]); mx[c] = maxOf(mx[c], data[i]) }
                extra = ""","min":[${mn.joinToString(",")}],"max":[${mx.joinToString(",")}]"""
            }
            accessors.add("""{"bufferView":${views.size - 1},"componentType":5126,"count":${data.size / comps},"type":"$type"$extra}""")
            return accessors.size - 1
        }

        fun shorts(data: IntArray, type: String): Int {
            pad()
            val off = bin.size()
            val bb = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (v in data) bb.putShort(v.toShort())
            bin.write(bb.array())
            views.add("""{"buffer":0,"byteOffset":$off,"byteLength":${data.size * 2}}""")
            val comps = if (type == "VEC4") 4 else 1
            accessors.add("""{"bufferView":${views.size - 1},"componentType":5123,"count":${data.size / comps},"type":"$type"}""")
            return accessors.size - 1
        }

        // ---- 身体：一根方柱，三圈顶点（y = 0 / 0.8 / 1.6 米）
        val r = 0.15f
        val corners = arrayOf(floatArrayOf(-r, -r), floatArrayOf(r, -r), floatArrayOf(r, r), floatArrayOf(-r, r))
        val bodyPos = FloatArray(12 * 3); val bodyNrm = FloatArray(12 * 3)
        val bodyJoints = IntArray(12 * 4); val bodyWeights = FloatArray(12 * 4)
        val inv = 1f / sqrt(2f)
        for (ring in 0 until 3) for (c in 0 until 4) {
            val v = ring * 4 + c
            bodyPos[v * 3] = corners[c][0]; bodyPos[v * 3 + 1] = ring * 0.8f; bodyPos[v * 3 + 2] = corners[c][1]
            bodyNrm[v * 3] = if (corners[c][0] > 0) inv else -inv; bodyNrm[v * 3 + 2] = if (corners[c][1] > 0) inv else -inv
            bodyJoints[v * 4] = if (ring == 0) 0 else 1
            bodyWeights[v * 4] = 1f
        }
        val bodyIdx = ArrayList<Int>()
        for (ring in 0 until 2) for (c in 0 until 4) {
            val c2 = (c + 1) % 4
            val a = ring * 4 + c2; val b = ring * 4 + c; val cc = (ring + 1) * 4 + c; val d = (ring + 1) * 4 + c2
            bodyIdx.addAll(listOf(a, b, cc, a, cc, d))
        }

        // ---- 头：一个立方体，中心在 1.85 米
        val hc = floatArrayOf(0f, 1.85f, 0f); val hh = 0.2f
        val headPos = FloatArray(8 * 3); val headNrm = FloatArray(8 * 3); val headDelta = FloatArray(8 * 3)
        val headJoints = IntArray(8 * 4); val headWeights = FloatArray(8 * 4)
        val inv3 = 1f / sqrt(3f)
        for (v in 0 until 8) {
            val sx = if (v and 1 != 0) 1f else -1f; val sy = if (v and 2 != 0) 1f else -1f; val sz = if (v and 4 != 0) 1f else -1f
            headPos[v * 3] = hc[0] + sx * hh; headPos[v * 3 + 1] = hc[1] + sy * hh; headPos[v * 3 + 2] = hc[2] + sz * hh
            headNrm[v * 3] = sx * inv3; headNrm[v * 3 + 1] = sy * inv3; headNrm[v * 3 + 2] = sz * inv3
            headDelta[v * 3] = sx * hh * 0.5f; headDelta[v * 3 + 1] = sy * hh * 0.5f; headDelta[v * 3 + 2] = sz * hh * 0.5f
            headJoints[v * 4] = 2
            headWeights[v * 4] = 1f
        }
        val headIdx = intArrayOf(
            0, 2, 1, 1, 2, 3,  4, 5, 6, 5, 7, 6,  0, 1, 4, 1, 5, 4,
            2, 6, 3, 3, 6, 7,  0, 4, 2, 2, 4, 6,  1, 3, 5, 3, 7, 5)

        val aBodyPos = floats(bodyPos, "VEC3", true); val aBodyNrm = floats(bodyNrm, "VEC3")
        val aBodyJ = shorts(bodyJoints, "VEC4"); val aBodyW = floats(bodyWeights, "VEC4")
        val aBodyI = shorts(bodyIdx.toIntArray(), "SCALAR"); val aBodyT = floats(FloatArray(12 * 3), "VEC3", true)
        val aHeadPos = floats(headPos, "VEC3", true); val aHeadNrm = floats(headNrm, "VEC3")
        val aHeadJ = shorts(headJoints, "VEC4"); val aHeadW = floats(headWeights, "VEC4")
        val aHeadI = shorts(headIdx, "SCALAR"); val aHeadT = floats(headDelta, "VEC3", true)

        // 逆绑定矩阵（列主序）：三根骨骼分别在 y = 0 / 0.8 / 1.6
        val ibm = FloatArray(48)
        for (j in 0 until 3) { val o = j * 16; ibm[o] = 1f; ibm[o + 5] = 1f; ibm[o + 10] = 1f; ibm[o + 15] = 1f; ibm[o + 13] = -0.8f * j }
        val aIbm = floats(ibm, "MAT4")

        // 自带动画：spine 绕 Z 轴摆动
        val aTime = floats(floatArrayOf(0f, 0.5f, 1f), "SCALAR", true)
        val h = Math.toRadians(15.0)
        val aRot = floats(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, sin(h).toFloat(), cos(h).toFloat(), 0f, 0f, 0f, 1f), "VEC4")

        pad()
        val b64 = Base64.getEncoder().encodeToString(bin.toByteArray())
        return """{
  "asset": {"version": "2.0", "generator": "LumiMMD tests"},
  "scene": 0,
  "scenes": [{"nodes": [0, 3]}],
  "nodes": [
    {"name": "root", "children": [1]},
    {"name": "$BONE_SPINE", "translation": [0, 0.8, 0], "children": [2]},
    {"name": "head", "translation": [0, 0.8, 0]},
    {"name": "Puppet", "mesh": 0, "skin": 0}
  ],
  "skins": [{"joints": [0, 1, 2], "inverseBindMatrices": $aIbm, "skeleton": 0}],
  "meshes": [{"name": "Puppet", "weights": [0], "extras": {"targetNames": ["$MORPH_BIG_HEAD"]}, "primitives": [
    {"attributes": {"POSITION": $aBodyPos, "NORMAL": $aBodyNrm, "JOINTS_0": $aBodyJ, "WEIGHTS_0": $aBodyW}, "indices": $aBodyI, "material": 0, "targets": [{"POSITION": $aBodyT}]},
    {"attributes": {"POSITION": $aHeadPos, "NORMAL": $aHeadNrm, "JOINTS_0": $aHeadJ, "WEIGHTS_0": $aHeadW}, "indices": $aHeadI, "material": 1, "targets": [{"POSITION": $aHeadT}]}
  ]}],
  "materials": [
    {"name": "body", "doubleSided": true, "pbrMetallicRoughness": {"baseColorFactor": [0.2, 0.5, 0.9, 1], "metallicFactor": 0, "roughnessFactor": 0.8}},
    {"name": "face", "doubleSided": true, "pbrMetallicRoughness": {"baseColorFactor": [1, 0.85, 0.75, 1], "metallicFactor": 0, "roughnessFactor": 0.8}}
  ],
  "animations": [{"name": "sway", "samplers": [{"input": $aTime, "output": $aRot, "interpolation": "LINEAR"}],
    "channels": [{"sampler": 0, "target": {"node": 1, "path": "rotation"}}]}],
  "buffers": [{"byteLength": ${bin.size()}, "uri": "data:application/octet-stream;base64,$b64"}],
  "bufferViews": [${views.joinToString(",")}],
  "accessors": [${accessors.joinToString(",")}]
}"""
    }

    // ------------------------------------------------------------------ VMD / VPD

    private val SJIS: Charset = Charset.forName("Shift_JIS")

    /** 最小的 VMD：[boneName] 在第 30 帧绕 Z 轴转 [degrees]°，[morphName] 从 0 渐变到 1。 */
    fun vmd(boneName: String = BONE_SPINE, morphName: String = MORPH_BIG_HEAD, degrees: Float = 40f): ByteArray {
        val out = ByteArrayOutputStream()
        fun fixed(s: String, n: Int) = out.write(s.toByteArray(SJIS).copyOf(n))
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun f32(v: Float) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array())
        fixed("Vocaloid Motion Data 0002", 30)
        fixed("puppet", 20)
        val keys = listOf(0 to 0f, 30 to degrees, 60 to 0f)
        u32(keys.size)
        val linear = ByteArray(64) { if (it % 16 < 8) 20 else 107 }
        for ((frame, deg) in keys) {
            fixed(boneName, 15); u32(frame)
            f32(0f); f32(0f); f32(0f)
            val half = Math.toRadians(deg / 2.0)
            f32(0f); f32(0f); f32(sin(half).toFloat()); f32(cos(half).toFloat())
            out.write(linear)
        }
        u32(2)
        fixed(morphName, 15); u32(0); f32(0f)
        fixed(morphName, 15); u32(60); f32(1f)
        u32(0)   // 镜头
        u32(0)   // 灯光
        u32(0)   // 自阴影
        u32(0)   // IK
        return out.toByteArray()
    }

    fun vpd(boneName: String = BONE_SPINE): ByteArray = ("Vocaloid Pose Data file\r\n\r\npuppet.osm;\t\t// 親ファイル名\r\n1;\t\t\t\t// 総ポーズボーン数\r\n\r\n" +
        "Bone0{$boneName\r\n  0.000000,0.000000,0.000000;\t\t\t\t// trans x,y,z\r\n  0.000000,0.000000,0.258819,0.965926;\t\t// Quaternion x,y,z,w\r\n}\r\n").toByteArray(SJIS)

    // ------------------------------------------------------------------ OBJ 小舞台

    const val STAGE_OBJ = """# LumiMMD test stage
mtllib stage.mtl
o Floor
v -20 0 -20
v 20 0 -20
v 20 0 20
v -20 0 20
vn 0 1 0
usemtl floor
f 4//1 3//1 2//1 1//1
o Pillar
v 6 0 6
v 9 0 6
v 9 0 9
v 6 0 9
v 6 8 6
v 9 8 6
v 9 8 9
v 6 8 9
usemtl pillar
f 5 6 7 8
f 5 8 12 9
f 6 5 9 10
f 7 6 10 11
f 8 7 11 12
f 9 12 11 10
"""

    const val STAGE_MTL = """newmtl floor
Kd 0.55 0.6 0.65
newmtl pillar
Kd 0.9 0.5 0.3
"""

    // ------------------------------------------------------------------ zip

    /** 文件名用 Shift-JIS 编码（没有 UTF-8 标志位）的素材包。 */
    fun writeSjisZip(zip: File) {
        ZipOutputStream(zip.outputStream(), SJIS).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("テスト素材/人形.gltf", riggedGltf().toByteArray())
            put("テスト素材/モーション/おじぎ.vmd", vmd())
            put("テスト素材/ポーズ.vpd", vpd())
            put("テスト素材/stage/stage.obj", STAGE_OBJ.toByteArray())
            put("テスト素材/stage/stage.mtl", STAGE_MTL.toByteArray())
            put("テスト素材/readme.txt", "テスト".toByteArray(SJIS))
        }
    }
}
