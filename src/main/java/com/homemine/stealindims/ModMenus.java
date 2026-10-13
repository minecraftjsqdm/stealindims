package com.homemine.stealindims;

import com.homemine.stealindims.menu.RaidPortalMenu;
import com.homemine.stealindims.menu.TransferCrateMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, StealInDimsMod.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<RaidPortalMenu>> RAID_PORTAL =
            MENUS.register("raid_portal", () -> IMenuTypeExtension.create(RaidPortalMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<TransferCrateMenu>> TRANSFER_CRATE =
            MENUS.register("transfer_crate", () -> IMenuTypeExtension.create(TransferCrateMenu::new));

    private ModMenus() {
    }
}
