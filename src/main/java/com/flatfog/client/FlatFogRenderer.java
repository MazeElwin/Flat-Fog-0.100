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
 * world-space ray directions from InvProjMat (NDC → view space) and InvViewMat
 * (view space → world space). InvViewMat is built directly from the camera's rotation
 * quaternion — the same rotation MC uses to render the scene — so the fog ray exactly
 * matches the rendered perspective, including any view-bob contribution. The depth clamp
 * uses Euclidean view-space distance, which is rotation-invariant.
 */
@EventBusSubscriber(modid = FlatFog.MOD_ID, value = Dist.CLIENT)
public class FlatFogRenderer {

    public static ShaderInstance fogShader = null;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (fogShader == null || !ClientFogSettings.hasData()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        float[] color = ClientFogSettings.getFogColor();

        // InvProjMat: NDC → view space.
        Matrix4f projMat    = new Matrix4f(event.getProjectionMatrix());
        Matrix4f invProjMat = projMat.invert(new Matrix4f());
        setUniform("InvProjMat", invProjMat);

        // InvViewMat: view space → world space using the camera's actual rotation quaternion.
        // MC camera-local convention: +Z = forward. OpenGL view-space convention: -Z = forward.
        // The scale(1,1,-1) flips Z so the two conventions agree before the quaternion is applied.
        // Result: InvViewMat * (0,0,-1) = look direction, InvViewMat * (1,0,0) = right, etc.
        org.joml.Quaternionf camRot = new org.joml.Quaternionf(event.getCamera().rotation());
        Matrix4f invViewMat = new Matrix4f().rotate(camRot).scale(1f, 1f, -1f);
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
