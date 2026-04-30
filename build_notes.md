# Flat Fog Build Notes

## Builds 1–10 — Anti-bob investigation
Tested various approaches to stabilize the fog horizon against view bobbing.

- CamForward/CamRight/CamUp from player angles — bob still present
- camera.rotation() with scale(1,1,-1) — wrong, scale was incorrect
- Disabling depth clamping — no effect on bob

**Finding:** Bob is applied to the projection matrix in GameRenderer.renderLevel() (~line 1251),
not the model-view matrix. event.getModelViewMatrix() is pure camera rotation — no bob.

**Target fix identified:** Two projection matrices:
- InvProjMatStable — raw FOV, no bob — ray directions only
- InvProjMat — event.getProjectionMatrix() with bob — depth decode only

## Builds 11–49 — Unknown
No notes recorded.

## Build 50
- Removed manual counter-bob block (bobX/Y/RZ/RX, bobMat, camPos compensation)
- Added InvProjMatStable built from mc.options.fov().get()
- InvProjMat now comes from event.getProjectionMatrix() (with bob) for depth decode
- Removed unused Mth import
- Added InvProjMatStable uniform to flat_fog.json and flat_fog.fsh
- Shader ray direction now uses InvProjMatStable; depth decode still uses InvProjMat

**Status:** Bobbing still present in build 51.

## Build 52
- Removed InvProjMat (event projection with bob) entirely
- InvProjMatStable (raw FOV, no bob) now used for BOTH ray directions AND depth decode
- Reason: using the bobbed projection for depth decode caused sceneT to oscillate with the
  walk cycle — the fog clipped at a slightly wrong depth that moved with bob at geometry edges
- Removed InvProjMat from FlatFogRenderer.java, flat_fog.json, flat_fog.fsh

**Status:** Bob OFF = fog stable (matrices confirmed correct). Bob ON = world bobs but fog doesn't → apparent relative motion. This is correct 3D behavior, not a matrix bug.
NOTE: Default FogTopY = 100 puts fog top ABOVE typical survival Y (64-70). Player was inside fog band during testing, which is why fog appeared level with crosshair.

## Build 53
- Fixed FOV mismatch: InvProjMatStable now extracts actual FOV from event.getProjectionMatrix().m11()
  instead of using raw mc.options.fov().get(). Fixes fog rising when sprinting.
- bob.m11() ≈ 1/tan(fovy/2) even with small bob rotations applied, so this is stable.

**Status:** Pending test.

## Build 54
- Added camera Y cull — then removed it. Cull fired when player was just above fog top,
  which is the normal viewing position. Shader's tEntry >= tExit discard handles this already.

**Status:** Reverted to build 53 state.

## Build 55
- Use event.getProjectionMatrix() directly for InvProjMatStable (ray directions + depth decode).
  Fog rays now match the scene exactly — fog moves with world, no visual mismatch.
- Previous "breathing" concern only applies when camera is ABOVE fog. With FogTopY=100 and
  player inside the band, tEntry=0 so bob angle changes cause negligible density variation.

**Status:** BROKEN — bob translation in event projection pushed fog top off-screen, causing fog
horizon to blink on/off with each footstep.

## Build 56
- Reverted InvProjMatStable to m11-extraction approach (no bob translation, correct sprint FOV).
- Added rotation-only bob to InvViewMat: rotateZ(bobRZ) * rotateX(bobRX) — no translation.
  Fog rays now co-rotate with the world geometry without blinking.
- Bob translation (bobX, bobY) stays in the projection matrix and is NOT applied to fog rays.

**Status:** CONFIRMED WORKING — fog horizon stable with view bobbing on. No blinking.

## Build 57
- Replaced `DepthTexSize` uniform with `textureSize(DepthSampler, 0)` directly in the shader.
  Root cause: `DepthTexSize` defaulted to 1920×1080 in the JSON; if the uniform set silently
  failed or if the window is smaller than 1920×1080, vTexCoord * 1920 pushed texel coords out
  of bounds → undefined depth → fog rendered through solid blocks in top-right corner.
- Changed sky threshold from `rawDepth < 0.9999` to `rawDepth < 1.0`. The old threshold excluded
  real geometry at far render distances (depth > 0.9999 = geometry beyond ~500 blocks at far=1000),
  causing fog to punch through distant terrain when looking down.
- Removed `DepthTexSize` uniform from flat_fog.json and FlatFogRenderer.java.
- Removed now-unused `setUniform(String, float, float)` overload from FlatFogRenderer.java.

**Status:** Confirmed passing (same code as 57, built twice — no intentional changes).

## Build 58
- Added `clamp(vTexCoord, vec2(0.0), vec2(0.999999))` before multiplying by `textureSize(DepthSampler, 0)` for the texelFetch pixel coordinate. Prevents out-of-bounds texel read when vTexCoord reaches exactly 1.0 at screen edges, which would cause undefined depth and fog bleeding at the right/top screen edge.
- Changed step integration from fixed 2-block step / max 48 steps to adaptive:
  `stepSize = max(2.0, pathLen / 64.0)` with `STEPS = min(..., 64)`.
  Ensures the full fog band is always covered in ≤ 64 steps regardless of band height
  (e.g. a 164-block band now uses ~3-block steps rather than capping at 96-block coverage),
  while the `max(2.0, ...)` floor prevents over-sampling on short paths.

**Status:** Artifacts still present (top-right fog bleeding through terrain).

## Build 59
- Fixed far-plane mismatch in depth decode: replaced hardcoded `far=1024` in InvProjMatStable
  with values extracted from the actual projection matrix via `m22()` and `m32()`.
  Formula: `near = m32 / (m22 - 1)`, `far = m32 / (m22 + 1)`.
- Root cause: MC's far plane scales with render distance (~256–512 blocks typically). Decoding
  depth with far=1024 overestimates sceneT — at 100 blocks actual distance the shader computed
  ~119 blocks, letting fog render ~19 blocks through solid terrain. Near-horizontal rays
  (longest geometry distances) showed this most visibly as top-right fog-through-terrain patches.
- The bob translation in the projection matrix only affects the x/y terms (m03, m13), not the
  depth row (m22, m32), so extracting near/far from the bobbed projection is safe and stable.

**Status:** Pending test.
