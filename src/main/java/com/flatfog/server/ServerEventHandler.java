package com.flatfog.server;

import com.flatfog.FlatFog;
import com.flatfog.config.FlatFogConfig;
import com.flatfog.network.FogSettingsPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = FlatFog.MOD_ID)
public class ServerEventHandler {

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PacketDistributor.sendToPlayer(player, buildPayload());
    }

    private static FogSettingsPayload buildPayload() {
        float[] color = FlatFogConfig.getFogColor();
        return new FogSettingsPayload(
            FlatFogConfig.FOG_TOP_Y.get().floatValue(),
            FlatFogConfig.FOG_BOTTOM_Y.get().floatValue(),
            FlatFogConfig.FOG_DENSITY.get().floatValue(),
            FlatFogConfig.HEIGHT_VARIATION.get().floatValue(),
            FlatFogConfig.HEIGHT_SCALE.get().floatValue(),
            color[0], color[1], color[2], color[3]
        );
    }
}
