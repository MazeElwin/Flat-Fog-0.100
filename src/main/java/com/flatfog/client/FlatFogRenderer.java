package com.flatfog.client;

import com.flatfog.FlatFog;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

@EventBusSubscriber(modid = FlatFog.MOD_ID, value = Dist.CLIENT)
public class FlatFogRenderer {

    private static final float HEIGHT_VARIATION = 3.0f;
    private static final float HEIGHT_SCALE     = 0.0005f;
    private static final float FOG_DENSITY      = 1.5f;
    private static final float FOG_COLOR_R      = 0.82f;
    private static final float FOG_COLOR_G      = 0.88f;
    private static final float FOG_COLOR_B      = 0.96f;
    private static final float FOG_ALPHA        = 0.92f;

    static ShaderInstance fogShader = null;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (fogShader == null || !ClientFogSettings.hasData()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Vec3 camPos = event.getCamera().getPosition();

        // Stable projection: extract FOV, near, and far from the actual projection matrix so the
        // depth decode uses the same clip planes MC used to render the scene. Hardcoding far=1024
        // causes sceneT to be overestimated (fog bleeds through terrain at distance). The bob lives
        // in the x/y translation terms of the projection matrix; m22/m32 (depth row) are unaffected.
        Matrix4f proj    = event.getProjectionMatrix();
        float yscale     = proj.m11();
        float fovRad     = 2.0f * (float) Math.atan(1.0f / yscale);
        float aspect     = (float) mc.getWindow().getWidth() / mc.getWindow().getHeight();
        float nearPlane  = proj.m32() / (proj.m22() - 1.0f);
        float farPlane   = proj.m32() / (proj.m22() + 1.0f);
        Matrix4f invProjMatStable = new Matrix4f().perspective(fovRad, aspect, nearPlane, farPlane).invert();
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
        setUniform("FogColorRGB",     FOG_COLOR_R, FOG_COLOR_G, FOG_COLOR_B);
        setUniform("FogAlpha",        FOG_ALPHA);

        var depthTarget = mc.getMainRenderTarget();
        fogShader.setSampler("DepthSampler", depthTarget.getDepthTextureId());

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
}
