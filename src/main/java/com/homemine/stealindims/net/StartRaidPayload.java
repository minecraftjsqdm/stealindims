package com.homemine.stealindims.net;

import com.homemine.stealindims.StealInDimsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: the player pressed "depart" on the raid select screen.
 *
 * <p>The host dimension is deliberately not part of the payload: the server takes it from where the
 * player is standing, so a modified client cannot ask for a raid it has no device for.
 */
public record StartRaidPayload(int difficultyOrdinal, String mapId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StartRaidPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(StealInDimsMod.MODID, "start_raid"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StartRaidPayload> STREAM_CODEC =
            StreamCodec.of(StartRaidPayload::encode, StartRaidPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, StartRaidPayload payload) {
        buffer.writeVarInt(payload.difficultyOrdinal);
        buffer.writeUtf(payload.mapId);
    }

    private static StartRaidPayload decode(RegistryFriendlyByteBuf buffer) {
        return new StartRaidPayload(buffer.readVarInt(), buffer.readUtf());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
