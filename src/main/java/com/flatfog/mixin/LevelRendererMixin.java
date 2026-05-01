package com.flatfog.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Redirect(
            method = "renderSky",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V",
                    ordinal = 2
            )
    )
    private void flatfog$skipVoidDarkSkyDisc(VertexBuffer buffer, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, ShaderInstance shader) {
        // Vanilla draws this black disc below the horizon to hide the void. Flat Fog provides its own opaque fog floor.
    }
}
