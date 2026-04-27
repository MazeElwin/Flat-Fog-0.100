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
 * Draws a single NDC quad that covers every pixel. The fragment shader reconstructs
 * world-space ray directions using:
 *   - InvProjMat  (set from event.getProjectionMatrix().invert()) to go NDC → view space
 *   - ModelViewMat (auto-set by MC — the camera rotation matrix) used as
 *     transpose(mat3(ModelViewMat)) to go view space → world space
 *
 * This avoids PoseStack entirely, which can accumulate stale transforms by AFTER_WEATHER.
 *
 * The scene depth buffer is read via texelFetch to clamp the fog ray at geometry,
 * preventing fog from bleeding through solid blocks.
 */
@EventBusSubscriber(modid = FlatFog.MOD_ID, value = Dist.CLIENT)
public class FlatFogRenderer {

    public static ShaderInstance fogShader = null;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) return;
        if (fogShader == null || !ClientFogSettings.hasData()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        float[] color = ClientFogSettings.getFogColor();

        // ProjMat + InvProjMat: depth linearisation and view-space reconstruction.
        // ProjMat is declared in the JSON but MC's auto-uniform system does not
        // reliably update it for custom shaders, so we set both explicitly.
        Matrix4f projMat    = new Matrix4f(event.getProjectionMatrix());
        Matrix4f invProjMat = projMat.invert(new Matrix4f());

        setUniform("ProjMat",         projMat);
        setUniform("InvProjMat",      invProjMat);
        setUniform("CamWorldPos",     (float)camPos.x, (float)camPos.y, (float)camPos.z);
        setUniform("FogTopY",         ClientFogSettings.getFogTopY());
        setUniform("FogBottomY",      ClientFogSettings.getFogBottomY());
        setUniform("FogDensity",      ClientFogSettings.getFogDensity());
        setUniform("HeightVariation", ClientFogSettings.getHeightVariation());
        setUniform("HeightScale",     ClientFogSettings.getHeightScale());
        setUniform("FogColorRGB",     color[0], color[1], color[2]);
        setUniform("FogAlpha",        color[3]);

        // Bind scene depth texture so the shader can stop fog at geometry.
        fogShader.setSampler("DepthSampler", mc.getMainRenderTarget().getDepthTextureId());

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);

        RenderSystem.setShader(() -> fogShader);

        // Full-screen quad in clip/NDC space.
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
