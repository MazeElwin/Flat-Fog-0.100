package com.flatfog;

import com.flatfog.config.FlatFogConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(FlatFog.MOD_ID)
public class FlatFog {
    public static final String MOD_ID = "flatfog";
    public static final Logger LOGGER = LogManager.getLogger();

    public FlatFog(IEventBus modEventBus, ModContainer modContainer) {
        // SERVER config so the server controls the fog layer; synced to clients on join
        modContainer.registerConfig(ModConfig.Type.SERVER, FlatFogConfig.SPEC);
        LOGGER.info("FlatFog loaded.");
    }
}
