package com.homemine.stealindims.world;

import com.homemine.stealindims.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The stage counter of the mod's own statue crank — kept, but no longer read (spec §5.4, R23).
 *
 * <p>The statue is the user's own {@code illager_statue.nbt} now and its nose is driven by Create's
 * mechanical bearing, so no stage index exists any more: the collar of the bearing is wherever the
 * player cranked it to. The counter is still saved and loaded so an existing block entity keeps its
 * data, and so a future re-use of this block has somewhere to start from.
 */
public class IllagerCrankBlockEntity extends BlockEntity {

    private static final String TAG_STAGE = "Stage";

    private int stage;

    public IllagerCrankBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ILLAGER_CRANK.get(), pos, state);
    }

    /** The last stage this crank was advanced to; vestigial since R23. */
    public int stage() {
        return this.stage;
    }

    /** Records one more quarter turn; vestigial since R23. */
    public int advance(int stops) {
        this.stage = Math.floorMod(this.stage + 1, Math.max(1, stops));
        setChanged();
        return this.stage;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(TAG_STAGE, this.stage);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.stage = tag.getInt(TAG_STAGE);
    }
}
