package com.homemine.stealindims.client;

import com.homemine.stealindims.StealInDimsMod;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;

/**
 * design §4.2: a player who has readied up in the lobby gets a green tick after their name, so the
 * whole team can see at a glance who is still holding things up.
 *
 * <p>The roster comes from {@link RaidClientState}, which mirrors the last status packet, so this needs
 * no packet of its own. Despite being a rendering hook this is a plain game event, not a mod event,
 * hence the differing subscriber bus from {@link StealInDimsClient}.
 */
@EventBusSubscriber(modid = StealInDimsMod.MODID, value = Dist.CLIENT)
public final class RaidNameTagOverlay {

    private static final Component READY_TICK = Component.literal("\u2714").withStyle(ChatFormatting.GREEN);

    private RaidNameTagOverlay() {
    }

    @SubscribeEvent
    public static void onRenderNameTag(RenderNameTagEvent event) {
        if (!(event.getEntity() instanceof Player player) || !RaidClientState.isReady(player.getUUID())) {
            return;
        }
        event.setContent(event.getContent().copy()
                .append(Component.literal(" "))
                .append(READY_TICK));
    }
}
