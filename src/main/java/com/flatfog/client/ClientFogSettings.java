package com.flatfog.client;

import com.flatfog.network.FogSettingsPayload;

/**
 * Client-side mirror of the server's fog layer configuration.
 * Populated by FogSettingsPayload on player join.
 * All render code reads from here — never directly from FlatFogConfig on the client.
 */
public class ClientFogSettings {

    private static float fogTopY      = 100.0f;
    private static float fogBottomY   = -64.0f;
    private static float fogDensity   = 1.5f;
    private static float heightVariation = 10.0f;
    private static float heightScale  = 0.003f;
    private static float colorR = 0.82f;
    private static float colorG = 0.88f;
    private static float colorB = 0.96f;
    private static float colorA = 0.92f;

    private static boolean received = true;  // defaults are sane; server sync overrides on join

    public static void onReceive(FogSettingsPayload payload) {
        fogTopY         = payload.fogTopY();
        fogBottomY      = payload.fogBottomY();
        fogDensity      = payload.fogDensity();
        heightVariation = payload.heightVariation();
        heightScale     = payload.heightScale();
        colorR          = payload.colorR();
        colorG          = payload.colorG();
        colorB          = payload.colorB();
        colorA          = payload.colorA();
        received        = true;
    }

    public static boolean hasData()          { return received; }
    public static float getFogTopY()         { return fogTopY; }
    public static float getFogBottomY()      { return fogBottomY; }
    public static float getFogDensity()      { return fogDensity; }
    public static float getHeightVariation() { return heightVariation; }
    public static float getHeightScale()     { return heightScale; }
    public static float[] getFogColor()      { return new float[]{colorR, colorG, colorB, colorA}; }
}
