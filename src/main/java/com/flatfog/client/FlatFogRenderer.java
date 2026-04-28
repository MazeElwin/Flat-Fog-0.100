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
 * world-space ray directions from InvProjMat (NDC → view space) and explicit camera
 * basis vectors (CamForward/CamUp/CamRight) computed from player yaw/pitch angles.
 *
 * Basis vectors are computed from mc.player.getYRot()/getXRot() rather than from
 * the Camera object, which includes MC's view-bob transform. Bob oscillates the
 * camera basis vectors every walking frame; using raw rotation angles bypasses it.
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

        // InvProjMat: NDC → view space for ray direction and depth reconstruction.
        Matrix4f projMat    = new Matrix4f(event.getProjectionMatrix());
        Matrix4f invProjMat = projMat.invert(new Matrix4f());
        setUniform("InvProjMat", invProjMat);

        // Camera basis vectors from stable player angles (no bobbing).
        // mc.player.get[XY]Rot() are the raw rotation values; the Camera object
        // adds a bobbing offset on top of these, which causes per-frame shimmer.
        float yaw      = (float)(mc.player.getYRot()  * Math.PI / 180.0);
        float pitch    = (float)(mc.player.getXRot()  * Math.PI / 180.0);
        float sinYaw   = (float) Math.sin(yaw);
        float cosYaw   = (float) Math.cos(yaw);
        float sinPitch = (float) Math.sin(pitch);
        float cosPitch = (float) Math.cos(pitch);
        setUniform("CamForward", -sinYaw * cosPitch, -sinPitch,         cosYaw  * cosPitch);
        setUniform("CamRight",   -cosYaw,            0f,                -sinYaw            );
        setUniform("CamUp",      -sinPitch * sinYaw,  cosPitch,          sinPitch * cosYaw );

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
