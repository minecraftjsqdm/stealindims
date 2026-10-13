package com.homemine.stealindims;

import com.homemine.stealindims.entity.ModEntities;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(StealInDimsMod.MODID);

    /** 高级折跃器 */
    public static final DeferredItem<BlockItem> RAID_PORTAL =
            ITEMS.registerSimpleBlockItem(ModBlocks.RAID_PORTAL);

    /** 转运箱 */
    public static final DeferredItem<BlockItem> TRANSFER_CRATE =
            ITEMS.registerSimpleBlockItem(ModBlocks.TRANSFER_CRATE);

    /** 准备台 */
    public static final DeferredItem<BlockItem> RAID_READY_PAD =
            ITEMS.registerSimpleBlockItem(ModBlocks.RAID_READY_PAD);

    /** 撤离传送门 */
    public static final DeferredItem<BlockItem> RAID_EXIT_PORTAL =
            ITEMS.registerSimpleBlockItem(ModBlocks.RAID_EXIT_PORTAL);

    /** 刌民像曲柄 */
    public static final DeferredItem<BlockItem> ILLAGER_CRANK =
            ITEMS.registerSimpleBlockItem(ModBlocks.ILLAGER_CRANK);

    /** Boss 传送门 */
    public static final DeferredItem<BlockItem> BOSS_PORTAL =
            ITEMS.registerSimpleBlockItem(ModBlocks.BOSS_PORTAL);

    /** 神偷印记 - 「神偷」系列通货的基础面额 */
    public static final DeferredItem<Item> THIEF_MARK =
            ITEMS.registerSimpleItem("thief_mark", new Item.Properties().rarity(Rarity.UNCOMMON));

    /** 万能钥匙 - 「神偷」系列通货的高面额 */
    public static final DeferredItem<Item> MASTER_KEY =
            ITEMS.registerSimpleItem("master_key", new Item.Properties().rarity(Rarity.RARE));

    /**
     * 顶位唤魔者刷怪蛋。
     *
     * <p>NeoForge's {@link DeferredSpawnEggItem} takes the entity type as a {@link java.util.function.Supplier}
     * instead of a resolved type: the boss's {@code EntityType} is looked up when the egg is first used, so this
     * item no longer depends on entity types being registered before items. NeoForge also wires up the dispenser
     * behaviour and the client-side item colours for us (see {@code DeferredSpawnEggItem}'s own subscribers), so
     * nothing else is needed on either side.
     */
    public static final DeferredItem<DeferredSpawnEggItem> TOP_EVOKER_SPAWN_EGG =
            ITEMS.register("top_evoker_spawn_egg", () -> new DeferredSpawnEggItem(
                    ModEntities.OVERWORLD_WATCHER,
                    0x3E4A63,   // 冷灰蓝 —— 取自模型的法袍主色
                    0xE0B23C,   // 金边 —— 取自模型的王冠与镶边
                    new Item.Properties().rarity(Rarity.EPIC)));

    private ModItems() {
    }
}
