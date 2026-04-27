package com.flatfog.network;

import com.flatfog.FlatFog;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client. Carries the authoritative fog layer settings from server config.
 * Sent on player join and whenever an op reloads the config.
 */
public record FogSettingsPayload(
    float fogTopY,
    float fogBottomY,
    float fogDensity,
    float heightVariation,
    float heightScale,
    float colorR,
    float colorG,
    float colorB,
    float colorA
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FogSettingsPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FlatFog.MOD_ID, "fog_settings"));

    public static final StreamCodec<FriendlyByteBuf, FogSettingsPayload> CODEC =
        StreamCodec.of(FogSettingsPayload::encode, FogSettingsPayload::decode);

    private static void encode(FriendlyByteBuf buf, FogSettingsPayload p) {
        buf.writeFloat(p.fogTopY);
        buf.writeFloat(p.fogBottomY);
        buf.writeFloat(p.fogDensity);
        buf.writeFloat(p.heightVariation);
        buf.writeFloat(p.heightScale);
        buf.writeFloat(p.colorR);
        buf.writeFloat(p.colorG);
        buf.writeFloat(p.colorB);
        buf.writeFloat(p.colorA);
    }

    private static FogSettingsPayload decode(FriendlyByteBuf buf) {
        return new FogSettingsPayload(
            buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(), buf.readFloat(),
            buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()
        );
    }

    @Override
    public CustomPacketPayload.Type<FogSettingsPayload> type() {
        return TYPE;
    }
}
