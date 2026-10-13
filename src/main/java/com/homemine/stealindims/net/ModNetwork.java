package com.homemine.stealindims.net;

import com.homemine.stealindims.raid.RaidSessionManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the four raid payloads.
 *
 * <p>The client handlers live in {@code client.RaidClientPayloadHandler} and are only named from
 * inside the deferred work of a client-bound handler, so the class - and with it every client-only
 * type it touches - is never loaded on a dedicated server.
 */
public final class ModNetwork {

    private static final String VERSION = "1";

    private ModNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);

        registrar.playToServer(StartRaidPayload.TYPE, StartRaidPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    Player player = context.player();
                    if (player instanceof ServerPlayer serverPlayer) {
                        RaidSessionManager.startRaid(serverPlayer, payload.difficultyOrdinal(), payload.mapId());
                    }
                }));

        registrar.playToServer(TransferCratePayload.TYPE, TransferCratePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    Player player = context.player();
                    if (player instanceof ServerPlayer serverPlayer) {
                        RaidSessionManager.transferAllMarked(serverPlayer);
                    }
                }));

        registrar.playToClient(RaidSelectInfoPayload.TYPE, RaidSelectInfoPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.homemine.stealindims.client.RaidClientPayloadHandler.onSelectInfo(payload)));

        registrar.playToClient(RaidStatusPayload.TYPE, RaidStatusPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.homemine.stealindims.client.RaidClientPayloadHandler.onStatus(payload)));

        registrar.playToClient(RaidResultPayload.TYPE, RaidResultPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.homemine.stealindims.client.RaidClientPayloadHandler.onResult(payload)));
    }
}
