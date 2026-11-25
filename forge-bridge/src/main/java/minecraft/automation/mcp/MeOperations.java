package minecraft.automation.mcp;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.IntConsumer;

final class MeOperations {
    private MeOperations() {}

    static JsonObject contents(Endpoint endpoint, ResourceLocation id,
                               Class<? extends AEKey> type, String field) {
        return contents(endpoint, List.of(id), type, field).get(0).getAsJsonObject();
    }

    static JsonArray contents(Endpoint endpoint, List<ResourceLocation> ids,
                              Class<? extends AEKey> type, String field) {
        IGrid grid = endpoint.grid();
        var byId = new LinkedHashMap<ResourceLocation, JsonObject>();
        for (ResourceLocation id : ids) {
            JsonObject result = new JsonObject();
            result.addProperty(field, id.toString());
            result.addProperty("total", 0L);
            result.add("variants", new JsonArray());
            byId.putIfAbsent(id, result);
        }
        for (var entry : grid.getStorageService().getInventory().getAvailableStacks()) {
            AEKey key = entry.getKey();
            JsonObject result = byId.get(key.getId());
            if (result != null && type.isInstance(key)) {
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
                result.getAsJsonArray("variants").add(variant);
                result.addProperty("total", result.get("total").getAsLong() + count);
            }
        }
        JsonArray result = new JsonArray();
        byId.values().forEach(result::add);
        return result;
    }

    static JsonObject transfer(IItemHandler handler, Endpoint endpoint, boolean toMe, ResourceLocation itemId,
                               int amount, Integer slot, String expectedSourceToken) {
        return transfer(handler, endpoint.grid(), playerAction(endpoint.level),
                toMe, itemId, amount, slot, expectedSourceToken);
    }

    static JsonObject transfer(IItemHandler handler, IGrid grid, IActionSource actor, boolean toMe,
                               ResourceLocation itemId, int amount, Integer slot, String expectedSourceToken) {
        if (amount < 1) {
            throw new BridgeError(400, "invalid_transfer", "Specify a positive amount");
        }
        MEStorage storage = grid.getStorageService().getInventory();
        long before;
        long after;
        int moved = amount;
        if (toMe) {
            if (slot == null) {
                throw new BridgeError(400, "invalid_source_slot", "Specify the source slot for a deposit");
            }
            ItemStack current = ItemHandlerOperations.sourceStack(handler, slot, amount, expectedSourceToken);
            AEItemKey key = AEItemKey.of(current);
            if (key == null || !key.getId().equals(itemId)) {
                throw new BridgeError(409, "source_changed", "Source stack does not match the requested item");
            }
            if (!ItemStack.isSameItemSameTags(current, key.toStack())) {
                throw new BridgeError(409, "unsupported_item", "AE2 cannot represent this stack exactly");
            }
            before = count(storage, key);
            moved = deposit(grid, storage, actor, key, amount,
                    () -> ItemHandlerOperations.extract(handler, slot, current, amount),
                    accepted -> ItemHandlerOperations.restoreSlot(handler, slot,
                            current.copyWithCount(current.getCount() - accepted), current));
            after = count(storage, key);
        } else {
            AEItemKey key = resolveMeStack(storage, AEItemKey.class, itemId, expectedSourceToken);
            var plan = ItemHandlerOperations.planInsertion(handler, key.toStack(amount), slot);
            before = count(storage, key);
            after = withdraw(grid, storage, actor, key, amount, before, plan::apply, plan::restore);
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
        requireWithdrawal(grid, storage, actor, key, amount);
        long after;
        try {
            long extracted = mutateMe(grid, storage, actor, key, amount, false, Actionable.MODULATE);
            after = count(storage, key);
            if (extracted != amount || !matchesWithdrawal(before, amount, after)) {
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

    static boolean matchesWithdrawal(long before, int amount, long after) {
        // Creative cells can expose an inexhaustible stack as Long.MAX_VALUE.
        return after == before - amount || (before == Long.MAX_VALUE && after == before);
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

    static String meToken(AEKey key, long count) {
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

    record Endpoint(ServerLevel level, BlockPos position, Direction side) {
        IGrid grid() {
            if (!level.isInWorldBounds(position) || !level.hasChunkAt(position)) {
                throw new BridgeError(409, "me_unavailable", "The ME endpoint must be in a loaded chunk");
            }
            var host = GridHelper.getNodeHost(level, position);
            if (host == null) {
                throw new BridgeError(409, "not_me_node", "The position does not expose an AE2 network node");
            }
            if (side != null) {
                return activeGrid(host.getGridNode(side));
            }
            IGridNode selected = null;
            IGrid selectedGrid = null;
            for (Direction face : Direction.values()) {
                IGridNode candidate = host.getGridNode(face);
                if (candidate == null) {
                    continue;
                }
                IGrid candidateGrid = candidate.getGrid();
                if (candidateGrid == null) {
                    continue;
                }
                if (selectedGrid != null && selectedGrid != candidateGrid) {
                    throw new BridgeError(409, "ambiguous_me_node", "Several ME networks are exposed here; specify the side");
                }
                if (selected == null || !selected.isActive()) {
                    selected = candidate;
                    selectedGrid = candidateGrid;
                }
            }
            return activeGrid(selected);
        }
    }

    private static IGrid activeGrid(IGridNode node) {
        IGrid grid = node == null ? null : node.getGrid();
        if (grid == null || !node.isActive() || !grid.getEnergyService().isNetworkPowered()) {
            throw new BridgeError(409, "me_offline", "No active powered ME node is available on the selected side");
        }
        return grid;
    }
}
