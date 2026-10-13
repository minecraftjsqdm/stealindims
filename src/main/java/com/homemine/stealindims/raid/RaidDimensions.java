package com.homemine.stealindims.raid;

import com.homemine.stealindims.StealInDimsMod;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Maps a host dimension (where the Advanced Warp Device stands) to the raid dimension it opens.
 *
 * <p>A host dimension only has an entry here once it also has a matching
 * {@code data/stealindims/raid_theme/*.json} file; {@link RaidThemeRegistry} is the authority on
 * whether a raid is actually open, this class only knows the naming convention.
 */
public final class RaidDimensions {

    /** 主世界 -> 主世界副本 */
    public static final ResourceKey<Level> OVERWORLD_RAID = raid("raid_overworld");
    /** 下界 -> 下界副本 */
    public static final ResourceKey<Level> NETHER_RAID = raid("raid_nether");
    /** 末地 -> 末地副本 */
    public static final ResourceKey<Level> END_RAID = raid("raid_end");
    /** 暮色森林 -> 暮色副本（软依赖 twilightforest） */
    public static final ResourceKey<Level> TWILIGHT_RAID = raid("raid_twilight");
    /** 天境 -> 天境副本（软依赖 aether） */
    public static final ResourceKey<Level> AETHER_RAID = raid("raid_aether");

    /** 暮色森林维度（twilightforest 软依赖，未安装时不会出现在任何已加载维度里） */
    public static final ResourceKey<Level> TWILIGHT_FOREST = host("twilightforest", "twilight_forest");
    /** 天境维度（aether 软依赖，未安装时不会出现在任何已加载维度里） */
    public static final ResourceKey<Level> THE_AETHER = host("aether", "the_aether");

    private static final Map<ResourceKey<Level>, ResourceKey<Level>> HOST_TO_RAID = Map.of(
            Level.OVERWORLD, OVERWORLD_RAID,
            Level.NETHER, NETHER_RAID,
            Level.END, END_RAID,
            TWILIGHT_FOREST, TWILIGHT_RAID,
            THE_AETHER, AETHER_RAID);

    private RaidDimensions() {
    }

    /** @return the raid dimension opened by a device standing in {@code host}, or {@code null} if unsupported. */
    @Nullable
    public static ResourceKey<Level> raidDimension(ResourceKey<Level> host) {
        return HOST_TO_RAID.get(host);
    }

    /** @return the host dimension a raid dimension belongs to, or {@code null} if it is not a raid dimension. */
    @Nullable
    public static ResourceKey<Level> hostDimension(ResourceKey<Level> raid) {
        for (Map.Entry<ResourceKey<Level>, ResourceKey<Level>> entry : HOST_TO_RAID.entrySet()) {
            if (entry.getValue().equals(raid)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static boolean isRaidDimension(ResourceKey<Level> dimension) {
        return HOST_TO_RAID.containsValue(dimension);
    }

    /** All raid dimensions, in a stable order (matching the host dimension order used for display). */
    public static List<ResourceKey<Level>> all() {
        return List.of(OVERWORLD_RAID, NETHER_RAID, END_RAID, TWILIGHT_RAID, AETHER_RAID);
    }

    private static ResourceKey<Level> raid(String path) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(StealInDimsMod.MODID, path));
    }

    private static ResourceKey<Level> host(String namespace, String path) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }
}
