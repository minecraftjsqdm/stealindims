package com.homemine.stealindims;

import com.homemine.stealindims.raid.RaidProgress;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Attachment types owned by this mod.
 *
 * <p>The raid unlock progress lives on the player so it survives death and dimension changes
 * (design §17: the unlock chain is global, not per dimension).
 */
public final class ModAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, StealInDimsMod.MODID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<RaidProgress>> RAID_PROGRESS =
            ATTACHMENT_TYPES.register("raid_progress",
                    () -> AttachmentType.serializable(RaidProgress::new).copyOnDeath().build());

    private ModAttachments() {
    }
}
