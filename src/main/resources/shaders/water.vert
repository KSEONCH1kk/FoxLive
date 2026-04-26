#version 450

// Per-vertex only (water plane is a single mesh in world space — identity model)
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec3 inColor;
layout(location = 3) in vec2 inUV;
layout(location = 4) in vec3 inTangent;

layout(push_constant) uniform Push {
    mat4 viewProj;
    vec4 lightDir;
    vec4 camPosTime;
} push;

layout(location = 0) out vec3 fragWorldPos;
layout(location = 1) out vec3 fragNormalWS;
layout(location = 2) out vec2 fragUV;
layout(location = 3) out float fragHeight;

// One sinusoidal travelling wave with analytical height + gradient.
// Height contribution is added; horizontal Gerstner displacement makes crests sharper.
// Gradients (dHx, dHz) are accumulated for fragment-perfect normal reconstruction.
vec3 waveOffset(vec2 p, vec2 dir, float wavelength, float steepness, float time, float speed,
                inout float dHx, inout float dHz) {
    vec2 d = normalize(dir);
    float k = 6.2831853 / wavelength;            // angular wavenumber
    float phase = k * dot(d, p) - speed * time * k;
    float a = steepness / k;                     // amplitude
    float c = cos(phase); float s = sin(phase);
    dHx += d.x * a * k * c;
    dHz += d.y * a * k * c;
    return vec3(d.x * a * 0.55 * c, a * s, d.y * a * 0.55 * c);
}

void main() {
    vec4 worldPos = vec4(inPos, 1.0);

    float t = push.camPosTime.w;
    float dHx = 0.0;
    float dHz = 0.0;
    vec3 off = vec3(0.0);

    // 5 layered waves at different scales/directions/speeds — sum ≈ ocean swell
    off += waveOffset(worldPos.xz, vec2( 1.0,  0.5),  9.0, 0.40, t, 0.55, dHx, dHz);
    off += waveOffset(worldPos.xz, vec2(-0.6,  1.0),  6.5, 0.30, t, 0.75, dHx, dHz);
    off += waveOffset(worldPos.xz, vec2( 0.3, -1.0),  3.5, 0.20, t, 1.05, dHx, dHz);
    off += waveOffset(worldPos.xz, vec2(-1.0, -0.4),  2.0, 0.12, t, 1.45, dHx, dHz);
    off += waveOffset(worldPos.xz, vec2( 0.7,  0.7),  1.2, 0.06, t, 1.85, dHx, dHz);

    worldPos.xyz += off;

    fragNormalWS = normalize(vec3(-dHx, 1.0, -dHz));
    fragWorldPos = worldPos.xyz;
    fragUV       = inUV;
    fragHeight   = off.y;

    gl_Position = push.viewProj * worldPos;
}
