#version 150

in vec2 vTexCoord;

uniform sampler2D DepthSampler;

uniform mat4 InvProjMat;
uniform mat4 InvViewMat;
uniform vec2 DepthTexSize;      // depth texture dimensions, set per-frame by the renderer

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
    vec4 viewNear = InvProjMat * vec4(vTexCoord * 2.0 - 1.0, -1.0, 1.0);
    viewNear /= viewNear.w;
    vec3 rayDir = normalize((InvViewMat * vec4(viewNear.xyz, 0.0)).xyz);

    vec3 camPos = CamWorldPos;
    float camY  = camPos.y;
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

    // Clamp tExit to scene geometry using Euclidean view-space distance.
    // Rotation preserves vector length, so length(viewPos) = world-space distance
    // from camera to geometry regardless of camera orientation or view bobbing.
    {
        ivec2 depthPixel = ivec2(clamp(vTexCoord, vec2(0.0), vec2(0.999999)) * DepthTexSize);
        float rawDepth = texelFetch(DepthSampler, depthPixel, 0).r;
        if (rawDepth < 0.9999) {
            vec4 ndcPos  = vec4(vTexCoord * 2.0 - 1.0, rawDepth * 2.0 - 1.0, 1.0);
            vec4 viewPos = InvProjMat * ndcPos;
            float sceneT = length(viewPos.xyz / viewPos.w);
            if (sceneT > 0.1) tExit = min(tExit, sceneT);
        }
    }
    if (tEntry >= tExit) discard;

    // Stepped integration. Step size grows with path length so all 64 steps
    // always cover the full marched range; minimum 2 blocks for short paths.
    float pathLen  = tExit - tEntry;
    float stepSize = max(2.0, pathLen / 64.0);
    int   STEPS    = min(int(pathLen / stepSize) + 1, 64);

    float transmittance = 1.0;
    for (int i = 0; i < STEPS; i++) {
        float t   = tEntry + (float(i) + 0.5) * stepSize;
        if (t >= tExit) break;
        vec3  pos = camPos + rayDir * t;

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
