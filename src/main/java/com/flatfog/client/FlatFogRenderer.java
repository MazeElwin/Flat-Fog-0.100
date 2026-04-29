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

        float fovRad = (float) Math.toRadians(mc.options.fov().get());
        float aspect = (float) mc.getWindow().getWidth() / mc.getWindow().getHeight();
        Matrix4f invProjMat = new Matrix4f().perspective(fovRad, aspect, 0.05f, 1024.0f).invert();
        setUniform("InvProjMat", invProjMat);

        Matrix4f invViewMat = new Matrix4f(event.getModelViewMatrix()).invert();

        // Strip view-bob out of InvViewMat by pre-multiplying the exact BobMat
        // that bobView() applied. BobMat * event_InvViewMat = bob-free InvViewMat.
        if (mc.options.bobView().get()) {
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            float f  = mc.player.walkDist - mc.player.walkDistO;
            float g  = -(mc.player.walkDist + f * partialTick);
            float h  = Mth.lerp(partialTick, mc.player.oBob, mc.player.bob);

            float bobX  = Mth.sin(g * (float)Math.PI) * h * 0.5f;
            float bobY  = -(float)Math.abs(Math.cos(g * Math.PI) * h);
            float bobRZ = Mth.sin(g * (float)Math.PI) * h * 3.0f;       // degrees
            float bobRX = (float)Math.abs(Math.cos(g * Math.PI) * h) * 5.0f; // degrees

            Matrix4f bobMat = new Matrix4f()
                .translate(bobX, bobY, 0.0f)
                .rotateZ((float)Math.toRadians(bobRZ))
                .rotateX((float)Math.toRadians(bobRX));

            invViewMat = new Matrix4f(bobMat).mul(invViewMat);

            // Also counteract the bob translation's effect on the fog camera position.
            // The bob shifts the scene by (bobX, bobY, 0) in view space; compensate
            // by moving camPos by the equivalent world-space offset (using the rotation
            // columns of the now-bob-free InvViewMat).
            float wx = invViewMat.m00() * (-bobX) + invViewMat.m10() * (-bobY);
            float wy = invViewMat.m01() * (-bobX) + invViewMat.m11() * (-bobY);
            float wz = invViewMat.m02() * (-bobX) + invViewMat.m12() * (-bobY);
            camPos = camPos.add(wx, wy, wz);
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
