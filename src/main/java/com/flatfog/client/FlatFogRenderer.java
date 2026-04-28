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
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Full-screen screen-space fog renderer.
 *
 * Draws a single NDC quad. The fragment shader uses two projection inverses:
 *   InvProjMatStable — built from raw FOV, no view-bob baked in → stable ray directions
 *   InvProjMat       — from the event (view bob included) → correct depth / sceneT
 * InvViewMat is the event model-view inverse (pure camera rotation, no bob).
 */
@EventBusSubscriber(modid = FlatFog.MOD_ID, value = Dist.CLIENT)
public class FlatFogRenderer {

    static ShaderInstance fogShader = null;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (fogShader == null || !ClientFogSettings.hasData()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        float[] color = ClientFogSettings.getFogColor();

        // InvProjMat: from the event projection (includes view-bob). Used only for
        // sceneT depth comparison so it decodes the depth buffer correctly.
        Matrix4f invProjMat = new Matrix4f(event.getProjectionMatrix()).invert();
        setUniform("InvProjMat", invProjMat);

        // InvProjMatStable: built from the raw FOV option, no view-bob baked in.
        // Used only for fog ray directions so the fog horizon doesn't shift with walking.
        float fovRad = (float) Math.toRadians(mc.options.fov().get());
        float aspect = (float) mc.getWindow().getWidth() / mc.getWindow().getHeight();
        Matrix4f invProjMatStable = new Matrix4f().perspective(fovRad, aspect, 0.05f, 1024.0f).invert();
        setUniform("InvProjMatStable", invProjMatStable);

        // InvViewMat: event model-view inverse = pure camera rotation (no bob).
        Matrix4f invViewMat = new Matrix4f(event.getModelViewMatrix()).invert();
        setUniform("InvViewMat", invViewMat);

        // GameTime: fractional day (0-1) used for fog surface animation.
        float gameTime = (float)(mc.level.getGameTime() % 24000L) / 24000.0f;
        setUniform("GameTime",        gameTime);
        setUniform("CamWorldPos",     (float)camPos.x, (float)camPos.y, (float)camPos.z);
        setUniform("FogTopY",         ClientFogSettings.getFogTopY());
        setUniform("FogBottomY",      ClientFogSettings.getFogBottomY());
        setUniform("FogDensity",      ClientFogSettings.getFogDensity());
        setUniform("HeightVariation", ClientFogSettings.getHeightVariation());
        setUniform("HeightScale",     ClientFogSettings.getHeightScale());
        setUniform("FogColorRGB",     color[0], color[1], color[2]);
        setUniform("FogAlpha",        color[3]);

        var depthTarget = mc.getMainRenderTarget();
        setUniform("DepthTexSize", (float) depthTarget.width, (float) depthTarget.height);
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

    private static void setUniform(String name, float a, float b) {
        var u = fogShader.getUniform(name);
        if (u != null) u.set(a, b);
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
