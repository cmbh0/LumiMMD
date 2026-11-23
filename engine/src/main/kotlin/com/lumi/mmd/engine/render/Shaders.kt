package com.lumi.mmd.engine.render

/**
 * 全部 GLSL ES 3.00 着色器源码。
 *
 * 顶点属性 location 约定（见 [ATTRIBS]）：
 *  0 a_pos  1 a_normal  2 a_uv  3 a_bones  4 a_weights  5 a_edge  6 a_sdefC  7 a_sdefR0  8 a_sdefR1  9 a_color
 *
 * 蒙皮在 GPU 上完成：每根骨骼在一张 RGBA32F 纹理里占 4 个 texel——
 * 前 3 个是 3x4 蒙皮矩阵的三行，第 4 个是该矩阵旋转部分的四元数（SDEF 用）。
 */
object Shaders {
    val ATTRIBS = arrayOf("a_pos", "a_normal", "a_uv", "a_bones", "a_weights", "a_edge", "a_sdefC", "a_sdefR0", "a_sdefR1", "a_color")

    const val FLAG_SKINNED = 1
    const val FLAG_SDEF = 2
    const val FLAG_VCOLOR = 4

    fun defines(flags: Int): String {
        val sb = StringBuilder("#version 300 es\n")
        if (flags and FLAG_SKINNED != 0) sb.append("#define SKINNED\n")
        if (flags and FLAG_SDEF != 0) sb.append("#define SDEF\n")
        if (flags and FLAG_VCOLOR != 0) sb.append("#define VCOLOR\n")
        return sb.toString()
    }

    /** 蒙皮函数（顶点着色器公用部分）。 */
    private const val SKIN = """
precision highp float;
precision highp int;
layout(location=0) in vec3 a_pos;
layout(location=1) in vec3 a_normal;
layout(location=2) in vec2 a_uv;
#ifdef SKINNED
layout(location=3) in vec4 a_bones;
layout(location=4) in vec4 a_weights;
uniform highp sampler2D u_boneTex;
#ifdef SDEF
layout(location=6) in vec4 a_sdefC;
layout(location=7) in vec3 a_sdefR0;
layout(location=8) in vec3 a_sdefR1;
#endif
vec4 boneRow(int bone, int row) {
    return texelFetch(u_boneTex, ivec2((bone & 255) * 4 + row, bone >> 8), 0);
}
vec3 qrot(vec4 q, vec3 v) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}
#endif
void skinVertex(out vec3 op, out vec3 on) {
#ifdef SKINNED
    ivec4 idx = ivec4(a_bones + 0.5);
    vec4 w = a_weights;
#ifdef SDEF
    if (a_sdefC.w > 0.5) {
        vec4 q0 = boneRow(idx.x, 3);
        vec4 q1 = boneRow(idx.y, 3);
        if (dot(q0, q1) < 0.0) q1 = -q1;
        vec4 q = normalize(q0 * w.x + q1 * w.y);
        vec4 r0 = vec4(a_sdefR0, 1.0);
        vec4 r1 = vec4(a_sdefR1, 1.0);
        vec3 p0 = vec3(dot(boneRow(idx.x, 0), r0), dot(boneRow(idx.x, 1), r0), dot(boneRow(idx.x, 2), r0));
        vec3 p1 = vec3(dot(boneRow(idx.y, 0), r1), dot(boneRow(idx.y, 1), r1), dot(boneRow(idx.y, 2), r1));
        op = qrot(q, a_pos - a_sdefC.xyz) + p0 * w.x + p1 * w.y;
        on = qrot(q, a_normal);
        return;
    }
#endif
    vec4 m0 = boneRow(idx.x, 0) * w.x;
    vec4 m1 = boneRow(idx.x, 1) * w.x;
    vec4 m2 = boneRow(idx.x, 2) * w.x;
    if (w.y != 0.0) { m0 += boneRow(idx.y, 0) * w.y; m1 += boneRow(idx.y, 1) * w.y; m2 += boneRow(idx.y, 2) * w.y; }
    if (w.z != 0.0) { m0 += boneRow(idx.z, 0) * w.z; m1 += boneRow(idx.z, 1) * w.z; m2 += boneRow(idx.z, 2) * w.z; }
    if (w.w != 0.0) { m0 += boneRow(idx.w, 0) * w.w; m1 += boneRow(idx.w, 1) * w.w; m2 += boneRow(idx.w, 2) * w.w; }
    vec4 p = vec4(a_pos, 1.0);
    op = vec3(dot(m0, p), dot(m1, p), dot(m2, p));
    on = vec3(dot(m0.xyz, a_normal), dot(m1.xyz, a_normal), dot(m2.xyz, a_normal));
#else
    op = a_pos;
    on = a_normal;
#endif
}
"""

    // ------------------------------------------------------------------ 模型主着色器

    const val MODEL_VS = SKIN + """
#ifdef VCOLOR
layout(location=9) in vec4 a_color;
#endif
uniform mat4 u_model;
uniform mat4 u_viewProj;
uniform mat4 u_view;
uniform mat4 u_shadowMat;
out vec3 v_normal;
out vec3 v_worldPos;
out vec2 v_uv;
out vec2 v_sphereUv;
out vec4 v_shadowCoord;
out vec4 v_color;
void main() {
    vec3 p; vec3 n;
    skinVertex(p, n);
    vec4 world = u_model * vec4(p, 1.0);
    vec3 wn = mat3(u_model) * n;
    v_worldPos = world.xyz;
    v_normal = wn;
    v_uv = a_uv;
    vec3 vn = normalize(mat3(u_view) * wn);
    v_sphereUv = vn.xy * vec2(0.5, -0.5) + 0.5;
    v_shadowCoord = u_shadowMat * world;
#ifdef VCOLOR
    v_color = a_color;
#else
    v_color = vec4(1.0);
#endif
    gl_Position = u_viewProj * world;
}
"""

    const val MODEL_FS = """
precision highp float;
precision highp int;
in vec3 v_normal;
in vec3 v_worldPos;
in vec2 v_uv;
in vec2 v_sphereUv;
in vec4 v_shadowCoord;
in vec4 v_color;
uniform sampler2D u_tex;
uniform sampler2D u_sphere;
uniform sampler2D u_toon;
uniform highp sampler2DShadow u_shadowMap;
uniform vec4 u_diffuse;
uniform vec3 u_ambient;
uniform vec3 u_specular;
uniform float u_shininess;
uniform vec3 u_emissive;
uniform vec3 u_lightDir;      // 指向光源
uniform vec3 u_lightColor;
uniform vec3 u_cameraPos;
uniform vec3 u_skyColor;      // 通用材质的半球环境光
uniform vec3 u_groundColor;
uniform int u_texMode;        // 0 无, 1 有
uniform int u_sphereMode;     // 0 无, 1 乘, 2 加, 4 自发光贴图
uniform int u_toonMode;       // 0 无, 1 有
uniform int u_shading;        // 0 MMD, 1 通用, 2 无光照
uniform vec4 u_texMul; uniform vec4 u_texAdd;
uniform vec4 u_sphereMul; uniform vec4 u_sphereAdd;
uniform vec4 u_toonMul; uniform vec4 u_toonAdd;
uniform float u_alphaCutoff;
uniform float u_shadowStrength;   // 0 = 不接收阴影
uniform vec2 u_shadowTexel;
uniform vec4 u_tint;              // 选中高亮 / 脚本染色
uniform vec3 u_fxMul;             // 脚本：颜色乘
uniform vec3 u_fxAdd;             // 脚本：颜色加（发光）
uniform float u_rim;              // 边缘光强度
uniform vec3 u_rimColor;
uniform float u_saturation;
uniform vec4 u_cel;               // 三渲二：x 光照分几级（0 = 关） y 交界线的柔和度 z 交界线偏移 w 硬边高光的强度
uniform vec3 u_shadeColor;        // 三渲二：暗部乘的颜色
uniform float u_specScale;        // 高光倍数
out vec4 fragColor;

// 把 0..1 的受光量切成 u_cel.x 级台阶；台阶的边至少有一个像素宽（不出锯齿）
float celStep(float x) {
    float t = clamp(x, 0.0, 1.0) * u_cel.x;
    float f = min(floor(t), u_cel.x - 1.0);
    float w = clamp(max(u_cel.y * 0.5, fwidth(t) * 0.75), 0.0, 0.5);
    return (f + smoothstep(0.5 - w, 0.5 + w, t - f)) / u_cel.x;
}

float shadowVisibility() {
    if (u_shadowStrength <= 0.0) return 1.0;
    vec3 c = v_shadowCoord.xyz / v_shadowCoord.w;
    if (c.x <= 0.0 || c.x >= 1.0 || c.y <= 0.0 || c.y >= 1.0 || c.z >= 1.0) return 1.0;
    float s = 0.0;
    s += texture(u_shadowMap, vec3(c.xy + vec2(-0.5, -0.5) * u_shadowTexel, c.z));
    s += texture(u_shadowMap, vec3(c.xy + vec2(0.5, -0.5) * u_shadowTexel, c.z));
    s += texture(u_shadowMap, vec3(c.xy + vec2(-0.5, 0.5) * u_shadowTexel, c.z));
    s += texture(u_shadowMap, vec3(c.xy + vec2(0.5, 0.5) * u_shadowTexel, c.z));
    s *= 0.25;
    // 阴影贴图边缘淡出
    vec2 e = min(c.xy, 1.0 - c.xy);
    float fade = clamp(min(e.x, e.y) * 20.0, 0.0, 1.0);
    return mix(1.0, s, u_shadowStrength * fade);
}

void main() {
    vec3 N = normalize(v_normal);
    if (!gl_FrontFacing) N = -N;
    vec3 L = u_lightDir;
    vec3 V = normalize(u_cameraPos - v_worldPos);
    float alpha = u_diffuse.a * v_color.a;
    vec4 tex = vec4(1.0);
    if (u_texMode != 0) {
        tex = texture(u_tex, v_uv);
        tex.rgb = mix(vec3(1.0), tex.rgb * u_texMul.rgb + u_texAdd.rgb, clamp(u_texMul.a + u_texAdd.a, 0.0, 1.0));
        alpha *= tex.a;
    }
    if (alpha <= u_alphaCutoff) discard;
    float vis = shadowVisibility();
    float ndl = dot(N, L);
    vec3 color;
    if (u_cel.x > 0.5 && u_shading != 2) {
        // ---- 三渲二：不管原来是什么材质，一律「固有色 × 亮 / 暗两三级」----
        vec3 base = tex.rgb * v_color.rgb;
        if (u_shading == 0) {
            base *= clamp(u_diffuse.rgb * u_lightColor + u_ambient, 0.0, 1.0);
            if (u_sphereMode == 1 || u_sphereMode == 2) {
                vec3 sp = texture(u_sphere, v_sphereUv).rgb;
                sp = mix(vec3(1.0), sp * u_sphereMul.rgb + u_sphereAdd.rgb, clamp(u_sphereMul.a + u_sphereAdd.a, 0.0, 1.0));
                if (u_sphereMode == 1) base *= sp; else base += sp;
            }
        } else {
            base *= u_diffuse.rgb * min(mix(u_groundColor, u_skyColor, 0.75) + u_lightColor, vec3(1.0)) + u_ambient;
        }
        float q = celStep(min(ndl * 0.5 + 0.5 + u_cel.z * 0.5, vis));
        color = base * mix(u_shadeColor, vec3(1.0), q);
        if (u_cel.w > 0.0 && u_shininess > 0.0) {
            vec3 H = normalize(L + V);
            float sp = pow(max(dot(N, H), 0.0), max(u_shininess, 8.0));
            float e = max(fwidth(sp), 0.02);
            color += smoothstep(0.5 - e, 0.5 + e, sp) * max(u_specular, vec3(0.25)) * u_cel.w * q;
        }
        if (u_shading == 1) {
            if (u_sphereMode == 4) color += texture(u_sphere, v_uv).rgb * u_emissive;
            else color += u_emissive;
        }
    } else if (u_shading == 0) {
        // ---- MMD 卡通着色 ----
        color = clamp(u_diffuse.rgb * u_lightColor + u_ambient, 0.0, 1.0) * tex.rgb * v_color.rgb;
        if (u_sphereMode == 1 || u_sphereMode == 2) {
            vec3 sp = texture(u_sphere, v_sphereUv).rgb;
            sp = mix(vec3(1.0), sp * u_sphereMul.rgb + u_sphereAdd.rgb, clamp(u_sphereMul.a + u_sphereAdd.a, 0.0, 1.0));
            if (u_sphereMode == 1) color *= sp; else color += sp;
        }
        float lit = clamp(ndl * 0.5 + 0.5, 0.0, 1.0);
        if (u_toonMode != 0) {
            // 被阴影挡住的地方直接落到 toon 色带的暗端
            float tv = 1.0 - min(lit, vis);
            vec3 toon = texture(u_toon, vec2(0.0, tv)).rgb;
            toon = mix(vec3(1.0), toon * u_toonMul.rgb + u_toonAdd.rgb, clamp(u_toonMul.a + u_toonAdd.a, 0.0, 1.0));
            color *= toon;
        } else {
            color *= mix(0.72, 1.0, vis);
        }
        if (u_shininess > 0.0) {
            vec3 H = normalize(L + V);
            float sp = pow(max(dot(N, H), 0.0), u_shininess);
            color += sp * u_specular * u_lightColor * vis * u_specScale;
        }
    } else if (u_shading == 1) {
        // ---- 通用材质：半球环境光 + Lambert + Blinn-Phong ----
        vec3 base = tex.rgb * v_color.rgb;
        vec3 hemi = mix(u_groundColor, u_skyColor, N.y * 0.5 + 0.5);
        float d = max(ndl, 0.0) * vis;
        // u_ambient：与贴图相乘、不受光照影响的环境项（.x 配件的"自发光"在 MMD 里就是这个语义）
        color = base * (u_diffuse.rgb * (hemi + u_lightColor * d) + u_ambient);
        if (u_shininess > 0.0) {
            vec3 H = normalize(L + V);
            color += pow(max(dot(N, H), 0.0), u_shininess) * u_specular * u_lightColor * d * u_specScale;
        }
        if (u_sphereMode == 4) color += texture(u_sphere, v_uv).rgb * u_emissive;
        else color += u_emissive;
    } else {
        color = u_diffuse.rgb * tex.rgb * v_color.rgb + u_emissive;
    }
    if (u_rim > 0.0 && u_shading != 2) {
        float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0) * clamp(ndl + 0.6, 0.0, 1.0);
        if (u_cel.x > 0.5) rim = smoothstep(0.26, 0.32, rim);
        color += rim * u_rim * u_rimColor;
    }
    if (u_saturation != 1.0) {
        float g = dot(color, vec3(0.299, 0.587, 0.114));
        color = mix(vec3(g), color, u_saturation);
    }
    color = color * u_fxMul + u_fxAdd;
    color = mix(color, u_tint.rgb, u_tint.a);
    fragColor = vec4(color, alpha);
}
"""

    // ------------------------------------------------------------------ 描边（背面外扩法）

    const val EDGE_VS = SKIN + """
layout(location=5) in float a_edge;
uniform mat4 u_model;
uniform mat4 u_viewProj;
uniform float u_edgePixels;    // 以 NDC 为单位的像素宽度（2 * 像素 / 视口高）
uniform float u_edgeWorld;     // 世界空间的宽度上限（已乘投影缩放）
uniform float u_aspect;
out vec2 v_uv;
void main() {
    vec3 p; vec3 n;
    skinVertex(p, n);
    vec4 world = u_model * vec4(p, 1.0);
    vec4 clip = u_viewProj * world;
    vec4 clipN = u_viewProj * vec4(mat3(u_model) * n, 0.0);
    vec2 dir = clipN.xy * vec2(u_aspect, 1.0);
    float len = length(dir);
    if (len > 1e-6) {
        dir /= len;
        float width = min(u_edgePixels * clip.w, u_edgeWorld) * a_edge;
        clip.xy += dir * vec2(1.0 / u_aspect, 1.0) * width;
    }
    v_uv = a_uv;
    gl_Position = clip;
}
"""

    const val EDGE_FS = """
precision highp float;
in vec2 v_uv;
uniform vec4 u_edgeColor;
uniform sampler2D u_tex;
uniform int u_texMode;
out vec4 fragColor;
void main() {
    float a = u_edgeColor.a;
    if (u_texMode != 0) a *= texture(u_tex, v_uv).a;
    if (a <= 0.01) discard;
    fragColor = vec4(u_edgeColor.rgb, a);
}
"""

    // ------------------------------------------------------------------ 阴影深度

    const val DEPTH_VS = SKIN + """
uniform mat4 u_model;
uniform mat4 u_viewProj;
out vec2 v_uv;
void main() {
    vec3 p; vec3 n;
    skinVertex(p, n);
    v_uv = a_uv;
    gl_Position = u_viewProj * (u_model * vec4(p, 1.0));
}
"""

    const val DEPTH_FS = """
precision highp float;
in vec2 v_uv;
uniform sampler2D u_tex;
uniform int u_texMode;
uniform float u_alpha;
void main() {
    float a = u_alpha;
    if (u_texMode != 0) a *= texture(u_tex, v_uv).a;
    if (a < 0.35) discard;
}
"""

    // ------------------------------------------------------------------ 地面网格（同时充当阴影接收面）

    const val GROUND_VS = """#version 300 es
precision highp float;
layout(location=0) in vec3 a_pos;
uniform mat4 u_viewProj;
uniform mat4 u_shadowMat;
uniform vec3 u_center;
uniform float u_size;
out vec3 v_world;
out vec4 v_shadowCoord;
void main() {
    vec3 w = vec3(a_pos.x * u_size + u_center.x, u_center.y, a_pos.z * u_size + u_center.z);
    v_world = w;
    v_shadowCoord = u_shadowMat * vec4(w, 1.0);
    gl_Position = u_viewProj * vec4(w, 1.0);
}
"""

    const val GROUND_FS = """#version 300 es
precision highp float;
in vec3 v_world;
in vec4 v_shadowCoord;
uniform highp sampler2DShadow u_shadowMap;
uniform float u_shadowStrength;
uniform vec2 u_shadowTexel;
uniform vec3 u_cameraPos;
uniform vec4 u_gridColor;
uniform float u_gridOn;
uniform float u_fadeDist;
uniform float u_cell;          // 小格的边长（每 5 小格一条粗线）
uniform vec4 u_fill;           // 地板的底色；a = 0 就是透明的地板
out vec4 fragColor;
float gridLine(vec2 p, float cell) {
    vec2 c = p / cell;
    vec2 g = abs(fract(c - 0.5) - 0.5) / max(fwidth(c), vec2(1e-5));
    return 1.0 - clamp(min(g.x, g.y), 0.0, 1.0);
}
void main() {
    float dist = length(v_world.xz - u_cameraPos.xz);
    float fade = 1.0 - smoothstep(u_fadeDist * 0.35, u_fadeDist, dist);
    float a = 0.0;
    vec3 col = u_gridColor.rgb;
    if (u_gridOn > 0.5) {
        float minor = gridLine(v_world.xz, u_cell) * 0.45;
        float major = gridLine(v_world.xz, u_cell * 5.0) * 0.9;
        a = max(minor, major) * u_gridColor.a;
        // 坐标轴：X 红、Z 蓝
        vec2 d = abs(v_world.xz) / max(fwidth(v_world.xz), vec2(1e-5));
        if (d.y < 1.5) { col = vec3(0.90, 0.30, 0.30); a = max(a, 0.9 * (1.0 - d.y / 1.5)); }
        if (d.x < 1.5) { col = vec3(0.30, 0.45, 0.95); a = max(a, 0.9 * (1.0 - d.x / 1.5)); }
    }
    float sh = 0.0;
    if (u_shadowStrength > 0.0) {
        vec3 c = v_shadowCoord.xyz / v_shadowCoord.w;
        if (c.x > 0.0 && c.x < 1.0 && c.y > 0.0 && c.y < 1.0 && c.z < 1.0) {
            float s = 0.0;
            s += texture(u_shadowMap, vec3(c.xy + vec2(-0.75, -0.75) * u_shadowTexel, c.z));
            s += texture(u_shadowMap, vec3(c.xy + vec2(0.75, -0.75) * u_shadowTexel, c.z));
            s += texture(u_shadowMap, vec3(c.xy + vec2(-0.75, 0.75) * u_shadowTexel, c.z));
            s += texture(u_shadowMap, vec3(c.xy + vec2(0.75, 0.75) * u_shadowTexel, c.z));
            vec2 e = min(c.xy, 1.0 - c.xy);
            sh = (1.0 - s * 0.25) * u_shadowStrength * clamp(min(e.x, e.y) * 20.0, 0.0, 1.0);
        }
    }
    // 从下往上：底色、网格线、影子（黑色半透明）
    a *= fade; sh *= fade;
    float fa = u_fill.a * (1.0 - smoothstep(u_fadeDist * 0.8, u_fadeDist, dist));
    float a0 = a + fa * (1.0 - a);
    vec3 c0 = a0 > 0.0 ? (col * a + u_fill.rgb * fa * (1.0 - a)) / a0 : vec3(0.0);
    float outA = sh + a0 * (1.0 - sh);
    vec3 outC = outA > 0.0 ? (c0 * a0 * (1.0 - sh)) / outA : vec3(0.0);
    if (outA < 0.004) discard;
    fragColor = vec4(outC, outA);
}
"""

    // ------------------------------------------------------------------ 线 / 纯色（骨骼、操纵轴、刚体线框）

    const val LINE_VS = """#version 300 es
precision highp float;
layout(location=0) in vec3 a_pos;
layout(location=9) in vec4 a_color;
uniform mat4 u_viewProj;
uniform float u_pointSize;
out vec4 v_color;
void main() {
    v_color = a_color;
    gl_Position = u_viewProj * vec4(a_pos, 1.0);
    gl_PointSize = u_pointSize;
}
"""

    const val LINE_FS = """#version 300 es
precision highp float;
in vec4 v_color;
out vec4 fragColor;
void main() { fragColor = v_color; }
"""

    // ------------------------------------------------------------------ 全屏后处理 / 输出

    const val POST_VS = """#version 300 es
precision highp float;
out vec2 v_uv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    v_uv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    const val POST_FS = """#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_scene;
uniform sampler2D u_bloom;
uniform float u_bloomStrength;
uniform float u_exposure;
uniform float u_vignette;
uniform float u_flipY;
uniform float u_contrast;
uniform float u_posterize;     // 每个通道留几级颜色；0 = 不处理
uniform vec3 u_grade;          // 整个画面乘的颜色
out vec4 fragColor;
void main() {
    vec2 uv = vec2(v_uv.x, mix(v_uv.y, 1.0 - v_uv.y, u_flipY));
    vec4 c = texture(u_scene, uv);
    if (u_bloomStrength > 0.0) c.rgb += texture(u_bloom, uv).rgb * u_bloomStrength;
    c.rgb *= u_exposure * u_grade;
    if (u_contrast != 1.0) c.rgb = max((c.rgb - 0.5) * u_contrast + 0.5, 0.0);
    if (u_posterize > 1.5) c.rgb = floor(clamp(c.rgb, 0.0, 1.0) * (u_posterize - 1.0) + 0.5) / (u_posterize - 1.0);
    if (u_vignette > 0.0) {
        vec2 d = uv - 0.5;
        c.rgb *= 1.0 - u_vignette * dot(d, d) * 1.6;
    }
    fragColor = c;
}
"""

    /** 泛光：提取高亮 / 降采样 / 高斯模糊 共用一个着色器，u_mode 区分。 */
    const val BLOOM_FS = """#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_scene;
uniform vec2 u_dir;        // 模糊方向 * texel
uniform int u_mode;        // 0 提取高亮, 1 模糊
uniform float u_threshold;
out vec4 fragColor;
void main() {
    if (u_mode == 0) {
        vec3 c = texture(u_scene, v_uv).rgb;
        float l = max(c.r, max(c.g, c.b));
        float k = clamp((l - u_threshold) / max(1.0 - u_threshold, 1e-3), 0.0, 1.0);
        fragColor = vec4(c * k * k, 1.0);
    } else {
        vec3 c = texture(u_scene, v_uv).rgb * 0.227027;
        c += (texture(u_scene, v_uv + u_dir * 1.384615).rgb + texture(u_scene, v_uv - u_dir * 1.384615).rgb) * 0.316216;
        c += (texture(u_scene, v_uv + u_dir * 3.230769).rgb + texture(u_scene, v_uv - u_dir * 3.230769).rgb) * 0.070270;
        fragColor = vec4(c, 1.0);
    }
}
"""

    /** 背景：竖直渐变。 */
    const val BACKGROUND_FS = """#version 300 es
precision highp float;
in vec2 v_uv;
uniform vec3 u_top;
uniform vec3 u_bottom;
out vec4 fragColor;
void main() {
    fragColor = vec4(mix(u_bottom, u_top, smoothstep(0.0, 1.0, v_uv.y)), 1.0);
}
"""
}
