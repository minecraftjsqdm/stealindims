package com.homemine.stealindims.net;

import com.homemine.stealindims.StealInDimsMod;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: the HUD readout, sent once a second while a raid is running (design §22).
 *
 * <p>Carries the lobby state as well, because the same readout has to cover the three phases: while
 * waiting it shows how many members have readied up, during the countdown it shows the seconds left,
 * and once the run starts it becomes the clock.
 *
 * @param remainingSeconds seconds until the run times out
 * @param submittedValue   appraised value already handed to the transit crate
 * @param requiredValue    the threshold the submitted value has to reach
 * @param teleportTaxCount how many teleports the player has paid for so far
 * @param phase            {@code RaidSession.Phase} ordinal: 0 waiting, 1 countdown, 2 started
 * @param countdownSeconds seconds left on the start countdown, 0 outside the countdown
 * @param memberCount      how many players are in the lobby or run
 * @param readyMembers     who has readied up, used for the green tick over their heads
 * @param inRaid           false once the session ended, which tells the client to drop the HUD
 */
public record RaidStatusPayload(int remainingSeconds, long submittedValue, long requiredValue,
                                int teleportTaxCount, int phase, int countdownSeconds, int memberCount,
                                List<UUID> readyMembers, boolean inRaid) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RaidStatusPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(StealInDimsMod.MODID, "raid_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RaidStatusPayload> STREAM_CODEC =
            StreamCodec.of(RaidStatusPayload::encode, RaidStatusPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, RaidStatusPayload payload) {
        buffer.writeVarInt(payload.remainingSeconds);
        buffer.writeLong(payload.submittedValue);
        buffer.writeLong(payload.requiredValue);
        buffer.writeVarInt(payload.teleportTaxCount);
        buffer.writeVarInt(payload.phase);
        buffer.writeVarInt(payload.countdownSeconds);
        buffer.writeVarInt(payload.memberCount);
        buffer.writeVarInt(payload.readyMembers.size());
        for (UUID member : payload.readyMembers) {
            buffer.writeUUID(member);
        }
        buffer.writeBoolean(payload.inRaid);
    }

    private static RaidStatusPayload decode(RegistryFriendlyByteBuf buffer) {
        int remainingSeconds = buffer.readVarInt();
        long submittedValue = buffer.readLong();
        long requiredValue = buffer.readLong();
        int teleportTaxCount = buffer.readVarInt();
        int phase = buffer.readVarInt();
        int countdownSeconds = buffer.readVarInt();
        int memberCount = buffer.readVarInt();
        int readyCount = buffer.readVarInt();
        List<UUID> readyMembers = new ArrayList<>(readyCount);
        for (int i = 0; i < readyCount; i++) {
            readyMembers.add(buffer.readUUID());
        }
        return new RaidStatusPayload(remainingSeconds, submittedValue, requiredValue, teleportTaxCount,
                phase, countdownSeconds, memberCount, List.copyOf(readyMembers), buffer.readBoolean());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
