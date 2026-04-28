package com.flatfog.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class FlatFogConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue FOG_TOP_Y;
    public static final ModConfigSpec.DoubleValue FOG_BOTTOM_Y;

    static {
        BUILDER.push("fog");

        FOG_TOP_Y = BUILDER
            .comment("World Y level of the flat fog top.")
            .defineInRange("fog_top_y", 100.0, -64.0, 320.0);

        FOG_BOTTOM_Y = BUILDER
            .comment("World Y level of the fog floor.")
            .defineInRange("fog_bottom_y", -64.0, -320.0, 320.0);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
