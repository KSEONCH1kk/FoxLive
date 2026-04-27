#version 450

layout(location = 0) in vec2 fragUV;
layout(set = 0, binding = 0) uniform sampler2D sceneTex;

layout(push_constant) uniform PostFx {
    vec4 viewportTime;   // xy = pixel size (px), z = time, w = unused
    vec4 bloom;          // x = strength, y = threshold, z = radius, w = unused
    vec4 blur;           // x = strength (0..1), y = radius (px), z/w unused
    vec4 vhs;            // x = wobble strength, y = chroma, z = scanline, w = noise
    vec4 vignette;       // x = strength, y = unused, z = unused, w = unused
    vec4 colorGrade;     // xyz = RGB tint, w = exposure
} fx;

layout(location = 0) out vec4 outColor;

float hash12(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

vec3 sampleScene(vec2 uv) {
    return texture(sceneTex, clamp(uv, vec2(0.001), vec2(0.999))).rgb;
}

void main() {
    vec2 uv = fragUV;
    float t = fx.viewportTime.z;
    vec2 px = 1.0 / max(fx.viewportTime.xy, vec2(1.0));

    // 1) VHS sync wobble — horizontal jitter that ripples vertically
    if (fx.vhs.x > 0.001) {
        float band = sin(uv.y * 80.0 + t * 6.0) + sin(uv.y * 27.0 - t * 2.0) * 0.3;
        uv.x += band * 0.0015 * fx.vhs.x;
    }

    // 2) Chromatic aberration — separate R/B sample offsets
    vec3 col;
    if (fx.vhs.y > 0.001) {
        float a = fx.vhs.y * 0.0035;
        col.r = sampleScene(uv + vec2( a, 0.0)).r;
        col.g = sampleScene(uv).g;
        col.b = sampleScene(uv - vec2( a, 0.0)).b;
    } else {
        col = sampleScene(uv);
    }

    // 3) Box blur — variable radius, configurable strength
    if (fx.blur.x > 0.001 && fx.blur.y > 0.5) {
        vec3 acc = vec3(0.0);
        float w = 0.0;
        float r = fx.blur.y;
        for (int y = -2; y <= 2; ++y) {
            for (int x = -2; x <= 2; ++x) {
                vec2 off = vec2(float(x), float(y)) * px * r;
                acc += sampleScene(uv + off);
                w   += 1.0;
            }
        }
        col = mix(col, acc / w, clamp(fx.blur.x, 0.0, 1.0));
    }

    // 4) Bloom — single-pass approximation: blur of bright pixels, added back.
    if (fx.bloom.x > 0.001) {
        vec3 acc = vec3(0.0);
        float wsum = 0.0;
        float r = max(fx.bloom.z, 1.0);
        for (int y = -3; y <= 3; ++y) {
            for (int x = -3; x <= 3; ++x) {
                vec2 off = vec2(float(x), float(y)) * px * r * 1.2;
                vec3 s = sampleScene(uv + off);
                vec3 bright = max(s - vec3(fx.bloom.y), vec3(0.0));
                float wt = exp(-float(x*x + y*y) * 0.18);
                acc  += bright * wt;
                wsum += wt;
            }
        }
        col += (acc / max(wsum, 0.001)) * fx.bloom.x * 2.5;
    }

    // 5) Color grade + exposure
    col *= fx.colorGrade.rgb * max(fx.colorGrade.w, 0.01);

    // 6) Scanlines — modulate every other line
    if (fx.vhs.z > 0.001) {
        float lines = sin(fragUV.y * fx.viewportTime.y * 1.5) * 0.5 + 0.5;
        col *= mix(1.0, 0.65 + lines * 0.35, fx.vhs.z);
    }

    // 7) Per-pixel hash noise — film/VHS grain
    if (fx.vhs.w > 0.001) {
        float n = hash12(fragUV * fx.viewportTime.xy + t * 113.1);
        col += (n - 0.5) * fx.vhs.w * 0.5;
    }

    // 8) Vignette — soft radial darkening
    if (fx.vignette.x > 0.001) {
        vec2 vc = fragUV - 0.5;
        float vd = dot(vc, vc);
        float vig = 1.0 - vd * fx.vignette.x * 4.0;
        col *= clamp(vig, 0.0, 1.0);
    }

    outColor = vec4(col, 1.0);
}
