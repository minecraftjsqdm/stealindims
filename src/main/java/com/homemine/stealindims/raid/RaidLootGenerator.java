package com.homemine.stealindims.raid;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static com.homemine.stealindims.StealInDimsMod.MODID;

/**
 * Rolls what a raid container holds (design §4.2, §5.1).
 *
 * <p>Every container kind maps to a fixed tier recipe, so the value of a barrel no longer depends on
 * the difficulty's tier weights — the placement pass decides how many of each kind exist, and this
 * class only fills them. The two former special channels are narrower now: {@code master_key} only
 * comes out of a gold chest on A/S, and {@code nether_star} is out of the container pools entirely.
 *
 * <p>Pots are the one kind a player can break, so their contents cannot live in a block entity: they
 * are derived on the spot from {@code (raid seed, position)}. {@link RandomSource#forkPositional()}
 * gives a random source bound to the coordinates, which makes the drop of a given pot identical
 * every time it is derived, without persisting anything.
 */
public final class RaidLootGenerator {

    /** The five container kinds, in the order the placement pass consumes them (design §4.2). */
    public enum Kind {
        POT,
        BARREL,
        BLUE,
        PURPLE,
        GOLD
    }

    /** Chance that a pot also yields one uncommon drop on top of its common one. */
    private static final float POT_UNCOMMON_CHANCE = 0.10F;
    /** Chance that a gold chest on A or S holds the key to the boss. */
    private static final float GOLD_KEY_CHANCE = 0.05F;

    private static final ResourceLocation MASTER_KEY =
            ResourceLocation.fromNamespaceAndPath(MODID, "master_key");

    private RaidLootGenerator() {
    }

    /**
     * Contents of one container of {@code kind}.
     *
     * <p>Chest kinds degrade to {@link Kind#BARREL} contents when sophisticated storage is missing,
     * which the placement pass handles by reusing this same call with the barrel kind.
     *
     * <p>Everything rolled here carries {@link RaidLootMark}: this method is the one place the map's
     * loot comes from, so marking at the door covers containers, pots and the gold chest's key alike.
     */
    public static List<ItemStack> contents(RaidTheme theme, RaidDifficulty difficulty, Kind kind,
                                           RandomSource random) {
        if (theme.lootPool().isEmpty()) {
            return List.of();
        }
        float multiplier = theme.yieldMultiplier();
        List<ItemStack> contents = new ArrayList<>();

        switch (kind) {
            case POT -> {
                addTier(contents, theme, random, 0, multiplier);
                if (random.nextFloat() < POT_UNCOMMON_CHANCE) {
                    addTier(contents, theme, random, 1, multiplier);
                }
            }
            case BARREL -> {
                addTier(contents, theme, random, 0, multiplier);
                addTier(contents, theme, random, 0, multiplier);
            }
            case BLUE -> {
                addTier(contents, theme, random, 0, multiplier);
                addTier(contents, theme, random, 1, multiplier);
            }
            case PURPLE -> {
                addTier(contents, theme, random, 1, multiplier);
                addTier(contents, theme, random, 1, multiplier);
                addTier(contents, theme, random, 2, multiplier);
            }
            case GOLD -> {
                addTier(contents, theme, random, 2, multiplier);
                addTier(contents, theme, random, 2, multiplier);
                addTier(contents, theme, random, 3, multiplier);
                if (isKeyHolder(difficulty) && random.nextFloat() < GOLD_KEY_CHANCE) {
                    Item key = BuiltInRegistries.ITEM.get(MASTER_KEY);
                    if (key != Items.AIR) {
                        contents.add(new ItemStack(key));
                    }
                }
            }
        }
        for (ItemStack stack : contents) {
            RaidLootMark.mark(stack);
        }
        return contents;
    }

    /**
     * Drops of a pot at {@code pos}, derived from the raid's seed.
     *
     * <p>Nothing is stored: breaking the same pot of the same raid twice always yields the same
     * stacks, which is what keeps a pot breakable without a block entity.
     */
    public static List<ItemStack> forPot(RaidTheme theme, RaidDifficulty difficulty, long seed, BlockPos pos) {
        return contents(theme, difficulty, Kind.POT,
                RandomSource.create(seed).forkPositional().at(pos));
    }

    /** Convenience overload for the block-break hook, which only has the player. */
    public static List<ItemStack> forPot(ServerPlayer player, BlockPos pos) {
        RaidSession session = RaidSessionManager.sessionOf(player);
        if (session == null) {
            return List.of();
        }
        return forPot(session.theme(), session.difficulty(), session.seed(), pos);
    }

    private static boolean isKeyHolder(RaidDifficulty difficulty) {
        return difficulty == RaidDifficulty.A || difficulty == RaidDifficulty.S;
    }

    /** Rolls one drop out of {@code tier} and appends it, unless the tier rolled empty. */
    private static void addTier(List<ItemStack> contents, RaidTheme theme, RandomSource random, int tier,
                                float multiplier) {
        ItemStack stack = roll(random, theme.lootPool().tier(tier), multiplier);
        if (!stack.isEmpty()) {
            contents.add(stack);
        }
    }

    @Nullable
    public static ItemStack roll(RandomSource random, List<RaidTheme.LootDrop> drops, float countMultiplier) {
        List<RaidTheme.LootDrop> candidates = new ArrayList<>(drops.size());
        int total = 0;
        for (RaidTheme.LootDrop drop : drops) {
            if (!isBanned(drop.item())) {
                candidates.add(drop);
                total += Math.max(1, drop.weight());
            }
        }
        if (candidates.isEmpty() || total <= 0) {
            return ItemStack.EMPTY;
        }

        int pick = random.nextInt(total);
        for (RaidTheme.LootDrop drop : candidates) {
            pick -= Math.max(1, drop.weight());
            if (pick < 0) {
                Item item = BuiltInRegistries.ITEM.get(drop.item());
                if (item == Items.AIR) {
                    return ItemStack.EMPTY;
                }
                int size = Math.min(drop.rollCount(random, countMultiplier), item.getDefaultMaxStackSize());
                return new ItemStack(item, Math.max(1, size));
            }
        }
        return ItemStack.EMPTY;
    }

    public static boolean isBanned(ResourceLocation itemId) {
        Item item = BuiltInRegistries.ITEM.get(itemId);
        return item == Items.AIR || new ItemStack(item).is(RaidTags.BANNED);
    }
}
