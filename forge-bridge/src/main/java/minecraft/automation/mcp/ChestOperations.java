package minecraft.automation.mcp;

import com.google.gson.JsonObject;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

final class ChestOperations {
    private ChestOperations() {}

    static ChestBlockEntity chestAt(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        if (!(state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST))
                || state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                || !(level.getBlockEntity(position) instanceof ChestBlockEntity chest)
                || chest.isRemoved()) {
            throw new BridgeError(409, "not_single_chest", "The position must contain a single vanilla chest");
        }
        if (chest.saveWithoutMetadata().contains("LootTable", Tag.TAG_STRING)) {
            throw new BridgeError(409, "unopened_loot_chest", "Open this chest in game to resolve its loot table first");
        }
        return chest;
    }

    static JsonObject prepareTestChests(ServerLevel level, BlockPos source, BlockPos target) {
        if (source.distManhattan(target) < 2 || !MinecraftAutomationBridge.isClearFixtureSite(level, source)
                || !MinecraftAutomationBridge.isClearFixtureSite(level, target)) {
            throw new BridgeError(409, "fixture_site_unavailable", "Test chests need two separated air blocks above solid ground");
        }

        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH);
        boolean sourcePlaced = false;
        boolean targetPlaced = false;
        try {
            sourcePlaced = level.setBlock(source, chest, Block.UPDATE_ALL);
            targetPlaced = level.setBlock(target, chest, Block.UPDATE_ALL);
            if (!sourcePlaced || !targetPlaced) {
                throw new BridgeError(409, "fixture_refused", "The game did not place both test chests");
            }
            chestAt(level, source).setItem(0, new ItemStack(Items.COBBLESTONE, 32));
            ChestBlockEntity targetChest = chestAt(level, target);
            // Start with four available spaces to exercise the all-or-nothing transfer check.
            targetChest.setItem(0, new ItemStack(Items.COBBLESTONE, 60));
            for (int slot = 1; slot < targetChest.getContainerSize(); slot++) {
                targetChest.setItem(slot, new ItemStack(Items.DIRT));
            }
            JsonObject result = new JsonObject();
            result.add("source", ItemHandlerOperations.contents(level, source, null));
            result.add("target", ItemHandlerOperations.contents(level, target, null));
            return result;
        } catch (RuntimeException failure) {
            // Clear the generated items before removing a test chest.
            if (sourcePlaced) {
                removeTestChest(level, source);
            }
            if (targetPlaced) {
                removeTestChest(level, target);
            }
            throw failure;
        }
    }

    private static void removeTestChest(ServerLevel level, BlockPos position) {
        if (level.getBlockEntity(position) instanceof ChestBlockEntity chest) {
            chest.clearContent();
        }
        level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }
}
