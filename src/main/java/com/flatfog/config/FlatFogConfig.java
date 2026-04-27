package com.flatfog.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class FlatFogConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue FOG_TOP_Y;
    public static final ModConfigSpec.DoubleValue FOG_BOTTOM_Y;
    public static final ModConfigSpec.DoubleValue FOG_DENSITY;
    public static final ModConfigSpec.DoubleValue HEIGHT_VARIATION;
    public static final ModConfigSpec.DoubleValue HEIGHT_SCALE;

    public static final ModConfigSpec.DoubleValue FOG_COLOR_R;
    public static final ModConfigSpec.DoubleValue FOG_COLOR_G;
    public static final ModConfigSpec.DoubleValue FOG_COLOR_B;
    public static final ModConfigSpec.DoubleValue FOG_COLOR_A;

    static {
        BUILDER.push("fog");

        FOG_TOP_Y = BUILDER
            .comment("World Y level of the flat fog top. Fog fills all air below this.")
            .defineInRange("fog_top_y", 100.0, -64.0, 320.0);

        FOG_BOTTOM_Y = BUILDER
            .comment("World Y level of the fog floor.")
            .defineInRange("fog_bottom_y", -64.0, -320.0, 320.0);

        FOG_DENSITY = BUILDER
            .comment("Fog extinction coefficient multiplier. Higher = thicker. Range 0.0-5.0.")
            .defineInRange("fog_density", 1.5, 0.0, 5.0);

        HEIGHT_VARIATION = BUILDER
            .comment("Amplitude in blocks of the fog top surface variation. 0 = perfectly flat top.")
            .defineInRange("height_variation", 10.0, 0.0, 64.0);

        HEIGHT_SCALE = BUILDER
            .comment("Spatial frequency of the height field noise. Smaller = larger fog features.")
            .defineInRange("height_scale", 0.003, 0.0001, 0.1);

        BUILDER.pop();
        BUILDER.push("fog_color");

        FOG_COLOR_R = BUILDER.defineInRange("r", 0.82, 0.0, 1.0);
        FOG_COLOR_G = BUILDER.defineInRange("g", 0.88, 0.0, 1.0);
        FOG_COLOR_B = BUILDER.defineInRange("b", 0.96, 0.0, 1.0);
        FOG_COLOR_A = BUILDER.defineInRange("a", 0.92, 0.0, 1.0);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    public static float[] getFogColor() {
        return new float[]{
            FOG_COLOR_R.get().floatValue(),
            FOG_COLOR_G.get().floatValue(),
            FOG_COLOR_B.get().floatValue(),
            FOG_COLOR_A.get().floatValue()
        };
    }
}
