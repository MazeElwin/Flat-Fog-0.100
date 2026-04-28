#version 150

in vec2 vTexCoord;

uniform sampler2D DepthSampler;

uniform mat4 InvProjMat;     // set from Java: inverse projection (NDC → view space)
uniform vec3 CamForward;     // world-space look direction (from player yaw/pitch, no bobbing)
uniform vec3 CamRight;       // world-space camera-right   (from player yaw/pitch, no bobbing)
uniform vec3 CamUp;          // world-space camera-up      (from player yaw/pitch, no bobbing)

uniform vec3  CamWorldPos;
uniform float GameTime;
uniform float FogTopY;
uniform float FogBottomY;
uniform float FogDensity;
uniform float HeightVariation;
uniform float HeightScale;
uniform vec3  FogColorRGB;
uniform float FogAlpha;

out vec4 fragColor;

// ---------------------------------------------------------------------------
// FBM — smooth continuous fog top surface
// ---------------------------------------------------------------------------

float hash21(vec2 p) {
    p = fract(p * vec2(127.1, 311.7));
    p += dot(p, p.yx + 19.19);
    return fract((p.x + p.y) * p.x);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash21(i),                   hash21(i + vec2(1.0, 0.0)), u.x),
        mix(hash21(i + vec2(0.0, 1.0)),  hash21(i + vec2(1.0, 1.0)), u.x),
        u.y
    );
}

float fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += valueNoise(p) * a;
        p *= 2.1;
        a *= 0.5;
    }
    return v;
}

float fogTopAt(vec2 worldXZ) {
    vec2 coord = worldXZ * HeightScale + vec2(GameTime * 0.12, GameTime * 0.09);
    return FogTopY + (fbm(coord) - 0.5) * 2.0 * HeightVariation;
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

void main() {
    // Reconstruct world-space ray direction via camera basis vectors.
    // InvProjMat maps the NDC near-plane point to view space; the view-space
    // direction is then rotated to world space using pre-computed basis vectors
    // derived from player yaw/pitch (not from the Camera object, which bobs).
    vec4 viewNear = InvProjMat * vec4(vTexCoord * 2.0 - 1.0, -1.0, 1.0);
    viewNear /= viewNear.w;
    vec3 viewDir = normalize(viewNear.xyz);   // view-space ray direction
    // view +X = CamRight, view +Y = CamUp, view -Z = CamForward
    vec3 rayDir = normalize(viewDir.x * CamRight + viewDir.y * CamUp + (-viewDir.z) * CamForward);

    float camY      = CamWorldPos.y;
    float bandTop    = FogTopY + HeightVariation;
    float bandBottom = FogBottomY;

    // Conservative band intersection to find tEntry / tExit.
    float tEntry, tExit;
    if (abs(rayDir.y) < 0.0001) {
        if (camY > bandTop || camY < bandBottom) discard;
        tEntry = 0.0;
        tExit  = 1024.0;
    } else {
        float t1 = (bandBottom - camY) / rayDir.y;
        float t2 = (bandTop    - camY) / rayDir.y;
        tEntry = max(min(t1, t2), 0.0);
        tExit  = min(max(t1, t2), 1024.0);
    }
    if (tEntry >= tExit) discard;

    // Clamp tExit to scene geometry using Euclidean distance.
    // Dividing sceneViewZ by viewDir.z ties the clamp to the bobbed view space,
    // which becomes inconsistent with the non-bobbed world-space rayDir when
    // view bobbing is on. Euclidean distance (length of the view-space position
    // vector) is rotation-agnostic — the same whether the camera is bobbing or
    // not — so it correctly bounds the world-space ray at any camera tilt.
    {
        float rawDepth = texelFetch(DepthSampler, ivec2(gl_FragCoord.xy), 0).r;
        if (rawDepth < 0.9999) {
            vec4 ndcPos  = vec4(vTexCoord * 2.0 - 1.0, rawDepth * 2.0 - 1.0, 1.0);
            vec4 viewPos = InvProjMat * ndcPos;
            float sceneT = length(viewPos.xyz / viewPos.w);
            if (sceneT > 0.1) tExit = min(tExit, sceneT);
        }
    }
    if (tEntry >= tExit) discard;

    // Stepped integration with a fixed world-space step size.
    // Adaptive step counts (pathLen/N) shift all sample positions each frame
    // as pathLen changes, causing visible shimmer when moving.
    // A fixed 2-block step keeps each sample's world contribution stable.
    float pathLen  = tExit - tEntry;
    float stepSize = 2.0;
    int   STEPS    = min(int(pathLen / stepSize) + 1, 48);

    float transmittance = 1.0;
    for (int i = 0; i < STEPS; i++) {
        float t   = tEntry + (float(i) + 0.5) * stepSize;
        if (t >= tExit) break;
        vec3  pos = CamWorldPos + rayDir * t;

        if (pos.y < bandBottom) continue;

        float lt    = fogTopAt(pos.xz);
        float below = lt - pos.y;
        if (below <= 0.0) continue;

        // Density fades in over HeightVariation*0.75 blocks below the surface.
        float density = clamp(below / max(HeightVariation * 0.75, 0.5), 0.0, 1.0);
        density = density * density * FogDensity * 0.06;

        transmittance *= exp(-density * stepSize);
        if (transmittance < 0.01) break;
    }

    float alpha = (1.0 - transmittance) * FogAlpha;
    if (alpha < 0.004) discard;

    fragColor = vec4(FogColorRGB, alpha);
}
