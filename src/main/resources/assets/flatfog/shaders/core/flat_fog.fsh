#version 150

in vec2 vTexCoord;

uniform sampler2D DepthSampler;
uniform sampler2D DhDepthSampler;

uniform mat4 InvProjMatStable;
uniform mat4 InvViewMat;

uniform vec3  CamWorldPos;
uniform float GameTime;
uniform float FogTopY;
uniform float FogBottomY;
uniform float FogDensity;
uniform float HeightVariation;
uniform float HeightScale;
uniform float MaxTraceDistance;
uniform float DepthFadeStart;
uniform float DepthFadeEnd;
uniform float HasDhDepth;
uniform vec3  FogColorRGB;
uniform float FogAlpha;

out vec4 fragColor;

const float TOP_SOFTNESS = 14.0;
const float BOTTOM_SOFTNESS = 6.0;
const float DETAIL_SCALE = 0.04;

// ---------------------------------------------------------------------------
// FBM — kept as dead code for future use
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
    vec2 drift = vec2(GameTime * 0.16, GameTime * 0.10);
    float broad = fbm(worldXZ * HeightScale + drift);
    return FogTopY + (broad - 0.5) * 2.0 * HeightVariation;
}

float fogDensityAt(vec3 worldPos) {
    float localTop = fogTopAt(worldPos.xz);
    float topFade = 1.0 - smoothstep(localTop - TOP_SOFTNESS, localTop, worldPos.y);
    float bottomFade = smoothstep(FogBottomY, FogBottomY + BOTTOM_SOFTNESS, worldPos.y);
    if (topFade <= 0.0 || bottomFade <= 0.0) return 0.0;

    vec2 drift = vec2(GameTime * 0.33, -GameTime * 0.19);
    float detail = fbm(worldPos.xz * DETAIL_SCALE + drift);
    float texture = mix(0.74, 1.08, detail);
    return topFade * topFade * bottomFade * texture * FogDensity * 0.045;
}

vec3 cloudColorAt(vec3 worldPos, float density) {
    float localTop = fogTopAt(worldPos.xz);
    float height01 = smoothstep(FogBottomY + BOTTOM_SOFTNESS, localTop, worldPos.y);

    vec2 drift = vec2(-GameTime * 0.21, GameTime * 0.27);
    float detail = fbm(worldPos.xz * DETAIL_SCALE * 0.72 + drift);

    vec3 lavenderShadow = vec3(0.58, 0.59, 0.72);
    vec3 coolBody = vec3(0.73, 0.77, 0.86);
    vec3 skyWash = vec3(0.64, 0.74, 0.88);
    vec3 warmWhite = vec3(0.98, 0.96, 0.98);

    vec3 color = mix(lavenderShadow, coolBody, height01);
    color = mix(color, skyWash, (1.0 - height01) * 0.28);

    float highlight = smoothstep(0.34, 1.0, height01) * smoothstep(0.28, 0.86, detail);
    color = mix(color, warmWhite, highlight * 0.78);

    float softShadow = (1.0 - height01) * smoothstep(0.02, 0.07, density);
    color = mix(color, vec3(0.52, 0.55, 0.70), softShadow * 0.18);
    return mix(color, FogColorRGB, 0.14);
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

void main() {
    vec4 viewNear = InvProjMatStable * vec4(vTexCoord * 2.0 - 1.0, -1.0, 1.0);
    viewNear /= viewNear.w;
    vec3 rayDir = normalize((InvViewMat * vec4(viewNear.xyz, 0.0)).xyz);

    vec3 camPos = CamWorldPos;
    float camY  = camPos.y;
    float bandTop    = FogTopY + HeightVariation + TOP_SOFTNESS;
    float bandBottom = FogBottomY;

    // Conservative band intersection to find tEntry / tExit.
    float tEntry, tExit;
    if (abs(rayDir.y) < 0.0001) {
        if (camY > bandTop || camY < bandBottom) discard;
        tEntry = 0.0;
        tExit  = MaxTraceDistance;
    } else {
        float t1 = (bandBottom - camY) / rayDir.y;
        float t2 = (bandTop    - camY) / rayDir.y;
        tEntry = max(min(t1, t2), 0.0);
        tExit  = min(max(t1, t2), MaxTraceDistance);
    }
    if (tEntry >= tExit) discard;

    // Depth clamp: stop fog at real scene geometry.
    // sceneT > 2.0  — skips near-plane prepass depths.
    // sceneWorldY < bandTop — skips cloud blocks above the fog band.
    float rawDepth = texture(DepthSampler, vTexCoord).r;
    if (rawDepth < 1.0) {
        vec4 ndcPos  = vec4(vTexCoord * 2.0 - 1.0, rawDepth * 2.0 - 1.0, 1.0);
        vec4 viewPos = InvProjMatStable * ndcPos;
        float sceneT = length(viewPos.xyz / viewPos.w);
        if (sceneT > 0.1) {
            float hardClamp = 1.0 - smoothstep(DepthFadeStart, DepthFadeEnd, sceneT);
            tExit = mix(tExit, min(tExit, sceneT), hardClamp);
        }
    }
    if (tEntry >= tExit) discard;

    bool dhHasDepth = HasDhDepth > 0.5 && texture(DhDepthSampler, vTexCoord).r < 1.0;

    // Stepped integration.
    float pathLen  = tExit - tEntry;
    float stepSize = max(2.0, pathLen / 64.0);
    int   STEPS    = min(int(pathLen / stepSize) + 1, 64);

    float transmittance = 1.0;
    vec3 fogColorSum = vec3(0.0);
    for (int i = 0; i < STEPS; i++) {
        float t   = tEntry + (float(i) + 0.5) * stepSize;
        if (t >= tExit) break;
        vec3  pos = camPos + rayDir * t;

        if (pos.y < bandBottom || pos.y >= bandTop) continue;

        float density = fogDensityAt(pos);
        if (density <= 0.0) continue;

        float sampleOpacity = 1.0 - exp(-density * stepSize);
        fogColorSum += cloudColorAt(pos, density) * sampleOpacity * transmittance;
        transmittance *= 1.0 - sampleOpacity;
        if (transmittance < 0.01) break;
    }

    float fogOpacity = 1.0 - transmittance;
    float alpha = fogOpacity * FogAlpha;
    if (alpha < 0.004) discard;

    if (dhHasDepth) {
        float topRayY = camPos.y + rayDir.y * max(tEntry, 0.0);
        float topWallMask = smoothstep(bandTop - TOP_SOFTNESS * 1.5, bandTop, topRayY);
        alpha *= 1.0 - topWallMask;
        if (alpha < 0.004) discard;
    }

    vec3 fogColor = fogOpacity > 0.0001 ? fogColorSum / fogOpacity : FogColorRGB;
    float atmosphericWash = smoothstep(0.08, 0.58, alpha) * 0.16;
    fogColor = mix(fogColor, vec3(0.66, 0.75, 0.89), atmosphericWash);
    fragColor = vec4(fogColor, alpha);
}
