package com.lumi.mmd.engine.model

import java.io.File

/**
 * 引擎内部统一的模型数据。PMX/PMD 以及 X / OBJ / glTF / FBX / STL / PLY 等格式
 * 全部被导入器转换成这一种结构，后面的动画、物理、渲染、拾取、部件编辑都只认它。
 *
 * 坐标系：MMD 原生左手系（X 右、Y 上、Z 前，模型面朝 -Z），三角形顺时针为正面，
 * UV 原点在左上角。右手系格式（glTF/OBJ/FBX…）在导入时翻转 Z 并交换绕序。
 *
 * 顶点采用 SoA（每种属性一个 FloatArray）而不是每顶点一个对象：
 * 几十万顶点的模型也只有十来个数组，对移动端 GC 友好，并且可以直接整块上传到 GPU。
 */
class ModelData {
    var format: ModelFormat = ModelFormat.PMX
    var name: String = ""
    var nameEn: String = ""
    var comment: String = ""
    var commentEn: String = ""

    /** 源文件及其所在目录（解析贴图相对路径用）。 */
    var sourceFile: File? = null
    var baseDir: File? = null

    // ---------------------------------------------------------------- 顶点
    var vertexCount: Int = 0
    var positions: FloatArray = FloatArray(0)      // 3 * n
    var normals: FloatArray = FloatArray(0)        // 3 * n
    var uvs: FloatArray = FloatArray(0)            // 2 * n
    /** 顶点色（RGBA，4*n）；只有 glTF/PLY/X 等带顶点色的模型才有。 */
    var colors: FloatArray? = null
    /** 每顶点 4 个骨骼索引 / 权重。静态模型为 null（不走蒙皮着色器）。 */
    var boneIndices: ShortArray? = null            // 4 * n（按无符号解释）
    var boneWeights: FloatArray? = null            // 4 * n
    /** 描边倍率（PMX 顶点的 edge scale）。 */
    var edgeScales: FloatArray? = null             // n
    /** SDEF 顶点的 C / R0 / R1（3*n，各顶点已预处理成 saba / mmd_tools 的 cr0/cr1 形式）；无 SDEF 顶点时为 null。 */
    var sdefC: FloatArray? = null
    var sdefR0: FloatArray? = null
    var sdefR1: FloatArray? = null
    /** 每顶点的变形方式：0 BDEF1, 1 BDEF2, 2 BDEF4, 3 SDEF, 4 QDEF。 */
    var skinTypes: ByteArray? = null

    // ---------------------------------------------------------------- 面
    var indices: IntArray = IntArray(0)

    // ---------------------------------------------------------------- 贴图 / 材质 / 骨骼 / 表情 / 物理
    val textures = ArrayList<TextureRef>()
    val materials = ArrayList<MaterialData>()
    val bones = ArrayList<BoneData>()
    val morphs = ArrayList<MorphData>()
    val displayFrames = ArrayList<DisplayFrame>()
    val rigidBodies = ArrayList<RigidBodyData>()
    val joints = ArrayList<JointData>()

    /** 模型文件自带的动画片段（glTF 的 animations），已换算成 MMD 式的动作数据。 */
    val animations = ArrayList<com.lumi.mmd.engine.anim.Motion>()

    /** 模型自带的灯光（glTF 的 KHR_lights_punctual 等），导入舞台时可一并应用。 */
    val lights = ArrayList<LightData>()

    /** 导入时建议的整体缩放（例如以"米"为单位的 glTF → MMD 单位约为 12.5）。 */
    var suggestedScale: Float = 1f

    /** 导入过程中的非致命问题，UI 会提示给用户。 */
    val warnings = ArrayList<String>()

    // 包围盒（绑定姿势）
    val boundsMin = floatArrayOf(0f, 0f, 0f)
    val boundsMax = floatArrayOf(0f, 0f, 0f)

    val isSkinned: Boolean get() = boneIndices != null && boneWeights != null && bones.isNotEmpty()
    val hasSdef: Boolean get() = sdefC != null
    val hasPhysics: Boolean get() = rigidBodies.isNotEmpty()
    val triangleCount: Int get() = indices.size / 3

    fun computeBounds() {
        if (vertexCount == 0) {
            boundsMin.fill(0f); boundsMax.fill(0f)
            return
        }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        val p = positions
        var i = 0
        val n = vertexCount * 3
        while (i < n) {
            val x = p[i]; val y = p[i + 1]; val z = p[i + 2]
            if (x == x && y == y && z == z) { // 跳过 NaN
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (z < minZ) minZ = z
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                if (z > maxZ) maxZ = z
            }
            i += 3
        }
        if (minX > maxX) { minX = 0f; minY = 0f; minZ = 0f; maxX = 0f; maxY = 0f; maxZ = 0f }
        boundsMin[0] = minX; boundsMin[1] = minY; boundsMin[2] = minZ
        boundsMax[0] = maxX; boundsMax[1] = maxY; boundsMax[2] = maxZ
    }

    /** 做一遍防御性修正：越界索引、NaN、零法线、材质区间等，保证后续流程不用再判空判界。 */
    fun sanitize() {
        val n = vertexCount
        // 面索引越界 → 指到 0 号顶点（退化三角形，不会画出来）
        val idx = indices
        var bad = 0
        for (i in idx.indices) {
            val v = idx[i]
            if (v < 0 || v >= n) { idx[i] = 0; bad++ }
        }
        if (bad > 0) warnings.add("有 $bad 个面索引越界，已忽略")

        for (i in positions.indices) if (positions[i] != positions[i]) positions[i] = 0f
        for (i in normals.indices) if (normals[i] != normals[i]) normals[i] = 0f
        for (i in uvs.indices) if (uvs[i] != uvs[i]) uvs[i] = 0f

        val bi = boneIndices
        val bw = boneWeights
        if (bi != null && bw != null) {
            val boneCount = bones.size
            for (v in 0 until n) {
                val o = v * 4
                var sum = 0f
                for (k in 0 until 4) {
                    val b = bi[o + k].toInt() and 0xFFFF
                    if (b >= boneCount) { bi[o + k] = 0; bw[o + k] = 0f }
                    val w = bw[o + k]
                    if (w != w) bw[o + k] = 0f
                    sum += bw[o + k]
                }
                if (sum <= 1e-6f) { bw[o] = 1f; bw[o + 1] = 0f; bw[o + 2] = 0f; bw[o + 3] = 0f }
            }
        }

        // 材质面区间
        var offset = 0
        for (m in materials) {
            if (m.indexCount < 0) m.indexCount = 0
            m.indexOffset = offset
            if (offset + m.indexCount > idx.size) m.indexCount = maxOf(0, idx.size - offset)
            m.indexCount -= m.indexCount % 3
            offset += m.indexCount
            if (m.texture >= textures.size) m.texture = -1
            if (m.sphereTexture >= textures.size) m.sphereTexture = -1
            if (!m.sharedToon && m.toonIndex >= textures.size) m.toonIndex = -1
        }

        for ((i, b) in bones.withIndex()) {
            if (b.parent >= bones.size || b.parent == i) b.parent = -1
            if (b.tailBone >= bones.size) b.tailBone = -1
            if (b.appendParent >= bones.size || b.appendParent == i) b.appendParent = -1
            if (b.appendParent < 0) b.flags = b.flags and (BoneData.FLAG_APPEND_ROTATE or BoneData.FLAG_APPEND_TRANSLATE).inv()
            val ik = b.ik
            if (ik != null) {
                if (ik.target < 0 || ik.target >= bones.size) {
                    b.ik = null
                } else {
                    ik.links.removeAll { it.bone < 0 || it.bone >= bones.size }
                    if (ik.links.isEmpty()) b.ik = null
                }
            }
        }
        // 父子关系成环的保护：沿父链走超过骨骼总数就切断
        for (b in bones) {
            var p = b.parent
            var steps = 0
            while (p >= 0) {
                p = bones[p].parent
                if (++steps > bones.size) { b.parent = -1; break }
            }
        }
        for (rb in rigidBodies) if (rb.bone >= bones.size) rb.bone = -1
        joints.removeAll { it.bodyA < 0 || it.bodyB < 0 || it.bodyA >= rigidBodies.size || it.bodyB >= rigidBodies.size || it.bodyA == it.bodyB }
        computeBounds()
    }
}

enum class ModelFormat(val label: String, val isMmd: Boolean) {
    PMX("PMX", true),
    PMD("PMD", true),
    X("DirectX .x", false),
    OBJ("Wavefront OBJ", false),
    GLTF("glTF 2.0", false),
    FBX("FBX", false),
    STL("STL", false),
    PLY("PLY", false),
    BLEND("Blender", false),
    PRIMITIVE("内置", false),
}

/** 一张贴图的来源：相对路径，或直接内嵌的已编码图片数据（GLB / FBX 内嵌）。 */
class TextureRef(
    /** 模型里记录的原始路径（已统一成 '/' 分隔）。 */
    val path: String,
    /** 内嵌图片的编码字节（png/jpg/...），与 path 二选一。 */
    val embedded: ByteArray? = null,
) {
    override fun toString(): String = if (embedded != null) "<内嵌:$path>" else path
}

class MaterialData {
    var name: String = ""
    var nameEn: String = ""
    /** 漫反射 RGBA（A 即材质不透明度）。 */
    val diffuse = floatArrayOf(1f, 1f, 1f, 1f)
    val specular = floatArrayOf(0f, 0f, 0f)
    var shininess: Float = 5f
    /** MMD 的"环境色"，实际作用更接近自发光基底。 */
    val ambient = floatArrayOf(0.5f, 0.5f, 0.5f)
    var flags: Int = FLAG_GROUND_SHADOW or FLAG_CAST_SHADOW or FLAG_RECEIVE_SHADOW
    val edgeColor = floatArrayOf(0f, 0f, 0f, 1f)
    var edgeSize: Float = 1f
    var texture: Int = -1
    var sphereTexture: Int = -1
    var sphereMode: Int = SPHERE_NONE
    /** true 时 [toonIndex] 是共享 toon 序号 0..9（toon01..toon10），否则是贴图表索引。 */
    var sharedToon: Boolean = false
    var toonIndex: Int = -1
    var memo: String = ""
    var indexOffset: Int = 0
    var indexCount: Int = 0

    /** 着色模型：MMD 卡通 / 通用（Lambert + Blinn-Phong）/ 无光照。 */
    var shading: Int = SHADING_MMD
    /** 通用材质的自发光。 */
    val emissive = floatArrayOf(0f, 0f, 0f)
    /** glTF 的 alphaMode：0 OPAQUE, 1 MASK, 2 BLEND。MMD 材质恒为 BLEND。 */
    var alphaMode: Int = ALPHA_BLEND
    var alphaCutoff: Float = 0.5f

    val doubleSided: Boolean get() = (flags and FLAG_DOUBLE_SIDED) != 0
    val drawEdge: Boolean get() = (flags and FLAG_EDGE) != 0
    val castShadow: Boolean get() = (flags and FLAG_CAST_SHADOW) != 0
    val receiveShadow: Boolean get() = (flags and FLAG_RECEIVE_SHADOW) != 0

    companion object {
        const val FLAG_DOUBLE_SIDED = 0x01
        const val FLAG_GROUND_SHADOW = 0x02
        const val FLAG_CAST_SHADOW = 0x04
        const val FLAG_RECEIVE_SHADOW = 0x08
        const val FLAG_EDGE = 0x10
        const val FLAG_VERTEX_COLOR = 0x20
        const val FLAG_POINT = 0x40
        const val FLAG_LINE = 0x80

        const val SPHERE_NONE = 0
        const val SPHERE_MUL = 1
        const val SPHERE_ADD = 2
        const val SPHERE_SUBTEX = 3

        const val SHADING_MMD = 0
        const val SHADING_STANDARD = 1
        const val SHADING_UNLIT = 2

        const val ALPHA_OPAQUE = 0
        const val ALPHA_MASK = 1
        const val ALPHA_BLEND = 2
    }
}

class BoneData {
    var name: String = ""
    var nameEn: String = ""
    val position = FloatArray(3)
    var parent: Int = -1
    /** 变形阶层。 */
    var level: Int = 0
    var flags: Int = FLAG_ROTATABLE or FLAG_VISIBLE or FLAG_OPERABLE
    var tailBone: Int = -1
    val tailOffset = FloatArray(3)
    var appendParent: Int = -1
    var appendWeight: Float = 0f
    val fixedAxis = FloatArray(3)
    val localX = floatArrayOf(1f, 0f, 0f)
    val localZ = floatArrayOf(0f, 0f, 1f)
    var externalKey: Int = 0
    var ik: IkData? = null

    val isRotatable: Boolean get() = (flags and FLAG_ROTATABLE) != 0
    val isTranslatable: Boolean get() = (flags and FLAG_TRANSLATABLE) != 0
    val isVisible: Boolean get() = (flags and FLAG_VISIBLE) != 0
    val isOperable: Boolean get() = (flags and FLAG_OPERABLE) != 0
    val isIk: Boolean get() = ik != null
    val appendRotate: Boolean get() = (flags and FLAG_APPEND_ROTATE) != 0
    val appendTranslate: Boolean get() = (flags and FLAG_APPEND_TRANSLATE) != 0
    val appendLocal: Boolean get() = (flags and FLAG_APPEND_LOCAL) != 0
    val hasFixedAxis: Boolean get() = (flags and FLAG_FIXED_AXIS) != 0
    val afterPhysics: Boolean get() = (flags and FLAG_AFTER_PHYSICS) != 0

    companion object {
        const val FLAG_TAIL_IS_BONE = 0x0001
        const val FLAG_ROTATABLE = 0x0002
        const val FLAG_TRANSLATABLE = 0x0004
        const val FLAG_VISIBLE = 0x0008
        const val FLAG_OPERABLE = 0x0010
        const val FLAG_IK = 0x0020
        const val FLAG_APPEND_LOCAL = 0x0080
        const val FLAG_APPEND_ROTATE = 0x0100
        const val FLAG_APPEND_TRANSLATE = 0x0200
        const val FLAG_FIXED_AXIS = 0x0400
        const val FLAG_LOCAL_AXIS = 0x0800
        const val FLAG_AFTER_PHYSICS = 0x1000
        const val FLAG_EXTERNAL_PARENT = 0x2000
    }
}

class IkData {
    /** 末端执行器：要去够 IK 骨位置的那根骨（PMX 里叫"目标骨"）。 */
    var target: Int = -1
    var loopCount: Int = 0
    /** 每次迭代允许的最大转角（弧度）。 */
    var limitAngle: Float = 0f
    val links = ArrayList<IkLink>()
}

class IkLink {
    var bone: Int = -1
    var hasLimit: Boolean = false
    val lower = FloatArray(3)
    val upper = FloatArray(3)
}

class MorphData {
    var name: String = ""
    var nameEn: String = ""
    /** 面板：1 眉, 2 目, 3 口, 4 其他, 0 系统。 */
    var panel: Int = 4
    var type: Int = TYPE_VERTEX
    /** 偏移量个数。 */
    var count: Int = 0
    /** 每个偏移量的目标索引（顶点/骨骼/材质/表情/刚体，视类型而定）。 */
    var indices: IntArray = IntArray(0)
    /**
     * 每个偏移量的浮点数据，步长见 [stride]：
     *  - VERTEX: dx,dy,dz (3)
     *  - UV / 追加UV: x,y,z,w (4)
     *  - BONE: tx,ty,tz, qx,qy,qz,qw (7)
     *  - GROUP / FLIP: weight (1)
     *  - MATERIAL: op, diffuse(4), specular(3), shininess, ambient(3), edgeColor(4), edgeSize, tex(4), sphere(4), toon(4) (29)
     *  - IMPULSE: local, vx,vy,vz, tx,ty,tz (7)
     */
    var data: FloatArray = FloatArray(0)

    val stride: Int
        get() = when (type) {
            TYPE_VERTEX -> 3
            TYPE_UV, TYPE_UV1, TYPE_UV2, TYPE_UV3, TYPE_UV4 -> 4
            TYPE_BONE -> 7
            TYPE_GROUP, TYPE_FLIP -> 1
            TYPE_MATERIAL -> MATERIAL_STRIDE
            TYPE_IMPULSE -> 7
            else -> 0
        }

    companion object {
        const val TYPE_GROUP = 0
        const val TYPE_VERTEX = 1
        const val TYPE_BONE = 2
        const val TYPE_UV = 3
        const val TYPE_UV1 = 4
        const val TYPE_UV2 = 5
        const val TYPE_UV3 = 6
        const val TYPE_UV4 = 7
        const val TYPE_MATERIAL = 8
        const val TYPE_FLIP = 9
        const val TYPE_IMPULSE = 10

        const val MATERIAL_STRIDE = 29
    }
}

class DisplayFrame {
    var name: String = ""
    var nameEn: String = ""
    var special: Boolean = false
    /** 元素：type(0 骨骼 / 1 表情) 与索引成对存放。 */
    var elementTypes: ByteArray = ByteArray(0)
    var elementIndices: IntArray = IntArray(0)
}

class RigidBodyData {
    var name: String = ""
    var nameEn: String = ""
    var bone: Int = -1
    var group: Int = 0
    /** 会与之碰撞的分组位掩码（位为 1 表示碰撞）。 */
    var collisionMask: Int = 0xFFFF
    var shape: Int = SHAPE_SPHERE
    val size = FloatArray(3)
    val position = FloatArray(3)
    /** 欧拉角（弧度），R = Ry·Rx·Rz。 */
    val rotation = FloatArray(3)
    var mass: Float = 1f
    var linearDamping: Float = 0f
    var angularDamping: Float = 0f
    var restitution: Float = 0f
    var friction: Float = 0.5f
    var mode: Int = MODE_STATIC

    companion object {
        const val SHAPE_SPHERE = 0
        const val SHAPE_BOX = 1
        const val SHAPE_CAPSULE = 2

        /** 跟随骨骼（运动学）。 */
        const val MODE_STATIC = 0
        /** 物理演算。 */
        const val MODE_DYNAMIC = 1
        /** 物理演算 + 位置对齐骨骼。 */
        const val MODE_DYNAMIC_BONE = 2
    }
}

class JointData {
    var name: String = ""
    var nameEn: String = ""
    var type: Int = 0
    var bodyA: Int = -1
    var bodyB: Int = -1
    val position = FloatArray(3)
    val rotation = FloatArray(3)
    val linearLower = FloatArray(3)
    val linearUpper = FloatArray(3)
    val angularLower = FloatArray(3)
    val angularUpper = FloatArray(3)
    val linearSpring = FloatArray(3)
    val angularSpring = FloatArray(3)
}

class LightData {
    var name: String = ""
    /** 0 平行光, 1 点光, 2 聚光。 */
    var type: Int = 0
    val color = floatArrayOf(1f, 1f, 1f)
    var intensity: Float = 1f
    /** 光线传播方向（世界空间，左手系）。 */
    val direction = floatArrayOf(-0.5f, -1f, 0.5f)
    val position = FloatArray(3)
}
