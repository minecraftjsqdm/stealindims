package com.homemine.stealindims.net;

import com.homemine.stealindims.StealInDimsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: the player pressed "transfer all" on the transit crate screen.
 *
 * <p>Carries nothing: the server works from the sender's own inventory and session, so a modified
 * client cannot name a different crate.
 */
public record TransferCratePayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<TransferCratePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(StealInDimsMod.MODID, "transfer_crate"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TransferCratePayload> STREAM_CODEC =
            StreamCodec.of(TransferCratePayload::encode, TransferCratePayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, TransferCratePayload payload) {
    }

    private static TransferCratePayload decode(RegistryFriendlyByteBuf buffer) {
        return new TransferCratePayload();
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
