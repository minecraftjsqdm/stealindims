package com.homemine.stealindims;

import com.homemine.stealindims.command.MojinUnlockCommand;
import com.homemine.stealindims.entity.ModEntities;
import com.homemine.stealindims.entity.OverworldWatcher;
import com.homemine.stealindims.net.ModNetwork;
import com.homemine.stealindims.raid.RaidServerEvents;
import com.homemine.stealindims.raid.RaidThemeRegistry;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import org.slf4j.Logger;

/**
 * Steal in Dims (维度神偷)
 *
 * <p>Adds one raid dungeon (a "map") per dimension that has a theme configured. A theme is a datapack
 * file under {@code data/stealindims/raid_theme/}; dimensions without one stay closed, which keeps the
 * content whitelist-driven and cheap to extend.
 *
 * <p>Every entry point is the Advanced Warp Device: right-clicking one opens the raid select screen,
 * and the device's own dimension decides which raid dimension the player is sent to.
 */
@Mod(StealInDimsMod.MODID)
public class StealInDimsMod {

    public static final String MODID = "stealindims";
    public static final Logger LOGGER = LogUtils.getLogger();

    public StealInDimsMod(IEventBus modBus, ModContainer container) {
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        ModEntities.ENTITY_TYPES.register(modBus);
        ModMenus.MENUS.register(modBus);
        ModAttachments.ATTACHMENT_TYPES.register(modBus);

        modBus.addListener(this::buildCreativeTabs);
        modBus.addListener(this::registerEntityAttributes);
        modBus.addListener(ModNetwork::register);

        container.registerConfig(ModConfig.Type.COMMON, StealInDimsConfig.SPEC);

        NeoForge.EVENT_BUS.addListener(RaidThemeRegistry::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(MojinUnlockCommand::onRegisterCommands);

        NeoForge.EVENT_BUS.register(RaidServerEvents.class);
    }

    /** The boss's attribute sheet; without this it would spawn with none and die to a stiff breeze. */
    private void registerEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.OVERWORLD_WATCHER.get(), OverworldWatcher.createAttributes().build());
    }

    private void buildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModBlocks.RAID_PORTAL.get());
            event.accept(ModBlocks.TRANSFER_CRATE.get());
            event.accept(ModBlocks.RAID_READY_PAD.get());
            event.accept(ModBlocks.RAID_EXIT_PORTAL.get());
            event.accept(ModBlocks.ILLAGER_CRANK.get());
            event.accept(ModBlocks.BOSS_PORTAL.get());
        } else if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(ModItems.THIEF_MARK.get());
            event.accept(ModItems.MASTER_KEY.get());
        } else if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ModItems.TOP_EVOKER_SPAWN_EGG.get());
        }
    }
}
