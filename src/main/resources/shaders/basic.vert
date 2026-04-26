#version 450

// Per-vertex
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec3 inColor;
layout(location = 3) in vec2 inUV;
layout(location = 4) in vec3 inTangent;

// Per-instance: mat4 model + vec4 tint
layout(location = 5) in vec4 inModel0;
layout(location = 6) in vec4 inModel1;
layout(location = 7) in vec4 inModel2;
layout(location = 8) in vec4 inModel3;
layout(location = 9) in vec4 inTint;

layout(push_constant) uniform Push {
    mat4 viewProj;
    vec4 lightDir;     // xyz = direction toward light, w unused
    vec4 camPosTime;   // xyz = camera world position, w = elapsed seconds (used by other pipelines)
} push;

layout(location = 0) out vec3 fragColor;
layout(location = 1) out vec2 fragUV;
layout(location = 2) out vec3 fragNormalWS;
layout(location = 3) out vec3 fragTangentWS;
layout(location = 4) out vec3 fragWorldPos;
layout(location = 5) out vec3 fragLightDirWS;

void main() {
    mat4 model = mat4(inModel0, inModel1, inModel2, inModel3);
    vec4 worldPos = model * vec4(inPos, 1.0);
    gl_Position = push.viewProj * worldPos;

    mat3 nmat = mat3(model);
    fragNormalWS = normalize(nmat * inNormal);
    fragTangentWS = normalize(nmat * inTangent);
    fragColor = inColor * inTint.rgb;
    fragUV = inUV;
    fragWorldPos = worldPos.xyz;
    fragLightDirWS = normalize(push.lightDir.xyz);
}
