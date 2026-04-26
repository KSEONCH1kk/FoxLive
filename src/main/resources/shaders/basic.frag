#version 450

layout(location = 0) in vec3 fragColor;
layout(location = 1) in vec2 fragUV;
layout(location = 2) in vec3 fragNormalWS;
layout(location = 3) in vec3 fragTangentWS;
layout(location = 4) in vec3 fragWorldPos;
layout(location = 5) in vec3 fragLightDirWS;

layout(set = 0, binding = 0) uniform sampler2D diffuseTex;
layout(set = 0, binding = 1) uniform sampler2D normalTex;

layout(location = 0) out vec4 outColor;

void main() {
    vec3 N = normalize(fragNormalWS);
    vec3 T = normalize(fragTangentWS - dot(fragTangentWS, N) * N);
    vec3 B = cross(N, T);
    mat3 TBN = mat3(T, B, N);

    vec3 nSample = texture(normalTex, fragUV).rgb * 2.0 - 1.0;
    vec3 worldN = normalize(TBN * nSample);

    vec3 albedo = texture(diffuseTex, fragUV).rgb * fragColor;

    vec3 L = normalize(fragLightDirWS);
    float diff = max(dot(worldN, L), 0.0);
    vec3 ambient = 0.22 * albedo;
    vec3 lit = ambient + diff * albedo;
    outColor = vec4(lit, 1.0);
}
