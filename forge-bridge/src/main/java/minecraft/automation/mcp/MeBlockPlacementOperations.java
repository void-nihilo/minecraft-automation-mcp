package minecraft.automation.mcp;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.block.IMachineBlock;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Places one plain BlockItem from ME onto a verified, empty floor position. */
final class MeBlockPlacementOperations {
    private MeBlockPlacementOperations() {}

    static JsonObject place(ServerLevel level, BlockPos target, MeOperations.Endpoint endpoint, ResourceLocation itemId, String expectedMeToken) {
        requireTarget(level, target);

        var grid = endpoint.grid();
        var storage = grid.getStorageService().getInventory();
        var actor = MeOperations.playerAction(endpoint.level());
        AEItemKey key = MeOperations.resolveMeStack(storage, AEItemKey.class, itemId, expectedMeToken);
        ItemStack stack = key.toStack();
        if (!stack.isEmpty() && stack.hasTag()) {
            throw new BridgeError(409, "unsupported_item_data", "Block placement only accepts an untagged BlockItem");
        }
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            throw new BridgeError(409, "not_block_item", "The ME item is not a placeable BlockItem");
        }

        Block block = blockItem.getBlock();
        BlockState state = block.defaultBlockState();
        if (block == Blocks.AIR || state.isAir() || state.getBlock() != block
                || !state.canSurvive(level, target)) {
            throw new BridgeError(409, "unsupported_block_state", "The BlockItem default state is not valid at the target");
        }
        if (block instanceof IMachineBlock && !(stack.getItem() instanceof com.gregtechceu.gtceu.api.item.MetaMachineItem)) {
            throw new BridgeError(409, "unsupported_machine_item", "GT machine blocks require their registered MetaMachineItem");
        }

        long before = MeOperations.count(storage, key);
        MeOperations.requireWithdrawal(grid, storage, actor, key, 1);
        try {
            if (MeOperations.mutateMe(grid, storage, actor, key, 1, false, Actionable.MODULATE) != 1
                    || !MeOperations.matchesWithdrawal(before, 1, MeOperations.count(storage, key))) {
                throw new BridgeError(409, "me_withdrawal_failed", "ME did not withdraw exactly one BlockItem");
            }
            if (!level.setBlock(target, state, Block.UPDATE_ALL)
                    || !level.getBlockState(target).equals(state)) {
                throw new BridgeError(409, "placement_failed", "The target did not retain the BlockItem default state");
            }
            if (block instanceof IMachineBlock && MetaMachine.getMachine(level, target) == null) {
                throw new BridgeError(409, "machine_initialization_failed",
                        "The GT machine block entity did not initialize from its default state");
            }
        } catch (RuntimeException failure) {
            try {
                if (level.getBlockState(target).is(block)) {
                    level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                if (!level.getBlockState(target).isAir()) {
                    throw new IllegalStateException("Could not restore the target block to air");
                }
                MeOperations.restoreMe(storage, actor, key, before);
            } catch (RuntimeException rollback) {
                failure.addSuppressed(rollback);
                throw new IllegalStateException("ME block placement rollback did not finish", failure);
            }
            throw failure;
        }

        JsonObject result = new JsonObject();
        result.addProperty("item", itemId.toString());
        result.addProperty("placed", 1);
        result.addProperty("meBefore", before);
        result.addProperty("meAfter", MeOperations.count(storage, key));
        result.add("position", MinecraftAutomationBridge.positionJson(target));
        result.addProperty("blockState", state.toString());
        result.addProperty("gtMachineInitialized", block instanceof IMachineBlock);
        return result;
    }

    private static void requireTarget(ServerLevel level, BlockPos target) {
        if (!level.isInWorldBounds(target) || !level.hasChunkAt(target)
                || !level.getBlockState(target).isAir()) {
            throw new BridgeError(409, "placement_site_unavailable", "The target must be a loaded air block");
        }
        BlockPos floorPosition = target.below();
        if (!level.isInWorldBounds(floorPosition) || !level.hasChunkAt(floorPosition)
                || !level.getBlockState(floorPosition).isFaceSturdy(level, floorPosition, Direction.UP)) {
            throw new BridgeError(409, "unsupported_floor", "The target must sit above a sturdy, solid floor block");
        }
        if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(target), LivingEntity::isAlive).isEmpty()) {
            throw new BridgeError(409, "placement_site_occupied", "A living entity occupies the target block");
        }
    }
}
