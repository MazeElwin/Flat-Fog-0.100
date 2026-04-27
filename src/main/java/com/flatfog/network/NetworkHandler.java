package com.flatfog.network;

import com.flatfog.FlatFog;
import com.flatfog.client.ClientFogSettings;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = FlatFog.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class NetworkHandler {

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(
            FogSettingsPayload.TYPE,
            FogSettingsPayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> ClientFogSettings.onReceive(payload))
        );
    }
}
