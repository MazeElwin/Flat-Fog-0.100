package com.flatfog.network;

import com.flatfog.FlatFog;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record FogSettingsPayload(
    float fogTopY,
    float fogBottomY
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FogSettingsPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FlatFog.MOD_ID, "fog_settings"));

    public static final StreamCodec<FriendlyByteBuf, FogSettingsPayload> CODEC =
        StreamCodec.of(FogSettingsPayload::encode, FogSettingsPayload::decode);

    private static void encode(FriendlyByteBuf buf, FogSettingsPayload p) {
        buf.writeFloat(p.fogTopY);
        buf.writeFloat(p.fogBottomY);
    }

    private static FogSettingsPayload decode(FriendlyByteBuf buf) {
        return new FogSettingsPayload(buf.readFloat(), buf.readFloat());
    }

    @Override
    public CustomPacketPayload.Type<FogSettingsPayload> type() {
        return TYPE;
    }
}
