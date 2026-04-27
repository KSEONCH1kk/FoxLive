#version 450

// Fullscreen triangle covering the screen — no vertex buffer needed.
//   gl_VertexIndex 0 → (-1, -1, 0)   uv (0, 0)
//   gl_VertexIndex 1 → ( 3, -1, 0)   uv (2, 0)
//   gl_VertexIndex 2 → (-1,  3, 0)   uv (0, 2)
// One triangle, one-pass, easy.

layout(location = 0) out vec2 fragUV;

void main() {
    fragUV = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(fragUV * 2.0 - 1.0, 0.0, 1.0);
}
