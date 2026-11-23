package minecraft.automation.mcp;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class ChestOperations {
    private ChestOperations() {}

    static JsonObject contents(ServerLevel level, BlockPos position) {
        ChestBlockEntity chest = chestAt(level, position);
        JsonObject result = new JsonObject();
        result.addProperty("dimension", level.dimension().location().toString());
        result.add("position", MinecraftAutomationBridge.positionJson(position));
        result.addProperty("size", chest.getContainerSize());

        JsonArray items = new JsonArray();
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (!stack.isEmpty()) {
                JsonObject item = new JsonObject();
                item.addProperty("slot", slot);
                item.addProperty("item", ForgeRegistries.ITEMS.getKey(stack.getItem()).toString());
                item.addProperty("count", stack.getCount());
                item.addProperty("stackToken", stackToken(stack));
                items.add(item);
            }
        }
        result.add("items", items);
        return result;
    }

    static JsonObject transfer(ServerLevel sourceLevel, BlockPos sourcePosition, int sourceSlot,
                               ServerLevel targetLevel, BlockPos targetPosition, int amount,
                               String expectedStackToken) {
        if (sourceLevel == targetLevel && sourcePosition.equals(targetPosition)) {
            throw new BridgeError(400, "same_chest", "Source and target must be different chests");
        }

        ChestBlockEntity source = chestAt(sourceLevel, sourcePosition);
        ChestBlockEntity target = chestAt(targetLevel, targetPosition);
        if (sourceSlot < 0 || sourceSlot >= source.getContainerSize() || amount < 1) {
            throw new BridgeError(400, "invalid_transfer", "Use a valid source slot and a positive amount");
        }

        ItemStack stack = source.getItem(sourceSlot);
        if (stack.isEmpty() || !stackToken(stack).equals(expectedStackToken)) {
            throw new BridgeError(409, "source_changed", "Source stack no longer matches the inventory read");
        }
        if (stack.getCount() < amount) {
            throw new BridgeError(409, "insufficient_items", "Source slot contains fewer items than requested");
        }

        ItemStack original = stack.copy();
        List<SlotChange> changes = new ArrayList<>();
        changes.add(new SlotChange(source, sourceSlot, original,
                original.copyWithCount(original.getCount() - amount)));
        int remaining = planInsertions(target, original, amount, false, changes);
        remaining = planInsertions(target, original, remaining, true, changes);
        if (remaining != 0) {
            throw new BridgeError(409, "target_full", "Target chest lacks room for the full transfer");
        }

        applyChanges(changes);
        JsonObject result = new JsonObject();
        result.addProperty("moved", amount);
        result.addProperty("item", ForgeRegistries.ITEMS.getKey(original.getItem()).toString());
        result.addProperty("sourceSlot", sourceSlot);
        result.addProperty("sourceRemaining", source.getItem(sourceSlot).getCount());
        return result;
    }

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

    static int planInsertions(ChestBlockEntity target, ItemStack stack, int remaining,
                                      boolean emptySlots, List<SlotChange> changes) {
        for (int slot = 0; slot < target.getContainerSize() && remaining > 0; slot++) {
            ItemStack current = target.getItem(slot);
            if (current.isEmpty() != emptySlots || !target.canPlaceItem(slot, stack)
                    || (!emptySlots && !ItemStack.isSameItemSameTags(current, stack))) {
                continue;
            }

            int limit = Math.min(target.getMaxStackSize(), stack.getMaxStackSize());
            if (!emptySlots) {
                limit = Math.min(limit, current.getMaxStackSize());
            }
            int accepted = Math.min(remaining, limit - current.getCount());
            if (accepted > 0) {
                ItemStack replacement = (emptySlots ? stack : current).copyWithCount(current.getCount() + accepted);
                changes.add(new SlotChange(target, slot, current.copy(), replacement));
                remaining -= accepted;
            }
        }
        return remaining;
    }

    static void applyChanges(List<SlotChange> changes) {
        int attempted = 0;
        try {
            for (SlotChange change : changes) {
                attempted++;
                change.chest.setItem(change.slot, change.after.copy());
            }
            for (SlotChange change : changes) {
                if (!ItemStack.matches(change.chest.getItem(change.slot), change.after)) {
                    throw new BridgeError(409, "transfer_refused", "A chest did not retain the planned stack");
                }
            }
        } catch (RuntimeException failure) {
            for (int index = attempted - 1; index >= 0; index--) {
                SlotChange change = changes.get(index);
                try {
                    change.chest.setItem(change.slot, change.before.copy());
                    if (!ItemStack.matches(change.chest.getItem(change.slot), change.before)) {
                        throw new IllegalStateException("Chest slot did not retain the restored stack");
                    }
                } catch (RuntimeException rollbackError) {
                    failure.addSuppressed(rollbackError);
                }
            }
            if (failure.getSuppressed().length != 0) {
                throw new IllegalStateException("Chest transfer rollback did not finish", failure);
            }
            throw failure;
        }
    }

    static boolean matchesBefore(List<SlotChange> changes) {
        return changes.stream().allMatch(change ->
                ItemStack.matches(change.chest.getItem(change.slot), change.before));
    }

    static String stackToken(ItemStack stack) {
        String state = stack.save(new CompoundTag()).toString();
        return Hashing.sha256().hashString(state, StandardCharsets.UTF_8).toString();
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
            result.add("source", contents(level, source));
            result.add("target", contents(level, target));
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

    record SlotChange(ChestBlockEntity chest, int slot, ItemStack before, ItemStack after) {}
}
