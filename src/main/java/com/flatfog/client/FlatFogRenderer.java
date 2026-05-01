package com.flatfog.client;

import com.flatfog.FlatFog;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.GraphicsStatus;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

@EventBusSubscriber(modid = FlatFog.MOD_ID, value = Dist.CLIENT)
public class FlatFogRenderer {

    private static final float HEIGHT_VARIATION = 7.0f;
    private static final float HEIGHT_SCALE     = 0.0035f;
    private static final float FOG_DENSITY      = 0.85f;
    private static final float FOG_COLOR_R      = 0.84f;
    private static final float FOG_COLOR_G      = 0.88f;
    private static final float FOG_COLOR_B      = 0.97f;
    private static final float FOG_ALPHA        = 0.76f;

    static ShaderInstance fogShader = null;
    private static TextureTarget depthCopyTarget = null;
    private static long lastDhDistanceQueryMs = 0L;
    private static Float cachedDhRenderDistanceBlocks = null;
    private static long lastDhDepthQueryMs = 0L;
    private static Integer cachedDhDepthTextureId = null;
    private static boolean warnedDistantHorizons = false;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        // Quick pre-filter: only proceed on the two candidate stages.
        if (stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS &&
            stage != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (fogShader == null || !ClientFogSettings.hasData()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        warnIfDistantHorizonsLoaded(mc);

        // Fabulous composites its separate render targets (clouds, particles, etc.) before
        // AFTER_WEATHER, so the fog must run before that composite — AFTER_TRANSLUCENT_BLOCKS
        // is confirmed working. In Fast/Fancy, clouds render to the main framebuffer after
        // AFTER_TRANSLUCENT_BLOCKS and before AFTER_LEVEL, so the fog must run at AFTER_LEVEL
        // to blend over clouds rather than under them.
        boolean isFabulous = mc.options.graphicsMode().get() == GraphicsStatus.FABULOUS;
        RenderLevelStageEvent.Stage targetStage = isFabulous
                ? RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                : RenderLevelStageEvent.Stage.AFTER_LEVEL;
        if (stage != targetStage) return;

        Vec3 camPos = event.getCamera().getPosition();

        // Stable projection: extract FOV from m11 (stable even under bob rotation).
        // near/far are hardcoded — extracting them from m22/m32 of the bobbed projection is
        // numerically unstable: bob rotation modifies m22 each frame, and far = m32/(m22+1)
        // divides by ~0.0003, so a tiny bob-induced change in m22 produces a wildly wrong far
        // that oscillates with the walk cycle, causing the fog surface to bob visually.
        Matrix4f proj    = event.getProjectionMatrix();
        float yscale     = proj.m11();
        float fovRad     = 2.0f * (float) Math.atan(1.0f / yscale);
        float aspect     = (float) mc.getWindow().getWidth() / mc.getWindow().getHeight();
        Matrix4f invProjMatStable = new Matrix4f().perspective(fovRad, aspect, 0.05f, 1024.0f).invert();
        setUniform("InvProjMatStable", invProjMatStable);

        // Model-view is pure camera rotation (MC applies bob to projection, not model-view).
        Matrix4f invViewMat = new Matrix4f(event.getModelViewMatrix()).invert();

        // Apply only the ROTATION component of view bob to InvViewMat so fog rays co-rotate
        // with the world geometry. We skip the translation component (bobX, bobY) because that
        // lives in the projection matrix and using it for ray directions causes the off-screen blink.
        if (mc.options.bobView().get() && mc.player != null) {
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            float walkDelta = mc.player.walkDist - mc.player.walkDistO;
            float walkPhase = -(mc.player.walkDist + walkDelta * partialTick);
            float bobAmp    = Mth.lerp(partialTick, mc.player.oBob, mc.player.bob);
            float bobRZ = Mth.sin(walkPhase * (float) Math.PI) * bobAmp * 3.0f;
            float bobRX = Math.abs(Mth.cos(walkPhase * (float) Math.PI - 0.2f) * bobAmp) * 5.0f;
            Matrix4f bobRot = new Matrix4f()
                    .rotateZ((float) Math.toRadians(bobRZ))
                    .rotateX((float) Math.toRadians(bobRX));
            invViewMat.mul(new Matrix4f(bobRot).invert());
        }

        setUniform("InvViewMat", invViewMat);

        float gameTime = (float)(mc.level.getGameTime() % 24000L) / 24000.0f;
        setUniform("GameTime",        gameTime);
        setUniform("CamWorldPos",     (float)camPos.x, (float)camPos.y, (float)camPos.z);
        setUniform("FogTopY",         ClientFogSettings.getFogTopY());
        setUniform("FogBottomY",      ClientFogSettings.getFogBottomY());
        setUniform("FogDensity",      FOG_DENSITY);
        setUniform("HeightVariation", HEIGHT_VARIATION);
        setUniform("HeightScale",     HEIGHT_SCALE);
        float renderDistanceBlocks = getDepthFadeBaseDistance(mc);
        setUniform("MaxTraceDistance", Math.max(1024.0f, renderDistanceBlocks + 512.0f));
        setUniform("DepthFadeStart",   renderDistanceBlocks + 64.0f);
        setUniform("DepthFadeEnd",     renderDistanceBlocks + 224.0f);
        Integer dhDepthTextureId = getDistantHorizonsDepthTextureId();
        setUniform("HasDhDepth",       dhDepthTextureId != null ? 1.0f : 0.0f);
        setUniform("FogColorRGB",     FOG_COLOR_R, FOG_COLOR_G, FOG_COLOR_B);
        setUniform("FogAlpha",        FOG_ALPHA);

        var depthTarget = mc.getMainRenderTarget();
        if (isFabulous) {
            fogShader.setSampler("DepthSampler", depthTarget.getDepthTextureId());
        } else {
            depthCopyTarget = ensureDepthCopyTarget(depthCopyTarget, depthTarget.width, depthTarget.height);
            depthCopyTarget.copyDepthFrom(depthTarget);
            depthTarget.bindWrite(false);
            fogShader.setSampler("DepthSampler", depthCopyTarget.getDepthTextureId());
        }
        if (dhDepthTextureId != null) {
            fogShader.setSampler("DhDepthSampler", dhDepthTextureId);
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);

        RenderSystem.setShader(() -> fogShader);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        buf.addVertex(-1.0f, -1.0f, 0.0f);
        buf.addVertex( 1.0f, -1.0f, 0.0f);
        buf.addVertex( 1.0f,  1.0f, 0.0f);
        buf.addVertex(-1.0f,  1.0f, 0.0f);
        BufferUploader.drawWithShader(buf.buildOrThrow());

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private static void setUniform(String name, Matrix4f value) {
        var u = fogShader.getUniform(name);
        if (u != null) u.set(value);
    }

    private static void setUniform(String name, float a, float b, float c) {
        var u = fogShader.getUniform(name);
        if (u != null) u.set(a, b, c);
    }

    private static void setUniform(String name, float value) {
        var u = fogShader.getUniform(name);
        if (u != null) u.set(value);
    }

    private static TextureTarget ensureDepthCopyTarget(TextureTarget target, int width, int height) {
        if (target == null) {
            return new TextureTarget(width, height, true, Minecraft.ON_OSX);
        }
        if (target.width != width || target.height != height) {
            target.resize(width, height, Minecraft.ON_OSX);
        }
        return target;
    }

    private static float getDepthFadeBaseDistance(Minecraft mc) {
        float vanillaDistanceBlocks = mc.options.getEffectiveRenderDistance() * 16.0f;
        Float dhDistanceBlocks = getDistantHorizonsRenderDistanceBlocks();
        return dhDistanceBlocks != null ? Math.max(vanillaDistanceBlocks, dhDistanceBlocks) : vanillaDistanceBlocks;
    }

    private static Float getDistantHorizonsRenderDistanceBlocks() {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastDhDistanceQueryMs < 1000L) {
            return cachedDhRenderDistanceBlocks;
        }

        lastDhDistanceQueryMs = nowMs;
        cachedDhRenderDistanceBlocks = null;

        try {
            Class<?> delayedClass = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
            Object configs = delayedClass.getField("configs").get(null);
            if (configs == null) return isDistantHorizonsLoaded() ? 8192.0f : null;

            Object graphics = configs.getClass().getMethod("graphics").invoke(configs);
            Object chunkRenderDistance = graphics.getClass().getMethod("chunkRenderDistance").invoke(graphics);
            Object value = chunkRenderDistance.getClass().getMethod("getValue").invoke(chunkRenderDistance);
            if (value instanceof Number number) {
                cachedDhRenderDistanceBlocks = Math.max(0.0f, number.floatValue() * 16.0f);
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            cachedDhRenderDistanceBlocks = isDistantHorizonsLoaded() ? 8192.0f : null;
        }

        return cachedDhRenderDistanceBlocks;
    }

    private static Integer getDistantHorizonsDepthTextureId() {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastDhDepthQueryMs < 250L) {
            return cachedDhDepthTextureId;
        }

        lastDhDepthQueryMs = nowMs;
        cachedDhDepthTextureId = null;

        try {
            Class<?> delayedClass = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
            Object renderProxy = delayedClass.getField("renderProxy").get(null);
            if (renderProxy == null) return null;

            Object result = renderProxy.getClass().getMethod("getDhDepthTextureId").invoke(renderProxy);
            if (!readDhResultSuccess(result)) return null;

            Object payload = readDhResultPayload(result);
            if (payload instanceof Number number && number.intValue() > 0) {
                cachedDhDepthTextureId = number.intValue();
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            cachedDhDepthTextureId = null;
        }

        return cachedDhDepthTextureId;
    }

    private static boolean readDhResultSuccess(Object result) throws ReflectiveOperationException {
        Object success = result.getClass().getField("success").get(result);
        return success instanceof Boolean value && value;
    }

    private static Object readDhResultPayload(Object result) throws ReflectiveOperationException {
        return result.getClass().getField("payload").get(result);
    }

    private static boolean isDistantHorizonsLoaded() {
        return ModList.get().isLoaded("distanthorizons");
    }

    private static void warnIfDistantHorizonsLoaded(Minecraft mc) {
        if (warnedDistantHorizons || !isDistantHorizonsLoaded()) return;
        warnedDistantHorizons = true;
        mc.player.displayClientMessage(
                Component.literal("[Flat Fog] Warning: Flat Fog is not currently compatible with Distant Horizons. Visual artifacts may occur."),
                false
        );
    }
}
