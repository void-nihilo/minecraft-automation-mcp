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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class ItemHandlerOperations {
    private ItemHandlerOperations() {}

    static IItemHandler insertionTarget(ServerLevel level, BlockPos position, Direction side) {
        if (ModList.get().isLoaded("gtceu")) {
            GtBoilerProtection.beforeItemInsertion(level, position);
        }
        return at(level, position, side);
    }

    static IItemHandler at(ServerLevel level, BlockPos position, Direction side) {
        BlockEntity entity = level.getBlockEntity(position);
        if (entity == null || entity.isRemoved()) {
            throw new BridgeError(409, "item_handler_unavailable", "No live block entity at the requested location");
        }
        requireResolvedLoot(entity);
        var state = entity.getBlockState();
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = position.relative(ChestBlock.getConnectedDirection(state));
            if (!level.hasChunkAt(other)) {
                throw new BridgeError(409, "chunk_not_loaded", "The other half of the chest is in an unloaded chunk");
            }
            requireResolvedLoot(level.getBlockEntity(other));
        }
        return entity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).resolve().orElseThrow(
                () -> new BridgeError(409, "item_handler_unavailable", "No Forge item interface on the requested side"));
    }

    private static void requireResolvedLoot(BlockEntity entity) {
        if (entity instanceof RandomizableContainerBlockEntity
                && entity.saveWithoutMetadata().contains("LootTable", Tag.TAG_STRING)) {
            throw new BridgeError(409, "unopened_loot_chest", "Open the container in game to resolve its loot table first");
        }
    }

    static JsonObject contents(ServerLevel level, BlockPos position, Direction side) {
        JsonObject result = new JsonObject();
        result.addProperty("dimension", level.dimension().location().toString());
        result.add("position", MinecraftAutomationBridge.positionJson(position));
        result.addProperty("block", ForgeRegistries.BLOCKS.getKey(level.getBlockState(position).getBlock()).toString());
        if (side != null) {
            result.addProperty("side", side.getName());
        }
        JsonObject status = machineStatus(level, position);
        if (status != null) {
            result.add("machine", status);
        }
        IItemHandler handler;
        try {
            handler = at(level, position, side);
        } catch (BridgeError unavailable) {
            if (status == null || !unavailable.code().equals("item_handler_unavailable")) {
                throw unavailable;
            }
            result.addProperty("itemError", unavailable.getMessage());
            return result;
        }
        int size = handler.getSlots();
        result.addProperty("size", size);
        JsonArray items = new JsonArray();
        JsonArray slots = new JsonArray();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            JsonObject row = new JsonObject();
            row.addProperty("slot", slot);
            row.addProperty("limit", handler.getSlotLimit(slot));
            row.addProperty("count", stack.getCount());
            if (!stack.isEmpty()) {
                row.addProperty("item", ForgeRegistries.ITEMS.getKey(stack.getItem()).toString());
                row.addProperty("stackToken", stackToken(stack));
                items.add(row);
            }
            slots.add(row);
        }
        result.add("items", items);
        result.add("slots", slots);
        return result;
    }

    private static JsonObject machineStatus(ServerLevel level, BlockPos position) {
        BlockEntity entity = level.getBlockEntity(position);
        if (entity == null || entity.isRemoved()) {
            return null;
        }
        if (ModList.get().isLoaded("gtceu")) {
            JsonObject gt = GtMachineOperations.nativeStatus(level, position);
            if (gt != null) {
                return gt;
            }
        }
        JsonObject furnace = VanillaFurnaceOperations.nativeStatus(entity);
        if (furnace != null) {
            return furnace;
        }
        return ModList.get().isLoaded("ae2") ? InscriberOperations.nativeStatus(entity) : null;
    }

    static JsonObject transfer(ServerLevel sourceLevel, BlockPos sourcePosition, Direction sourceSide, int sourceSlot,
                               ServerLevel targetLevel, BlockPos targetPosition, Direction targetSide,
                               Integer targetSlot, int amount, String token) {
        if (sourceLevel == targetLevel && sourcePosition.equals(targetPosition)) {
            throw new BridgeError(400, "same_container", "Source and target must have different positions");
        }
        var source = at(sourceLevel, sourcePosition, sourceSide);
        var target = insertionTarget(targetLevel, targetPosition, targetSide);
        if (source == target) {
            throw new BridgeError(400, "same_container", "Source and target expose the same item handler");
        }
        ItemStack before = sourceStack(source, sourceSlot, amount, token);
        var plan = planInsertion(target, before.copyWithCount(amount), targetSlot);
        try {
            extract(source, sourceSlot, before, amount);
            plan.apply();
            if (!ItemStack.matches(source.getStackInSlot(sourceSlot), before.copyWithCount(before.getCount() - amount))) {
                throw new BridgeError(409, "balance_mismatch", "The transfer endpoints may share item storage");
            }
        } catch (RuntimeException failure) {
            try {
                plan.restore();
                restoreSlot(source, sourceSlot, before, before);
                plan.verifyOriginal();
            } catch (RuntimeException rollback) {
                failure.addSuppressed(rollback);
                throw new IllegalStateException("Item transfer rollback did not finish", failure);
            }
            throw failure;
        }
        JsonObject result = new JsonObject();
        result.addProperty("item", ForgeRegistries.ITEMS.getKey(before.getItem()).toString());
        result.addProperty("moved", amount);
        result.addProperty("sourceSlot", sourceSlot);
        result.addProperty("sourceRemaining", before.getCount() - amount);
        return result;
    }

    static ItemStack sourceStack(IItemHandler handler, int slot, int amount, String token) {
        requireSlot(handler, slot);
        if (amount < 1) {
            throw new BridgeError(400, "invalid_transfer", "Specify a positive transfer amount");
        }
        ItemStack before = handler.getStackInSlot(slot).copy();
        if (before.isEmpty() || !stackToken(before).equals(token)) {
            throw new BridgeError(409, "source_changed", "Source stack no longer matches the inventory read");
        }
        if (before.getCount() < amount) {
            throw new BridgeError(409, "insufficient_items", "Source slot contains fewer items than requested");
        }
        if (!ItemStack.matches(handler.extractItem(slot, amount, true), before.copyWithCount(amount))) {
            throw new BridgeError(409, "extract_refused", "Source slot cannot export the full amount on this side");
        }
        return before;
    }

    static void extract(IItemHandler handler, int slot, ItemStack before, int amount) {
        if (!ItemStack.matches(handler.getStackInSlot(slot), before)) {
            throw new BridgeError(409, "source_changed", "Source stack changed before extraction");
        }
        if (!ItemStack.matches(handler.extractItem(slot, amount, false), before.copyWithCount(amount))
                || !ItemStack.matches(handler.getStackInSlot(slot), before.copyWithCount(before.getCount() - amount))) {
            throw new BridgeError(409, "extract_refused", "Source slot did not retain the expected balance");
        }
    }

    static InsertionPlan planInsertion(IItemHandler handler, ItemStack moving, Integer targetSlot) {
        if (targetSlot != null) {
            requireSlot(handler, targetSlot);
        }
        List<Insertion> steps = new ArrayList<>();
        int remaining = moving.getCount();
        int start = targetSlot == null ? 0 : targetSlot;
        int end = targetSlot == null ? handler.getSlots() : targetSlot + 1;
        for (boolean empty : new boolean[] {false, true}) {
            for (int slot = start; slot < end && remaining > 0; slot++) {
                ItemStack before = handler.getStackInSlot(slot).copy();
                if (before.isEmpty() != empty || (!empty && !ItemStack.isSameItemSameTags(before, moving))) {
                    continue;
                }
                int remainder = remainderCount(handler.insertItem(slot, moving.copyWithCount(remaining), true),
                        moving, remaining);
                int accepted = remaining - remainder;
                if (accepted > 0) {
                    steps.add(new Insertion(slot, before, accepted));
                    remaining = remainder;
                }
            }
        }
        if (remaining != 0) {
            throw new BridgeError(409, "target_full", "Target cannot accept the full amount on this side");
        }
        return new InsertionPlan(handler, moving, steps);
    }

    private static int remainderCount(ItemStack remainder, ItemStack moving, int offered) {
        if (!remainder.isEmpty() && (!ItemStack.isSameItemSameTags(remainder, moving) || remainder.getCount() > offered)) {
            throw new IllegalStateException("The item handler returned an invalid insertion remainder");
        }
        return remainder.getCount();
    }

    static void restoreSlot(IItemHandler handler, int slot, ItemStack expected, ItemStack variant) {
        ItemStack actual = handler.getStackInSlot(slot).copy();
        if (ItemStack.matches(actual, expected)) {
            return;
        }
        if (!actual.isEmpty() && !ItemStack.isSameItemSameTags(actual, variant)) {
            throw new IllegalStateException("A different item appeared in the slot");
        }
        int delta = actual.getCount() - expected.getCount();
        if (delta > 0) {
            handler.extractItem(slot, delta, false);
        } else if (delta < 0) {
            handler.insertItem(slot, variant.copyWithCount(-delta), false);
        }
        if (!ItemStack.matches(handler.getStackInSlot(slot), expected)) {
            throw new IllegalStateException("The item handler did not restore the expected stack");
        }
    }

    private static void requireSlot(IItemHandler handler, int slot) {
        if (slot < 0 || slot >= handler.getSlots()) {
            throw new BridgeError(400, "invalid_slot", "The requested slot does not exist on this side");
        }
    }

    static String stackToken(ItemStack stack) {
        CompoundTag state = stack.save(new CompoundTag());
        if (stack.getCount() > Byte.MAX_VALUE) {
            state.putInt("Count", stack.getCount());
        }
        return Hashing.sha256().hashString(state.toString(), StandardCharsets.UTF_8).toString();
    }

    private record Insertion(int slot, ItemStack before, int amount) {}

    static final class InsertionPlan {
        private final IItemHandler handler;
        private final ItemStack moving;
        private final List<Insertion> steps;
        private int attempted;

        private InsertionPlan(IItemHandler handler, ItemStack moving, List<Insertion> steps) {
            this.handler = handler;
            this.moving = moving;
            this.steps = steps;
        }

        void apply() {
            for (Insertion step : steps) {
                if (!ItemStack.matches(handler.getStackInSlot(step.slot), step.before)) {
                    throw new BridgeError(409, "target_changed", "Target slot changed before insertion");
                }
                attempted++;
                ItemStack remainder = handler.insertItem(step.slot, moving.copyWithCount(step.amount), false);
                if (!remainder.isEmpty()) {
                    throw new BridgeError(409, "insert_refused", "The item handler refused part of the planned insertion");
                }
            }
            for (Insertion step : steps) {
                if (!ItemStack.matches(handler.getStackInSlot(step.slot),
                        moving.copyWithCount(step.before.getCount() + step.amount))) {
                    throw new BridgeError(409, "balance_mismatch", "Target slot did not retain the expected stack");
                }
            }
        }

        void restore() {
            for (int index = attempted - 1; index >= 0; index--) {
                Insertion step = steps.get(index);
                restoreSlot(handler, step.slot, step.before, moving);
            }
        }

        void verifyOriginal() {
            for (Insertion step : steps) {
                if (!ItemStack.matches(handler.getStackInSlot(step.slot), step.before)) {
                    throw new IllegalStateException("Restoring the source changed the target");
                }
            }
        }
    }
}
