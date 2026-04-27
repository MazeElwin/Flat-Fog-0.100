#version 150

in vec3 Position;

out vec2 vTexCoord;

void main() {
    // Position is already in NDC [-1,1]. Pass through and compute UV.
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    vTexCoord   = Position.xy * 0.5 + 0.5;
}
