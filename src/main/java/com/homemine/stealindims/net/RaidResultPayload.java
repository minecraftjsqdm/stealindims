package com.homemine.stealindims.net;

import com.homemine.stealindims.StealInDimsMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Server -> client: the settlement screen contents (design §23, modelled on 第五人格's 加页手记).
 *
 * <p>Five lists of stacks would blow past {@code StreamCodec.composite}'s arity, so the codec is
 * written by hand on top of the vanilla {@link ItemStack} list codecs.
 *
 * @param hostDimension    dimension the player is sent back to
 * @param mapName          display name of the map that was run
 * @param difficultyOrdinal ordinal of the difficulty that was run
 * @param elapsedSeconds   how long the run lasted
 * @param remainingSeconds how much time was left when it ended
 * @param submittedValue   value handed to the transit crate
 * @param requiredValue    value the run had to reach
 * @param submitted        what was handed to the transit crate
 * @param broughtOut       what actually left the raid, i.e. the highest value 27 stacks
 * @param equipmentLost    equipment taken by the difficulty's keep-ratio roll
 * @param teleportTaxCount teleports paid for during the run
 * @param teleportTaxValue value consumed by those teleports
 * @param success          whether the threshold was reached
 * @param unlockedOrdinal  difficulty unlocked by this run, or {@code -1} for none
 */
public record RaidResultPayload(ResourceLocation hostDimension,
                                String mapName,
                                int difficultyOrdinal,
                                int elapsedSeconds,
                                int remainingSeconds,
                                long submittedValue,
                                long requiredValue,
                                List<ItemStack> submitted,
                                List<ItemStack> broughtOut,
                                List<ItemStack> equipmentLost,
                                int teleportTaxCount,
                                long teleportTaxValue,
                                boolean success,
                                int unlockedOrdinal) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RaidResultPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(StealInDimsMod.MODID, "raid_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RaidResultPayload> STREAM_CODEC =
            StreamCodec.of(RaidResultPayload::encode, RaidResultPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, RaidResultPayload payload) {
        buffer.writeResourceLocation(payload.hostDimension);
        buffer.writeUtf(payload.mapName);
        buffer.writeVarInt(payload.difficultyOrdinal);
        buffer.writeVarInt(payload.elapsedSeconds);
        buffer.writeVarInt(payload.remainingSeconds);
        buffer.writeLong(payload.submittedValue);
        buffer.writeLong(payload.requiredValue);
        ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buffer, payload.submitted);
        ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buffer, payload.broughtOut);
        ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buffer, payload.equipmentLost);
        buffer.writeVarInt(payload.teleportTaxCount);
        buffer.writeLong(payload.teleportTaxValue);
        buffer.writeBoolean(payload.success);
        buffer.writeVarInt(payload.unlockedOrdinal);
    }

    private static RaidResultPayload decode(RegistryFriendlyByteBuf buffer) {
        ResourceLocation host = buffer.readResourceLocation();
        String mapName = buffer.readUtf();
        int difficultyOrdinal = buffer.readVarInt();
        int elapsedSeconds = buffer.readVarInt();
        int remainingSeconds = buffer.readVarInt();
        long submittedValue = buffer.readLong();
        long requiredValue = buffer.readLong();
        List<ItemStack> submitted = new ArrayList<>(ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buffer));
        List<ItemStack> broughtOut = new ArrayList<>(ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buffer));
        List<ItemStack> equipmentLost = new ArrayList<>(ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buffer));
        int teleportTaxCount = buffer.readVarInt();
        long teleportTaxValue = buffer.readLong();
        boolean success = buffer.readBoolean();
        int unlockedOrdinal = buffer.readVarInt();
        return new RaidResultPayload(host, mapName, difficultyOrdinal, elapsedSeconds, remainingSeconds,
                submittedValue, requiredValue, submitted, broughtOut, equipmentLost,
                teleportTaxCount, teleportTaxValue, success, unlockedOrdinal);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
