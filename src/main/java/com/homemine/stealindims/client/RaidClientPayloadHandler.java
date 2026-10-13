package com.homemine.stealindims.client;

import com.homemine.stealindims.net.RaidResultPayload;
import com.homemine.stealindims.net.RaidSelectInfoPayload;
import com.homemine.stealindims.net.RaidStatusPayload;
import net.minecraft.client.Minecraft;

/**
 * Client side receivers for the three server bound payloads.
 *
 * <p>This class is only ever named from inside the deferred work of a network handler, so it - and
 * every client only type it touches - stays out of a dedicated server's class loading.
 */
public final class RaidClientPayloadHandler {

    private RaidClientPayloadHandler() {
    }

    /** Opens the select screen; the server decides the contents, the client only paints them. */
    public static void onSelectInfo(RaidSelectInfoPayload payload) {
        Minecraft.getInstance().setScreen(new RaidSelectScreen(payload));
    }

    public static void onStatus(RaidStatusPayload payload) {
        RaidClientState.applyStatus(payload);
    }

    /** Ends the run on the client: drop the HUD and show the settlement screen (design §21). */
    public static void onResult(RaidResultPayload payload) {
        RaidClientState.clear();
        Minecraft.getInstance().setScreen(new RaidResultScreen(payload));
    }
}
