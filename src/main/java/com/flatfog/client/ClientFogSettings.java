package com.flatfog.client;

import com.flatfog.network.FogSettingsPayload;

public class ClientFogSettings {

    private static float fogTopY    = 100.0f;
    private static float fogBottomY = -64.0f;

    private static boolean received = true;

    public static void onReceive(FogSettingsPayload payload) {
        fogTopY    = payload.fogTopY();
        fogBottomY = payload.fogBottomY();
        received   = true;
    }

    public static boolean hasData()      { return received; }
    public static float getFogTopY()     { return fogTopY; }
    public static float getFogBottomY()  { return fogBottomY; }
}
