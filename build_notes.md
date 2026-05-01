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

**Status:** Artifacts still present (top-right fog bleeding through terrain).

## Build 58
- Added `clamp(vTexCoord, vec2(0.0), vec2(0.999999))` before multiplying by `textureSize(DepthSampler, 0)` for the texelFetch pixel coordinate. Prevents out-of-bounds texel read when vTexCoord reaches exactly 1.0 at screen edges, which would cause undefined depth and fog bleeding at the right/top screen edge.
- Changed step integration from fixed 2-block step / max 48 steps to adaptive:
  `stepSize = max(2.0, pathLen / 64.0)` with `STEPS = min(..., 64)`.
  Ensures the full fog band is always covered in ≤ 64 steps regardless of band height
  (e.g. a 164-block band now uses ~3-block steps rather than capping at 96-block coverage),
  while the `max(2.0, ...)` floor prevents over-sampling on short paths.

**Status:** Artifacts still present (top-right fog bleeding through terrain).

## Build 59
- No intentional changes — same code as build 58, built a second time.

**Status:** Artifacts still present (top-right fog bleeding through terrain).

## Build 60
- Fixed far-plane mismatch: replaced hardcoded `far=1024` in InvProjMatStable with values
  extracted from the actual projection matrix via `m22()` and `m32()`.
  Formula: `near = m32 / (m22 - 1)`, `far = m32 / (m22 + 1)`.
  Root cause: MC's far plane scales with render distance (~256–512 blocks). Decoding depth
  with far=1024 overestimated sceneT — ~119 blocks at 100 actual — letting fog bleed through
  terrain at distance.
- ALSO added InvProjMatActual (MC's real bobbed projection) for depth decode — this was a
  mistake. Bob changes m03/m13 every frame, which alters reconstructed viewPos.x/y, which
  changes sceneT, which moves tExit across step boundaries → severe flickering artifacts.
  Same failure mode as build 52, now in the depth path instead of the ray path.

**Status:** BROKEN — flickering artifacts worse than before. InvProjMatActual reverted in build 61.

## Build 61
- Reverted InvProjMatActual: removed it from Java, flat_fog.json, and flat_fog.fsh.
  Retained far-plane extraction (m22/m32) from build 60, expecting that to be enough.

**Status:** BROKEN — bobbing returned. Far-plane extraction is also unstable (see build 62).

## Build 62
- Reverted near/far extraction from m22/m32. Back to hardcoded near=0.05f, far=1024.0f.
- Root cause of build 61 bobbing: bob rotation modifies m22 each frame. The far formula
  divides by (m22+1) which is ~0.0003 for typical near/far, so even a 1° bob rotation
  changes the extracted far from ~384 to ~900+, oscillating each frame → fog bobs visually.
  m11 is not affected the same way (it divides by a full-magnitude value), which is why
  FOV extraction via m11 is stable but near/far extraction via m22/m32 is not.
- The far-plane mismatch (1024 vs actual ~256–512) does cause sceneT to be overestimated
  at long range, but the original square artifacts appear at 10–30 block range where the
  error is under 3% — so far-plane mismatch was not their cause. Root cause of the squares
  remains undiagnosed.

**Status:** Pending test — bobbing fix confirmed, square artifacts still under investigation.

## Build 63
- Increased HEIGHT_VARIATION from 3.0f to 20.0f.
- Root cause of square artifacts: HEIGHT_VARIATION=3.0 → rolloff zone = 3.0*0.75 = 2.25 blocks.
  Minimum step size is 2 blocks. A 2-block step across a 2.25-block rolloff zone means the density
  jumps from 0 → 0.79 in one step (amplified by the density*density curve), producing hard visible
  edges at the fog top surface. Confirmed by user testing: artifacts disappeared above Y≈190
  (matching FogTopY in config) and below a ~32° upward viewing angle (the angle at which the
  line of sight crosses the Y≈190 fog surface from below — not a depth or matrix issue at all).
- Fix: widen rolloff zone to 20*0.75 = 15 blocks. Density now ramps gradually over 15 blocks,
  making the fog surface smooth and invisible at 2-block step size.
- FogTopY may need to be tuned down ~17 blocks to compensate for the wider band.

**Status:** Squares still present. Also discovered: windowed mode = entire screen white below ~34°
(depth clamping fails for ALL pixels). Root cause identified — see build 65.

## Build 65
- Switched depth sampling from `vTexCoord * textureSize(DepthSampler, 0)` back to
  `gl_FragCoord.xy` (originally used in build 52).
- Root cause: `vTexCoord * textureSize` is only correct when the depth texture dimensions
  exactly match the framebuffer the fog shader is rendering into. In windowed mode (or with
  display scaling, or after a resize), these can differ — causing wrong pixel coordinates,
  returning sky depth (1.0) for pixels that have geometry, so depth clamping never fires
  and fog renders everywhere. In fullscreen the mismatch is smaller, producing wrong depth
  only at some pixels — the white squares.
- `gl_FragCoord.xy` is always in framebuffer space by definition, regardless of window size,
  display scaling, or render target configuration. No size assumptions required.
- Build 57 replaced `gl_FragCoord.xy` to fix the DepthTexSize uniform problem, but the
  correct fix to that problem was always `gl_FragCoord.xy` directly.

**Status:** gl_FragCoord.xy did not fix the squares. Root cause of squares identified — see build 66.

## Build 66
- Replaced FBM surface cutoff with smooth height-based top fade in flat_fog.fsh.
- Root cause of squares (confirmed by void-world testing): the FBM fog surface defines a
  hard boundary. Where the FBM dips the surface below the camera at some XZ positions, those
  pixels show no fog; adjacent pixels with surface above camera show dense fog. Step size
  snaps across this boundary in one step → checkerboard at the fog surface. Increasing
  HeightVariation widened the fade zone but did not eliminate the hard boundary itself.
- Fix: removed `fogTopAt / below <= 0 continue` logic. Replaced with:
    topFade = clamp((FogTopY + HeightVariation - pos.y) / HeightVariation, 0, 1)
    density = topFade² × FogDensity × 0.06
  Density is now purely height-based — full below FogTopY, smoothly fades to zero over
  HeightVariation blocks above FogTopY. No FBM surface = no hard boundary = no squares.
- HEIGHT_VARIATION reset to 10.0f (was 20.0f from build 63). Now controls fade zone
  width (10 blocks) rather than FBM surface amplitude.
- fogTopAt() kept in shader as dead code for potential future use.

**Status:** White squares unchanged. Also introduced fog body blockiness (regression) — FBM
removal eliminated the organic variation that disguised discrete step edges. These are two
separate problems; build 66 only addressed the white squares hypothesis, which turned out incorrect.

## Build 67
- Added per-pixel jitter to the ray-march step offset in flat_fog.fsh.
  `jitter = fract(sin(dot(gl_FragCoord.xy, vec2(127.1, 311.7))) * 43758.5453123)`
  Step sample position changes from `tEntry + (i + 0.5) * stepSize` to
  `tEntry + (i + jitter) * stepSize`.
  Root cause hypothesis: discrete uniform step spacing creates a coherent regular
  grid near the fog top — each pixel samples at the same relative position within
  its first step, so boundary transitions align across large screen regions → visible
  square pattern. Jitter gives each pixel a random phase within [0, stepSize), 
  converting the grid into noise that is invisible at normal viewing distances.

**Status:** Jitter changed fog body appearance but white squares completely unchanged.
Squares confirmed NOT caused by step-integration aliasing.

## Build 68
- Changed render stage from AFTER_TRANSLUCENT_BLOCKS to AFTER_WEATHER in FlatFogRenderer.java.
  Root cause of white squares: MC clouds render AFTER translucent blocks (between
  AFTER_TRANSLUCENT_BLOCKS and AFTER_WEATHER), so they painted onto the framebuffer AFTER
  the fog shader ran — fog could not cover them. Confirmed by user: Fabulous! mode had no
  squares because Fabulous sends clouds to a separate render target and composites them
  differently. In Fast/Fancy, clouds go directly to the main framebuffer after translucent.
  Fix: AFTER_WEATHER ensures clouds are already in the color buffer and depth buffer before
  the fog runs. Fog then blends over clouds, covering them. Depth clamp also correctly clips
  fog integration to cloud depth (fog stops at cloud surface, not beyond it).

**Status:** Fabulous: fog disappeared entirely. Fast/Fancy: fog visible but squares still present.
Root cause: Fabulous composites its separate render targets before AFTER_WEATHER, so fog
rendered after the composite and was discarded. Clouds in Fast/Fancy render after AFTER_WEATHER.

## Build 69
- Branch render stage on graphics mode (added GraphicsStatus import):
    Fabulous  → AFTER_TRANSLUCENT_BLOCKS (before Fabulous composite, confirmed working)
    Fast/Fancy → AFTER_LEVEL (after clouds are drawn to main framebuffer)
  Pre-filter accepts both stages, then discards whichever doesn't match the current mode.
  In Fast/Fancy, clouds render to the main framebuffer between AFTER_TRANSLUCENT_BLOCKS and
  AFTER_LEVEL. In Fabulous, clouds go to a separate target composited before AFTER_WEATHER,
  so any stage ≥ AFTER_WEATHER in Fabulous renders after the composite into the wrong buffer.

**Status:** Fabulous: working (no squares). Fast/Fancy: fog visible, white squares still present.
Squares confirmed present even at AFTER_LEVEL (after ALL level rendering including clouds).

## Builds 70–72
No notes recorded.

## Build 73
- Added `sceneWorldY < bandTop` guard to the depth clamp in flat_fog.fsh.
  Root cause hypothesis (from LevelRenderer bytecode analysis): MC's cloud geometry uses a
  `cloudsDepthOnly` depth-prepass that writes cloud depth values to the main depth buffer in
  Fast/Fancy mode. InvProjMatStable (far=1024) overestimates decoded sceneT for far geometry
  (clouds at 242 blocks decode to sceneT≈404 — normally no clamp since 404 > tExit=257).
  However, if `cloudsDepthOnly` writes a non-perspective "near-plane trick" depth to occlude
  particles, sceneT decodes to a very short distance → tExit clipped → near-zero fog alpha
  → cloud color shows through as white squares. In Fabulous mode, cloudsTarget has its own
  depth buffer separate from the main depth, so our fog never sees this cloud prepass depth.
  Fix: reconstruct sceneWorldY = camPos.y + rayDir.y * sceneT after depth decode.
  Only apply depth clamp when sceneWorldY < bandTop. Geometry above bandTop (clouds, sky)
  is past where the fog ray exits anyway; clamping there can only reduce fog density wrongly.
  Terrain and blocks inside/below the fog band still trigger the clamp correctly.

**Status:** REGRESSION — fog fixed to player's feet in Fast/Fancy. Root cause: sceneWorldY ≈
camPos.y when cloud depth prepass writes small-distance rawDepth; camPos.y < bandTop (player
inside fog) so guard always passes → clamp clips tExit to near-zero → fog is a thin shell
at camera. Also white squares still present. Reverted in build 74.

## Build 74
- Reverted sceneWorldY guard (back to plain `sceneT > 0.1` from build 69).
- Removed jitter from build 67 (restored `(float(i) + 0.5) * stepSize`).
- Shader is back to build 69 state: correct fog position, white squares still present.
- Next diagnostic: remove the entire depth clamp block and test.

**Status:** Confirmed correct fog position; squares still present.

## Build 75
- Removed the entire depth clamp block from flat_fog.fsh (no DepthSampler read,
  no sceneT, no tExit clamping to geometry).
  Root cause confirmed by diffing GroundMist V0.200 vs V0.210: V0.200 had no depth clamp
  and no squares; V0.210 added the depth clamp and squares appeared immediately. The depth
  clamp is the source of the white squares in Flat Fog as well.
  DepthSampler uniform kept in JSON/Java (harmless while unused in shader).

**Status:** Fog renders through solid blocks. "Fog at player's feet" is fog visible through terrain below the player — the depth clamp was needed.

## Build 76
- No intentional code changes — same shader as build 75, built a second time.

**Status:** "Fog locked to the feet" confirmed. Root cause: without depth clamp, fog ray marches through solid terrain, making the fog band below the player visible through the ground regardless of height.

## Build 77
- Re-added depth clamp to flat_fog.fsh using `texelFetch(DepthSampler, ivec2(gl_FragCoord.xy), 0).r`.
- Added `sceneT > 2.0` guard before applying the clamp.
  Root cause of white squares (revised): MC clouds use a depth-prepass in Fast/Fancy mode that
  writes near-plane rawDepth (~0) to the main depth buffer. Those values decode to sceneT ~0.05
  with our near=0.05 / far=1024 formula. Previous depth clamp had no guard, so it clipped tExit
  to ~0.05 → fog integrated only 0.05 blocks → nearly transparent → sky showed through at cloud
  positions = white squares.
  Fix: skip the depth clamp when sceneT <= 2.0. Cloud prepass values (~0.05) are ignored; real
  terrain (always > 2 blocks from camera) is still clamped. Fog barely accumulates in 2 blocks
  (~16% opacity) so the small near-geometry gap is visually negligible.
- Removed duplicate `if (tEntry >= tExit) discard;` that was left over from build 75's removal.

**Status:** Fog gone entirely + white below feet + white squares still present. The sceneT > 2.0 guard did not solve the problem.

## Build 79 — Diagnostic
- Removed depth clamp entirely (fog renders through terrain, like build 75/76).
- Added depth-buffer color diagnostic: reads rawDepth, decodes sceneT, and colors the fog
  based on what the depth buffer contains at each pixel. No tExit modification.
  Color key:
    RED    = sceneT <   1 block  (near-plane depth — some prepass writing depth ~0)
    ORANGE = sceneT   1–  5 blocks
    YELLOW = sceneT   5– 20 blocks
    GREEN  = sceneT  20–100 blocks
    BLUE   = sceneT > 100 blocks
    normal = rawDepth == 1.0 (sky — no geometry in depth buffer)
  Goal: look at where white squares used to appear (~32° upward angle at fog top) and
  note the color. The color reveals exactly what depth value the clamp was reacting to.

**Status:** Pending test — report what color appears at the white square positions.

## Build 80 — Production fix (combined guards)
- Replaced build 79 diagnostic color code with production depth clamp using two combined guards.
- Guard 1: `sceneT > 2.0` — filters near-plane prepass depths (sceneT ~0.05 blocks) written by
  any depth-only prepass. Near geometry always > 2 blocks so real terrain still clamps.
- Guard 2: `sceneWorldY < bandTop` — filters geometry above the fog band (cloud blocks).
  Cloud blocks in Fast/Fancy write their actual depth (~100+ blocks) to the main depth buffer.
  sceneWorldY = camPos.y + rayDir.y * sceneT. For clouds at Y=128 with bandTop=110:
  sceneWorldY=128 > bandTop=110 → skip clamp → no opacity reduction at cloud positions.
  Cloud-gap pixels (rawDepth=1.0) also skip clamp → both cloud and gap pixels get full fog →
  no contrast → no white squares.
- Root cause confirmed by build 79 diagnostic: cloud block positions had rawDepth < 1.0
  (actual cloud depth), depth clamp fired there, reducing fog opacity. Cloud-gap positions
  had rawDepth=1.0 (sky), clamp skipped, full fog opacity. Opacity contrast = visible grid.

**Status:** Pending test — needs compile and Fast/Fancy test.

## Builds 81–84
- No notes recorded (intermediate test builds, same combined-guard shader).
- User confirmed: white squares still present in fullscreen mode; works in windowed/minimized mode.
  This fullscreen-only behavior points to a coordinate-space mismatch, not a depth logic error.

## Build 85 — Depth texture sampling fix (texelFetch → texture)
- Root cause: `texelFetch(DepthSampler, ivec2(gl_FragCoord.xy), 0)` requires the depth texture to
  be exactly the same pixel dimensions as the current framebuffer. In fullscreen mode, these can
  differ — the depth texture may be at a different resolution than the FBO the fog shader renders
  into. When they differ, we read depth from the WRONG texel: cloud depth shows up at terrain
  positions and vice versa. The sceneWorldY guard correctly skips cloud positions when we read
  the RIGHT depth value, but wrong-coordinate reads defeat the guard and make the clamp fire at
  unexpected positions, recreating the white squares.
- Fix: replace with `texture(DepthSampler, vTexCoord).r`. `vTexCoord` is normalized UV [0,1]
  derived from the NDC vertex positions (Position.xy * 0.5 + 0.5 in the vertex shader). Normalized
  UV coordinates map correctly to any texture regardless of its pixel dimensions — no size-match
  requirement. In windowed mode (where sizes already matched), the two forms produce identical
  results so the windowed-mode behavior is unchanged.

**Status:** Pending test — compile and test in fullscreen Fast/Fancy mode.

## Build 91 — Diagnostic sky-depth color fix
- Changed the build 79-style depth diagnostic so `rawDepth == 1.0` renders magenta instead
  of normal fog gray. Diagnostic mode should no longer show gray fog anywhere.
- If the former white/gray fullscreen squares become magenta, those pixels are sky/no-depth
  samples. If the minimized/windowed view becomes entirely magenta, the depth sampler is
  returning sky depth for the whole fog pass in that mode.

**Status:** User confirmed the squares are magenta. They are sky/no-depth pixels, not a
separate fog color path.

## Build 92 — Restore production fog with sky/cloud-safe depth clamp
- Replaced the diagnostic color output with normal fog color.
- Restored depth clamping using `texture(DepthSampler, vTexCoord).r`, keeping normalized UV
  sampling so fullscreen/windowed texture-size differences do not pick the wrong texel.
- Clamp only when both guards pass:
  - `sceneT > 2.0` filters near-plane/prepass depth values.
  - `sceneWorldY < bandTop` means the depth belongs to terrain/geometry inside the fog band.
- `rawDepth == 1.0` sky/no-depth pixels skip the clamp. Cloud pixels above the band also skip
  the clamp. That makes cloud gaps and cloud blocks receive the same fog treatment, removing
  the square contrast while still allowing terrain inside the fog band to occlude the fog.

**Status:** User confirmed pixels/squares are still visible.

## Build 93 — Depth-clamp decision mask diagnostic
- Replaced normal fog color with a clamp-decision mask:
  - RED = depth actively clamped fog.
  - BLUE = depth existed but was above/outside the fog band, so clamp was skipped.
  - YELLOW = near/prepass depth skipped by the `sceneT > 2.0` guard.
  - MAGENTA = no depth was written at this pixel.
- Goal: determine whether the remaining squares are still caused by active depth clamping or
  whether they remain even when the clamp is skipped.

**Status:** User confirmed fullscreen square pixels are MAGENTA, and minimized/windowed is
entirely MAGENTA. The main depth texture is returning sky/no-depth for the whole pass in
windowed mode and for the square holes in fullscreen.

## Build 94 — Copy depth before sampling
- Hypothesis: sampling `mc.getMainRenderTarget().getDepthTextureId()` while drawing the fog
  back into the same main framebuffer creates an undefined framebuffer feedback loop. This
  matches the mode-dependent result: some usable values in fullscreen, all `1.0` in windowed.
- Added a private `TextureTarget` depth-copy target for Fast/Fancy.
- Before drawing the fog at `AFTER_LEVEL`, copy main depth into that separate target with
  `depthCopyTarget.copyDepthFrom(mainTarget)`, rebind the main target for drawing, then bind
  `depthCopyTarget.getDepthTextureId()` as `DepthSampler`.
- Left the Build 93 clamp-decision colors active for this test. If this works, minimized/windowed
  should no longer be entirely MAGENTA, and terrain should show RED/YELLOW/BLUE based on the
  clamp decision.

**Status:** User confirmed this solved the fullscreen/windowed purple square issue.

## Build 95 — Production fog with depth-copy fix
- Kept the Build 94 separate depth-copy target for Fast/Fancy:
  copy main depth into `TextureTarget`, rebind main target, then sample the copied depth.
- Removed the clamp-decision diagnostic colors and restored normal `FogColorRGB` output.
- Kept the guarded clamp:
  - `rawDepth == 1.0` skips clamp.
  - `sceneT <= 2.0` skips clamp.
  - geometry with `sceneWorldY >= bandTop` skips clamp.
  - only geometry inside/below the fog band clamps `tExit`.

**Status:** Pending final fullscreen and minimized/windowed visual test.

## Build 96 — Restore realistic fog layer
- Kept the Build 95 depth-copy fix. Fast/Fancy still copies main depth into a private
  `TextureTarget` before sampling, avoiding the framebuffer feedback bug that caused the
  white/gray/purple square artifacts.
- Restored a natural fog-layer shape without reintroducing the old hard FBM cutoff:
  - `fog_top_y` from the server/world config remains the base fog layer height.
  - `fog_bottom_y` remains the fog floor.
  - The shader now computes a broad drifting local top around `fog_top_y`.
  - Density fades smoothly across the top edge instead of turning on/off at a hard surface.
  - A subtle detail-noise multiplier breaks up the body of the fog without creating square
    depth artifacts.
- Tuned visual constants:
  - `HEIGHT_VARIATION`: 10.0 -> 7.0
  - `HEIGHT_SCALE`: 0.0005 -> 0.0035
  - `FOG_DENSITY`: 1.5 -> 0.85
  - `FOG_ALPHA`: 0.92 -> 0.82
- Expanded the conservative ray band to include the top softness zone:
  `FogTopY + HeightVariation + TOP_SOFTNESS`.

**Status:** User reported the fog appears affixed to the camera instead of the world.

## Build 97 — Restore world-anchored depth clamp distance
- Kept the Build 94/95 depth-copy fix. Do not sample the main framebuffer depth directly.
- Fixed the depth clamp distance decode:
  - Build 96 decoded `sceneT` with a forward-Z projection formula.
  - The ray marcher uses Euclidean/world ray distance for `t`.
  - Mixing those distance spaces can leave the clamp too loose near terrain, making fog read
    like camera-attached screen haze.
- Restored Euclidean depth reconstruction:
  `viewPos = InvProjMatStable * ndcPos`, then `sceneT = length(viewPos.xyz / viewPos.w)`.
- Lowered the near-depth guard from `sceneT > 2.0` to `sceneT > 0.1` so nearby terrain/walls
  can anchor the fog again. The cloud/special-depth protection still comes from the copied
  depth texture plus the `sceneWorldY < bandTop` guard.
- Slightly tightened the ray-march step:
  `max(2.0, pathLen / 64.0)` -> `max(1.25, pathLen / 80.0)`.

**Status:** Pending fullscreen/minimized visual test.

## Build 98 — Clamp against all real scene geometry
- User reported fog still rendering through blocks.
- The active shader had drifted back to the older `texelFetch(DepthSampler, ivec2(gl_FragCoord.xy), 0)` path.
  Replaced it again with `texture(DepthSampler, vTexCoord).r`, which is the safe path when sampling
  the copied depth target.
- Removed the `sceneWorldY < bandTop` clamp guard. With the depth-copy feedback bug fixed, this guard
  is too broad: it skips real blocks above the fog top and allows fog behind them to render through.
- Depth clamp now treats any real depth as an occluder:
  `sceneT = length((InvProjMatStable * ndcPos).xyz / w)`, then `tExit = min(tExit, sceneT)` for
  `sceneT > 0.1`.
- This should restore the original world-anchored behavior while keeping the fullscreen/windowed
  square fix.

**Status:** Pending fullscreen/minimized visual test.

## Build 105 — Soften far silhouettes and restore world-space top shape
- User reported close fog/block occlusion looks good, but far fog reads like vertical walls and
  cuts through distant terrain.
- The active shader still used a hard horizontal layer top: `FogTopY + HeightVariation`. The FBM
  top function existed but was not used by density.
- Restored a world-space local fog top:
  - `fogTopAt(worldXZ)` drifts slowly around the configured `fog_top_y`.
  - `fogDensityAt(worldPos)` fades smoothly through a 14-block top softness zone.
  - Added subtle body texture so the layer does not read as a flat slab at distance.
- Kept near/mid depth occlusion, but softened distant depth silhouettes:
  - Geometry closer than 180 blocks still clamps fog normally.
  - Between 180 and 320 blocks, clamp strength fades out.
  - Beyond 320 blocks, distant scenery receives atmospheric fog rather than carving sharp
    vertical masks through the layer.
- This keeps nearby blocks from showing fog through them while making the far layer behave more
  like atmosphere.

**Status:** Pending fullscreen/minimized visual test.

## Build 106 — Move far-depth softening beyond render distance
- User reported the far softening still happens too close; it should be pushed beyond whatever
  chunk render distance the user has configured.
- Replaced hard-coded shader constants:
  - `DEPTH_FADE_START = 180`
  - `DEPTH_FADE_END = 320`
- Added shader uniforms:
  - `DepthFadeStart`
  - `DepthFadeEnd`
- Java now computes these from the active client render distance:
  - `renderDistanceBlocks = mc.options.getEffectiveRenderDistance() * 16`
  - `DepthFadeStart = renderDistanceBlocks + 64`
  - `DepthFadeEnd = renderDistanceBlocks + 224`
- This keeps depth clamping fully strict across the user-visible chunk range, then only softens
  silhouettes beyond that range.

**Status:** Pending fullscreen/minimized visual test.

## Build 107 — Distant Horizons render-distance compatibility
- User reported Distant Horizons overrides/extents visible terrain beyond vanilla chunk render distance,
  causing the far-depth fade to start too close and look like a wall.
- Distant Horizons' API exposes `DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue()`;
  that value is the LOD render radius in chunks.
- Added reflection-based DH lookup so Flat Fog does not require a compile-time dependency on DH:
  - If DH API is present and initialized, query its LOD chunk render distance.
  - Cache the value for one second to avoid reflection every frame.
  - Use `max(vanillaRenderDistanceBlocks, dhRenderDistanceBlocks)` as the base for depth fade.
  - If DH is absent or not initialized, fall back to vanilla render distance.
- Depth fade still starts at `base + 64` and ends at `base + 224`, but `base` now respects
  DH's much larger LOD radius.

**Status:** User reported the wall still appears.

## Build 108 — Extend fog trace distance for Distant Horizons
- Root cause follow-up: the shader still hard-capped fog marching at `1024.0` blocks:
  - horizontal rays used `tExit = 1024.0`
  - band intersections used `min(..., 1024.0)`
- With Distant Horizons, visible terrain can extend far beyond 1024 blocks, so the fog volume
  itself ended in a visible wall even if depth fade was pushed outward.
- Added `MaxTraceDistance` shader uniform.
- Java now sets:
  - `MaxTraceDistance = max(1024, renderDistanceBlocks + 512)`
  - where `renderDistanceBlocks` is the max of vanilla render distance and DH LOD distance.
- Added a DH-loaded fallback: if the DH API/config is not initialized or reflection fails but
  NeoForge reports mod id `distanthorizons` loaded, assume an 8192-block DH horizon instead of
  falling back to vanilla distance.
- This keeps Flat Fog's layer extending beyond DH terrain instead of ending at the old 1024-block
  cap.

**Status:** User screenshot showed the wall is actually fog clipping through DH far LOD islands.

## Build 109 — Sample Distant Horizons depth texture
- Root cause follow-up: DH far LOD terrain is not necessarily present in the vanilla/main depth
  texture that Flat Fog copies. Extending `MaxTraceDistance` keeps the fog volume from ending
  early, but it still cannot occlude against DH-only terrain.
- Added optional `DhDepthSampler` and `HasDhDepth` shader uniform.
- Java now queries `DhApi.Delayed.renderProxy.getDhDepthTextureId()` by reflection.
  - If DH provides a valid depth texture id, bind it to `DhDepthSampler`.
  - If unavailable, `HasDhDepth = 0` and Flat Fog behaves as before.
- Shader now checks DH depth after vanilla depth clamp:
  - If DH depth exists at the pixel, discard the fog pixel.
  - This is a conservative first pass to prevent the horizontal fog layer from cutting through
    far DH islands.
- This may remove Flat Fog in front of DH LOD terrain pixels rather than blending it perfectly;
  if the wall is gone, the next refinement can replace the discard with a softer DH-aware blend.

**Status:** User confirmed fog wall is gone, but far terrain looks transparent/cut out because
the shader discarded fog pixels wherever DH depth existed.

## Build 110 — Use DH depth as clamp instead of discard
- Replaced the conservative DH-depth discard with DH-depth clamping.
- If `DhDepthSampler` has depth at a pixel:
  - reconstruct `sceneT` from the DH depth value,
  - clamp `tExit = min(tExit, sceneT)`,
  - continue normal fog integration.
- This should preserve the wall fix while avoiding punched-out transparent-looking terrain.

**Status:** User reported terrain still looks transparent, including things inside the fog.
Likely because DH depth uses a different projection/depth space than vanilla depth, so
reconstructing `sceneT` with `InvProjMatStable` clamps to the wrong distance.

## Build 111 — Use DH depth only as top-wall mask
- Stop using DH depth as a distance/depth clamp.
- DH depth is now only a boolean mask that tells us a DH LOD pixel exists at this screen pixel.
- After normal vanilla-depth fog integration, if DH depth exists, fade alpha only when the ray's
  entry point is near the fog layer's upper wall:
  `topWallMask = smoothstep(bandTop - TOP_SOFTNESS * 1.5, bandTop, topRayY)`.
- This avoids removing fog throughout the whole layer while still targeting the horizontal wall
  that cuts through far DH islands.

**Status:** Pending Distant Horizons visual test.

## Build 112 — Distant Horizons compatibility warning
- User decided full Distant Horizons compatibility is too much work for now.
- Added a one-time client chat warning when mod id `distanthorizons` is loaded:
  `[Flat Fog] Warning: Flat Fog is not currently compatible with Distant Horizons. Visual artifacts may occur.`
- Warning is shown once per client session after a player exists, so it should not spam chat.
- Rendering code remains otherwise unchanged from the current DH experiment build.

**Status:** Pending basic launch test.

---

## Reference Summary

### V0.200 vs V0.210 — What Changed and Why It Matters

Both GroundMist versions draw a **3D box** (faces at actual world positions), not a fullscreen quad.
The fragment shader receives the per-vertex world ray direction as `vCamRelPos` from the vertex shader.

**V0.200 (no white squares):**
- Stage: `AFTER_PARTICLES` — fires BEFORE clouds render in Fast/Fancy. Cloud depth prepass is NOT yet in the depth buffer.
- `RenderSystem.enableDepthTest()` — GPU hardware depth test active. Each fog face fragment's actual 3D depth is compared against the depth buffer; terrain blocks occlude the fog naturally. No manual depth clamp needed.
- Camera is always ABOVE the fog band (GroundMist is ground-level fog, band top at ~Y=20–30). Standard face culling works correctly from outside the volume.
- No DepthSampler, no ModelViewMat, no ProjMat. No manual depth decode at all.

**V0.210 (white squares introduced):**
- Stage: still `AFTER_PARTICLES`
- Switched to `RenderSystem.disableDepthTest()` — gave up GPU depth testing
- Added manual depth clamp using `DepthSampler`, `ProjMat`, `ModelViewMat`
- Added bottom face + `GL11.glCullFace(inside ? GL_FRONT : GL_BACK)` to handle camera inside band
- Depth decode: `float viewZ = ProjMat[3][2] / (-ndcZ - ProjMat[2][2])`, then `sceneT = viewZ / vrd.z`
- Guards: `vrd.z < -0.001` (forward-going ray) and `sceneT > 0.0` (positive distance) — not enough to stop squares
- **White squares appeared immediately.**

**Why V0.200 had no white squares:** ran before cloud prepass wrote anything to the depth buffer,
and used GPU depth testing which compares actual 3D fragment depth directly — no decode errors possible.

**Why Flat Fog can't just copy V0.200:** Flat Fog's camera is INSIDE the fog band, not above it.
V0.200's box geometry works cleanly only when the camera is outside (above) the fog volume.
Flat Fog also uses a fullscreen quad (not a box), which can't use GPU depth test the same way.

---

### Flat Fog Build Summary

| Build | Key Change | Result |
|-------|-----------|--------|
| 1–10 | Anti-bob investigation | Found bob is in projection matrix, not model-view |
| 50 | Split InvProjMatStable / InvProjMat for ray vs depth | Bobbing still present |
| 52 | InvProjMatStable for both (no bobbed depth decode) | Bob OFF = stable. Bob ON = expected relative motion |
| 53 | Extract FOV from proj.m11() instead of config value | Fixes sprint FOV mismatch |
| 54 | Camera Y cull — added then removed | Cull fired at normal viewing position |
| 55 | Use event.getProjectionMatrix() directly | BROKEN — bob translation caused fog to blink |
| 56 | m11 FOV extraction + rotation-only bob in InvViewMat | **CONFIRMED WORKING — bob stable** |
| 57 | Replace DepthTexSize uniform with textureSize() | Squares still present |
| 58 | Adaptive step size (max 2, pathLen/64) | Squares still present |
| 60 | Extract near/far from m22/m32 of proj matrix | BROKEN — flickering (bob changes m22 each frame) |
| 62 | Revert to hardcoded near=0.05, far=1024 | Bob stable; confirmed far-plane mismatch NOT cause of squares |
| 63 | HEIGHT_VARIATION 3.0 → 20.0 | Confirmed step size too large for rolloff zone was causing hard edges; squares still present |
| 65 | Switch depth sampling back to gl_FragCoord.xy | gl_FragCoord.xy did NOT fix squares |
| 66 | Replace FBM surface with smooth height-based topFade | FBM not cause of squares; regression: fog body blockiness |
| 67 | Add per-pixel jitter to step offset | Step aliasing NOT cause of squares |
| 68 | Stage → AFTER_WEATHER | Fabulous broken (composites before AFTER_WEATHER); Fast/Fancy squares still present |
| 69 | Fabulous → AFTER_TRANSLUCENT_BLOCKS, Fast/Fancy → AFTER_LEVEL | **Fabulous working (no squares)**; Fast/Fancy: squares still present |
| 73 | sceneWorldY < bandTop guard on depth clamp | REGRESSION — fog fixed to player's feet |
| 74 | Revert sceneWorldY guard, remove jitter | Correct fog position; squares still present |
| 75 | Remove entire depth clamp block | Fog through terrain ("at feet"); squares status unconfirmed |
| 76 | Same code as 75, rebuilt | Fog through terrain confirmed |
| 77 | Depth clamp re-added with sceneT > 2.0 guard | Fog gone entirely + white below feet + squares still present |

### Confirmed Facts

- Squares appear only in Fast/Fancy, never in Fabulous — confirmed build 69
- Squares are NOT caused by step aliasing or jitter — confirmed build 67
- Squares are NOT caused by FBM surface — confirmed build 66+
- Squares are NOT caused by far-plane mismatch — confirmed build 62
- Removing the depth clamp removes the squares (but fog renders through terrain) — builds 75–76 + V0.200/V0.210 diff
- Re-adding the depth clamp brings squares back — every depth clamp attempt
- Every manual depth decode approach tried has failed to stop squares — builds 57–77
- V0.200 avoided the problem using GPU depth test + box geometry + AFTER_PARTICLES (before cloud prepass) — V0.200 renderer analysis

### What Is Now Known (from build 79 diagnostic)

Cloud blocks write their actual perspective depth (sceneT > 100 blocks) to the main depth buffer
in Fast/Fancy mode. Cloud GAP positions (rawDepth=1.0) get full fog. Depth clamp fires at cloud
block pixels → reduced fog opacity there. Contrast between cloud-block and cloud-gap fog opacity
creates the visible white square grid matching MC's 16×16 cloud block pattern.

Fix (build 80+): skip the depth clamp when sceneWorldY >= bandTop. Clouds are always above the fog
band; by ignoring their depth, both cloud-block and cloud-gap positions get identical full fog opacity
→ no contrast → no squares. Terrain within the band still triggers the clamp correctly.

### Current Stable Baseline

Build 74 state (depth clamp active, no sceneWorldY guard, no jitter) = correct fog position,
white squares still present in Fast/Fancy. Build 76 (no depth clamp) = no squares but fog through terrain.
