#version 450

layout(location = 0) in vec3 fragWorldPos;
layout(location = 1) in vec3 fragNormalWS;
layout(location = 2) in vec2 fragUV;
layout(location = 3) in float fragHeight;

layout(push_constant) uniform Push {
    mat4 viewProj;
    vec4 lightDir;
    vec4 camPosTime;
} push;

layout(location = 0) out vec4 outColor;

// Procedural sky lookup — gradient + sun disk + glow.
vec3 skySample(vec3 dir, vec3 sunDir) {
    float t = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 zenith  = vec3(0.16, 0.40, 0.78);
    vec3 horizon = vec3(0.78, 0.86, 0.94);
    vec3 sky = mix(horizon, zenith, smoothstep(0.05, 0.60, t));
    vec3 sunCol = vec3(1.0, 0.93, 0.78);
    float sd = max(dot(dir, sunDir), 0.0);
    sky += sunCol * pow(sd, 512.0) * 8.0;
    sky += sunCol * pow(sd, 16.0)  * 0.35;
    return sky;
}

// Cheap 2D value-noise for high-frequency ripple normal perturbation.
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

void main() {
    float t = push.camPosTime.w;
    vec3 N = normalize(fragNormalWS);

    // High-frequency micro-ripples on top of the wave normal
    vec2 rp = fragWorldPos.xz * 0.8;
    float n1 = vnoise(rp + vec2(t * 0.30, t * 0.20));
    float n2 = vnoise(rp * 2.3 + vec2(-t * 0.15, t * 0.18));
    N = normalize(N + vec3((n1 - n2) * 0.22, 0.0, (n2 - n1) * 0.22));

    vec3 V = normalize(push.camPosTime.xyz - fragWorldPos);
    vec3 L = normalize(push.lightDir.xyz);

    // Schlick Fresnel — water reflects much more at grazing view
    float F0 = 0.02;
    float fresnel = F0 + (1.0 - F0) * pow(1.0 - max(dot(N, V), 0.0), 5.0);

    // Sky reflection
    vec3 R = reflect(-V, N);
    vec3 sky = skySample(R, L);

    // Body color — depth-gradient blend, height-tinted slightly cyan at crests
    vec3 deep    = vec3(0.02, 0.10, 0.16);
    vec3 shallow = vec3(0.10, 0.32, 0.42);
    float depthFactor = clamp(1.0 - V.y * 0.6, 0.0, 1.0);
    vec3 water = mix(shallow, deep, depthFactor);
    water += vec3(0.04, 0.06, 0.05) * fragHeight;

    // Subsurface-style tint at grazing angles
    float sss = pow(1.0 - max(dot(N, V), 0.0), 2.0);
    water += vec3(0.06, 0.10, 0.10) * sss;

    // Specular sun glint
    vec3 H = normalize(L + V);
    float spec = pow(max(dot(N, H), 0.0), 256.0);
    vec3 sunCol = vec3(1.0, 0.95, 0.82);

    vec3 lit = mix(water, sky, fresnel) + sunCol * spec * 3.0;

    // Soft Reinhard tonemap
    lit = lit / (1.0 + lit * 0.6);
    outColor = vec4(lit, 1.0);
}
