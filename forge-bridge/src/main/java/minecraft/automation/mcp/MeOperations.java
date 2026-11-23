package minecraft.automation.mcp;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.blockentity.storage.DriveBlockEntity;
import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import minecraft.automation.mcp.ChestOperations.SlotChange;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

final class MeOperations {
    private MeOperations() {}

    static JsonObject contents(ServerLevel level, BlockPos position, ResourceLocation id,
                               Class<? extends AEKey> type, String field) {
        IGrid grid = gridAt(level, position);
        JsonArray variants = new JsonArray();
        long total = 0;
        for (var entry : grid.getStorageService().getInventory().getAvailableStacks()) {
            AEKey key = entry.getKey();
            if (type.isInstance(key) && key.getId().equals(id)) {
                long count = entry.getLongValue();
                JsonObject variant = new JsonObject();
                variant.addProperty("count", count);
                variant.addProperty("meToken", meToken(key, count));
                if (key instanceof AEItemKey itemKey) {
                    ItemStack stack = itemKey.toStack();
                    if (stack.getMaxDamage() > 0) {
                        variant.addProperty("damage", stack.getDamageValue());
                        variant.addProperty("maxDamage", stack.getMaxDamage());
                    }
                }
                variants.add(variant);
                total += count;
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty(field, id.toString());
        result.addProperty("total", total);
        result.add("variants", variants);
        return result;
    }

    static JsonObject transfer(ServerLevel chestLevel, BlockPos chestPosition, ServerLevel meLevel,
                               BlockPos drivePosition, boolean toMe, ResourceLocation itemId, int amount,
                               int sourceSlot, String expectedSourceToken) {
        if (amount < 1) {
            throw new BridgeError(400, "invalid_transfer", "Specify a positive amount");
        }
        ChestBlockEntity chest = ChestOperations.chestAt(chestLevel, chestPosition);
        IGrid grid = gridAt(meLevel, drivePosition);
        IActionSource actor = playerAction(meLevel);
        MEStorage storage = grid.getStorageService().getInventory();
        long before;
        long after;
        int moved = amount;
        if (toMe) {
            if (sourceSlot < 0 || sourceSlot >= chest.getContainerSize()) {
                throw new BridgeError(400, "invalid_source_slot", "Specify a valid chest slot for a deposit");
            }
            ItemStack current = chest.getItem(sourceSlot).copy();
            AEItemKey key = AEItemKey.of(current);
            if (key == null || !key.getId().equals(itemId)
                    || !ChestOperations.stackToken(current).equals(expectedSourceToken)) {
                throw new BridgeError(409, "source_changed", "Chest stack no longer matches the inventory read");
            }
            if (!ItemStack.isSameItemSameTags(current, key.toStack())) {
                throw new BridgeError(409, "unsupported_item", "AE2 cannot represent this stack exactly");
            }
            if (current.getCount() < amount) {
                throw new BridgeError(409, "insufficient_items", "Chest slot contains fewer items than requested");
            }
            before = count(storage, key);
            ItemStack withdrawn = current.copyWithCount(current.getCount() - amount);
            moved = deposit(grid, storage, actor, key, amount,
                    () -> ChestOperations.applyChanges(List.of(new SlotChange(chest, sourceSlot, current, withdrawn))),
                    accepted -> {
                        ItemStack present = chest.getItem(sourceSlot).copy();
                        ItemStack remaining = current.copyWithCount(current.getCount() - accepted);
                        ChestOperations.applyChanges(List.of(new SlotChange(chest, sourceSlot, present, remaining)));
                    });
            after = count(storage, key);
        } else {
            AEItemKey key = resolveMeStack(storage, AEItemKey.class, itemId, expectedSourceToken);
            ItemStack stack = key.toStack();
            List<SlotChange> changes = new ArrayList<>();
            int remaining = ChestOperations.planInsertions(chest, stack, amount, false, changes);
            remaining = ChestOperations.planInsertions(chest, stack, remaining, true, changes);
            if (remaining != 0) {
                throw new BridgeError(409, "target_full", "Chest lacks room for the full transfer");
            }
            before = count(storage, key);
            after = withdraw(grid, storage, actor, key, amount, before,
                    () -> ChestOperations.applyChanges(changes),
                    () -> {
                        if (!ChestOperations.matchesBefore(changes)) {
                            throw new IllegalStateException("Chest did not restore its original slots");
                        }
                    });
        }

        JsonObject result = new JsonObject();
        result.addProperty("direction", toMe ? "to_me" : "from_me");
        result.addProperty("item", itemId.toString());
        result.addProperty("requested", amount);
        result.addProperty("moved", moved);
        result.addProperty("meBefore", before);
        result.addProperty("meAfter", after);
        return result;
    }

    static int deposit(IGrid grid, MEStorage storage, IActionSource actor, AEKey key, int amount,
                       Runnable withdrawSource, IntConsumer settleSource) {
        if (mutateMe(grid, storage, actor, key, amount, true, Actionable.SIMULATE) != amount) {
            throw new BridgeError(409, "me_transfer_refused", "ME cannot accept the full requested amount");
        }

        try {
            withdrawSource.run();
        } catch (RuntimeException failure) {
            try {
                settleSource.accept(0);
            } catch (RuntimeException rollbackError) {
                failure.addSuppressed(rollbackError);
                throw new IllegalStateException("Could not restore the source before depositing into ME", failure);
            }
            throw failure;
        }

        // The return value includes items accepted by crafting jobs, which are not ordinary stock.
        long acceptedAmount = mutateMe(grid, storage, actor, key, amount, true, Actionable.MODULATE);
        if (acceptedAmount < 0 || acceptedAmount > amount) {
            throw new IllegalStateException("AE2 reported an invalid accepted quantity: " + acceptedAmount);
        }
        int accepted = (int) acceptedAmount;
        if (accepted != amount) {
            settleSource.accept(accepted);
        }
        if (accepted == 0) {
            throw new BridgeError(409, "me_transfer_refused", "ME did not accept any of the requested amount");
        }
        return accepted;
    }

    static void requireWithdrawal(IGrid grid, MEStorage storage, IActionSource actor, AEKey key, int amount) {
        if (mutateMe(grid, storage, actor, key, amount, false, Actionable.SIMULATE) != amount) {
            throw new BridgeError(409, "me_transfer_refused", "ME cannot extract the full amount");
        }
    }

    static long withdraw(IGrid grid, MEStorage storage, IActionSource actor, AEKey key, int amount,
                         long before, Runnable putTarget, Runnable restoreTarget) {
        long after = Math.subtractExact(before, amount);
        requireWithdrawal(grid, storage, actor, key, amount);
        try {
            if (mutateMe(grid, storage, actor, key, amount, false, Actionable.MODULATE) != amount
                    || count(storage, key) != after) {
                throw new BridgeError(409, "me_transfer_refused", "ME did not retain the planned balance");
            }
            putTarget.run();
        } catch (RuntimeException failure) {
            try {
                restoreTarget.run();
                restoreMe(storage, actor, key, before);
            } catch (RuntimeException rollback) {
                failure.addSuppressed(rollback);
                throw new IllegalStateException("ME withdrawal rollback did not finish", failure);
            }
            throw failure;
        }
        return after;
    }

    static long mutateMe(IGrid grid, MEStorage storage, IActionSource actor, AEKey key,
                                 long amount, boolean insert, Actionable mode) {
        return insert
                ? StorageHelper.poweredInsert(grid.getEnergyService(), storage, key, amount, actor, mode)
                : StorageHelper.poweredExtraction(grid.getEnergyService(), storage, key, amount, actor, mode);
    }

    static long count(MEStorage storage, AEKey key) {
        return storage.getAvailableStacks().get(key);
    }

    static <K extends AEKey> K resolveMeStack(MEStorage storage, Class<K> type, ResourceLocation id, String token) {
        return resolveMeStack(storage.getAvailableStacks(), type, id, token);
    }

    static <K extends AEKey> K resolveMeStack(KeyCounter stacks, Class<K> type, ResourceLocation id, String token) {
        for (var entry : stacks) {
            AEKey key = entry.getKey();
            if (type.isInstance(key) && key.getId().equals(id)
                    && meToken(key, entry.getLongValue()).equals(token)) {
                return type.cast(key);
            }
        }
        throw new BridgeError(409, "source_changed", "ME stack no longer matches the inventory read");
    }

    private static String meToken(AEKey key, long count) {
        return Hashing.sha256().hashString(key.toTag() + ":" + count, StandardCharsets.UTF_8).toString();
    }

    static void restoreMe(MEStorage storage, IActionSource actor, AEKey key, long before) {
        long delta = count(storage, key) - before;
        if (delta > 0) {
            storage.extract(key, delta, Actionable.MODULATE, actor);
        } else if (delta < 0) {
            storage.insert(key, -delta, Actionable.MODULATE, actor);
        }
        if (count(storage, key) != before) {
            throw new IllegalStateException("ME did not restore its original item count");
        }
    }

    static IActionSource playerAction(ServerLevel level) {
        var players = level.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) {
            throw new BridgeError(409, "no_player", "Enter the singleplayer world first");
        }
        return IActionSource.ofPlayer(players.get(0));
    }

    static IGrid gridAt(ServerLevel level, BlockPos position) {
        if (!(level.getBlockEntity(position) instanceof DriveBlockEntity drive) || drive.isRemoved()) {
            throw new BridgeError(409, "not_me_drive", "The position must contain an ME Drive");
        }
        IGridNode node = drive.getMainNode().getNode();
        if (node == null || !node.isActive() || !node.getGrid().getEnergyService().isNetworkPowered()) {
            throw new BridgeError(409, "me_offline", "ME Drive is waiting for power or a channel");
        }
        return node.getGrid();
    }

    static JsonObject prepareTestNetwork(ServerLevel level, BlockPos drivePosition, BlockPos powerPosition) {
        if (drivePosition.distManhattan(powerPosition) != 1 || !MinecraftAutomationBridge.isClearFixtureSite(level, drivePosition)
                || !MinecraftAutomationBridge.isClearFixtureSite(level, powerPosition)) {
            throw new BridgeError(409, "fixture_site_unavailable", "Test network needs two neighboring air blocks above solid ground");
        }
        Block driveBlock = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse("ae2:drive"));
        Block powerBlock = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse("ae2:creative_energy_cell"));
        Item storageCell = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse("ae2:item_storage_cell_1k"));
        if (driveBlock == null || driveBlock == Blocks.AIR || powerBlock == null || powerBlock == Blocks.AIR
                || storageCell == null) {
            throw new BridgeError(409, "fixture_unavailable", "Required AE2 components are unavailable");
        }
        try {
            if (!level.setBlock(drivePosition, driveBlock.defaultBlockState(), Block.UPDATE_ALL)
                    || !level.setBlock(powerPosition, powerBlock.defaultBlockState(), Block.UPDATE_ALL)
                    || !(level.getBlockEntity(drivePosition) instanceof DriveBlockEntity drive)) {
                throw new BridgeError(409, "fixture_refused", "The world did not place both AE2 components");
            }
            drive.getInternalInventory().setItemDirect(0, new ItemStack(storageCell));
            JsonObject result = new JsonObject();
            result.add("drive", MinecraftAutomationBridge.positionJson(drivePosition));
            result.add("power", MinecraftAutomationBridge.positionJson(powerPosition));
            return result;
        } catch (RuntimeException failure) {
            if (level.getBlockEntity(drivePosition) instanceof DriveBlockEntity drive) {
                drive.getInternalInventory().setItemDirect(0, ItemStack.EMPTY);
            }
            if (level.getBlockState(drivePosition).is(driveBlock)) {
                level.setBlock(drivePosition, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            if (level.getBlockState(powerPosition).is(powerBlock)) {
                level.setBlock(powerPosition, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            throw failure;
        }
    }

}
