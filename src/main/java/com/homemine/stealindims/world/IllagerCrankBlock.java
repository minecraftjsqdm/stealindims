package com.homemine.stealindims.world;

import com.mojang.serialization.MapCodec;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * The mod's own statue crank — <strong>retired</strong> (spec §5.4, R23).
 *
 * <p>Until R23 the illager statue was painted block by block and this block was its driver: one
 * right-click advanced a stage counter and {@code MansionInteract} re-placed a 3×3×3 cluster of nose
 * blocks at the matching 90° stop. The statue is now the user's own {@code illager_statue.nbt},
 * stamped verbatim, and it brings <em>Create's</em> real {@code hand_crank}, chain drive and
 * mechanical bearing with it — a genuine contraption. Keeping the fake rotation as well would mean
 * two mechanisms driving the same nose, so that path is gone: this block is no longer placed by
 * {@code MansionArenaBuilder}, and turning it does nothing.
 *
 * <p>The block and its block entity stay registered because registry entries are cheap to keep and
 * expensive to take away: a world that already holds one keeps it instead of losing it to air, and
 * {@code tags/item/raid_banned.json} still keeps the item out of a raider's hands.
 */
public class IllagerCrankBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public IllagerCrankBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return simpleCodec(IllagerCrankBlock::new);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new IllagerCrankBlockEntity(pos, state);
    }
}
