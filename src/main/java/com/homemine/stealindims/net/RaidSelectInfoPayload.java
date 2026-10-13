package com.homemine.stealindims.net;

import com.homemine.stealindims.StealInDimsMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: everything the raid select screen needs.
 *
 * <p>The screen is payload driven rather than menu driven (a deviation from design §23, where it was
 * planned as a {@code MenuProvider}) because the select screen has no slots to sync: it only paints a
 * dimension header, the map list and the difficulty buttons.
 *
 * @param hostDimension   the dimension the device stands in, used as the screen's title
 * @param highestUnlocked ordinal of the highest unlocked {@code RaidDifficulty}, lower ones are locked
 * @param maps            the maps of the theme, in file order
 * @param lobby           when the host dimension's raid slot already holds a waiting lobby, that
 *                        lobby's pinned difficulty and map; {@code null} when the player is opening
 *                        a fresh run and may pick for themselves
 */
public record RaidSelectInfoPayload(ResourceLocation hostDimension, int highestUnlocked,
                                    List<MapEntry> maps, LobbyInfo lobby)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RaidSelectInfoPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(StealInDimsMod.MODID, "raid_select_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RaidSelectInfoPayload> STREAM_CODEC =
            StreamCodec.of(RaidSelectInfoPayload::encode, RaidSelectInfoPayload::decode);

    /** One map row of the theme. */
    public record MapEntry(String id, String name, String layout, String form, String size, boolean locked) {

        /** Whether this row can be departed to at all; a locked map is shown but never opened. */
        public boolean selectable() {
            return !this.locked;
        }
    }

    /** The run a joiner would be folded into: its choices are fixed, the joiner only tags along. */
    public record LobbyInfo(int difficultyOrdinal, String mapId, int memberCount) {
    }

    private static void encode(RegistryFriendlyByteBuf buffer, RaidSelectInfoPayload payload) {
        buffer.writeResourceLocation(payload.hostDimension);
        buffer.writeVarInt(payload.highestUnlocked);
        buffer.writeVarInt(payload.maps.size());
        for (MapEntry map : payload.maps) {
            buffer.writeUtf(map.id());
            buffer.writeUtf(map.name());
            buffer.writeUtf(map.layout());
            buffer.writeUtf(map.form());
            buffer.writeUtf(map.size());
            buffer.writeBoolean(map.locked());
        }
        buffer.writeBoolean(payload.lobby != null);
        if (payload.lobby != null) {
            buffer.writeVarInt(payload.lobby.difficultyOrdinal());
            buffer.writeUtf(payload.lobby.mapId());
            buffer.writeVarInt(payload.lobby.memberCount());
        }
    }

    private static RaidSelectInfoPayload decode(RegistryFriendlyByteBuf buffer) {
        ResourceLocation host = buffer.readResourceLocation();
        int highestUnlocked = buffer.readVarInt();
        int size = buffer.readVarInt();
        List<MapEntry> maps = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            maps.add(new MapEntry(buffer.readUtf(), buffer.readUtf(), buffer.readUtf(), buffer.readUtf(),
                    buffer.readUtf(), buffer.readBoolean()));
        }
        LobbyInfo lobby = buffer.readBoolean()
                ? new LobbyInfo(buffer.readVarInt(), buffer.readUtf(), buffer.readVarInt())
                : null;
        return new RaidSelectInfoPayload(host, highestUnlocked, List.copyOf(maps), lobby);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
