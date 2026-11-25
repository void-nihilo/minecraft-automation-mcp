package minecraft.automation.mcp;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.stacks.AEItemKey;
import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fml.ModList;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GiantSpruceOperations {
    private static final int RADIUS = 7;
    private static final int HEIGHT = 36;
    private static final BlockState SAPLING = Blocks.SPRUCE_SAPLING.defaultBlockState();

    private GiantSpruceOperations() {}

    static JsonObject find(ServerLevel level, BlockPos center, int radius) {
        if (radius < 1 || radius > 48) {
            throw new IllegalArgumentException("Radius must be from 1 to 48");
        }
        JsonArray trees = new JsonArray();
        JsonArray drives = new JsonArray();
        JsonArray nodes = new JsonArray();
        boolean ae2Loaded = ModList.get().isLoaded("ae2");
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int bottom = Math.max(level.getMinBuildHeight() + 1, center.getY() - 40);
        int top = Math.min(level.getMaxBuildHeight() - 2, center.getY() + 40);
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                if (!level.hasChunkAt(at.set(x, bottom, z))) {
                    continue;
                }
                for (int y = bottom; y <= top; y++) {
                    at.set(x, y, z);
                    BlockState block = level.getBlockState(at);
                    if (ae2Loaded && nodes.size() < 64 && block.hasBlockEntity() && GridHelper.getNodeHost(level, at) != null) {
                        addMeLocations(level, at, drives, nodes);
                    }
                    if (block.is(Blocks.SPRUCE_LOG) && trees.size() < 64
                            && level.hasChunkAt(at.south().east()) && level.hasChunkAt(at.east())
                            && level.hasChunkAt(at.south()) && hasBase(level, at)) {
                        trees.add(MinecraftAutomationBridge.positionJson(at));
                    }
                }
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("dimension", level.dimension().location().toString());
        result.add("spruceRoots", trees);
        result.add("meDrives", drives);
        result.add("meNodes", nodes);
        return result;
    }

    private static void addMeLocations(ServerLevel level, BlockPos position, JsonArray legacy, JsonArray nodes) {
        try {
            new MeOperations.Endpoint(level, position, null).grid();
            legacy.add(MinecraftAutomationBridge.positionJson(position));
            nodes.add(meLocation(level, position));
        } catch (BridgeError error) {
            if (!error.code().equals("ambiguous_me_node")) {
                return;
            }
            for (Direction side : Direction.values()) {
                if (nodes.size() == 64) {
                    break;
                }
                try {
                    new MeOperations.Endpoint(level, position, side).grid();
                    JsonObject node = meLocation(level, position);
                    node.addProperty("side", side.getName());
                    nodes.add(node);
                } catch (BridgeError unavailable) {
                    // This face does not have an active network.
                }
            }
        }
    }

    private static JsonObject meLocation(ServerLevel level, BlockPos position) {
        JsonObject result = new JsonObject();
        result.addProperty("dimension", level.dimension().location().toString());
        result.add("position", MinecraftAutomationBridge.positionJson(position));
        return result;
    }

    static JsonObject run(ServerLevel level, BlockPos root, MeOperations.Endpoint endpoint, String expectedPlan) {
        List<BlockPos> base = base(root);
        Map<BlockPos, BlockState> logs = inspectTree(level, root, base);
        var grid = endpoint.grid();
        var actor = MeOperations.playerAction(endpoint.level());
        var storage = grid.getStorageService().getInventory();
        var wood = AEItemKey.of(Items.SPRUCE_LOG);
        var saplings = AEItemKey.of(Items.SPRUCE_SAPLING);
        int count = logs.size();
        long woodBefore = MeOperations.count(storage, wood);
        long saplingsBefore = MeOperations.count(storage, saplings);
        int fromMe = (int) Math.min(base.size(), saplingsBefore);
        int fromLeaves = base.size() - fromMe;
        Map<BlockPos, BlockState> leaves = nearbyLeaves(level, logs);

        StringBuilder signature = new StringBuilder(level.dimension().location() + "|" + endpoint.level().dimension().location()
                + "|" + endpoint.position() + "|" + endpoint.side() + "|" + woodBefore + "|" + saplingsBefore);
        for (var entry : logs.entrySet()) {
            signature.append('|').append(entry.getKey()).append('=').append(entry.getValue());
        }
        for (BlockPos at : base) {
            signature.append('|').append(level.getBlockState(at.below()));
        }
        for (var entry : leaves.entrySet()) {
            signature.append('|').append(entry.getKey()).append('=').append(entry.getValue());
        }
        String plan = Hashing.sha256().hashString(signature, StandardCharsets.UTF_8).toString();

        JsonObject result = new JsonObject();
        result.add("root", MinecraftAutomationBridge.positionJson(root));
        result.add("drive", MinecraftAutomationBridge.positionJson(endpoint.position()));
        result.addProperty("logs", count);
        result.addProperty("meLogsBefore", woodBefore);
        result.addProperty("meSaplingsBefore", saplingsBefore);
        result.addProperty("saplingsFromMe", fromMe);
        result.addProperty("saplingsNeededFromLeaves", fromLeaves);
        result.addProperty("eligibleLeaves", leaves.size());
        JsonArray planting = new JsonArray();
        base.forEach(at -> planting.add(MinecraftAutomationBridge.positionJson(at)));
        result.add("planting", planting);

        boolean ready = (fromLeaves == 0 || (!leaves.isEmpty() && level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)))
                && (fromMe == 0 || MeOperations.mutateMe(grid, storage, actor, saplings, fromMe, false, Actionable.SIMULATE) == fromMe)
                && MeOperations.mutateMe(grid, storage, actor, wood, count, true, Actionable.SIMULATE) == count;
        result.addProperty("ready", ready);
        if (expectedPlan == null) {
            result.addProperty("plan", plan);
            return result;
        }
        if (!expectedPlan.equals(plan)) {
            throw new BridgeError(409, "spruce_plan_changed", "Inspect the tree and ME inventory again");
        }
        if (!ready) {
            throw new BridgeError(409, "spruce_me_unavailable", "The tree or ME must supply four saplings and ME must accept all logs");
        }

        Map<AEItemKey, Integer> drops = leafDrops(level, leaves);
        int droppedSaplings = drops.getOrDefault(saplings, 0);
        int reusedSaplings = Math.min(base.size(), droppedSaplings);
        int saplingsToExtract = base.size() - reusedSaplings;
        if (saplingsToExtract > fromMe) {
            throw new BridgeError(409, "spruce_saplings_short", "The canopy and ME did not supply four replacement saplings");
        }
        if (droppedSaplings > 0) {
            drops.put(saplings, droppedSaplings - reusedSaplings);
        }
        drops.merge(wood, count, Math::addExact);
        Map<AEItemKey, Long> balances = new LinkedHashMap<>();
        balances.put(saplings, saplingsBefore);
        for (var drop : drops.entrySet()) {
            AEItemKey key = drop.getKey();
            int amount = drop.getValue();
            balances.putIfAbsent(key, MeOperations.count(storage, key));
            if (amount > 0 && MeOperations.mutateMe(grid, storage, actor, key, amount, true, Actionable.SIMULATE) != amount) {
                throw new BridgeError(409, "spruce_me_unavailable", "ME cannot accept all drops for " + key.getId());
            }
        }
        Map<BlockPos, BlockState> changed = new LinkedHashMap<>();
        var ordered = logs.keySet().stream().sorted(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed()).toList();
        try {
            if (saplingsToExtract > 0 && (MeOperations.mutateMe(grid, storage, actor, saplings, saplingsToExtract, false,
                    Actionable.MODULATE) != saplingsToExtract || !MeOperations.matchesWithdrawal(saplingsBefore,
                    saplingsToExtract, MeOperations.count(storage, saplings)))) {
                throw new BridgeError(409, "spruce_me_unavailable", "ME did not supply the planned spruce saplings");
            }
            for (var leaf : leaves.entrySet()) {
                replace(level, leaf.getKey(), leaf.getValue(), Blocks.AIR.defaultBlockState(), changed);
            }
            for (BlockPos at : ordered) {
                BlockState replacement = base.contains(at) ? SAPLING : Blocks.AIR.defaultBlockState();
                replace(level, at, logs.get(at), replacement, changed);
            }
            for (var drop : drops.entrySet()) {
                int amount = drop.getValue();
                if (amount == 0) {
                    continue;
                }
                AEItemKey key = drop.getKey();
                long before = MeOperations.count(storage, key);
                long expected = before > Long.MAX_VALUE - amount ? Long.MAX_VALUE : before + amount;
                if (MeOperations.mutateMe(grid, storage, actor, key, amount, true, Actionable.MODULATE) != amount
                        || MeOperations.count(storage, key) != expected) {
                    throw new BridgeError(409, "spruce_me_unavailable", "ME did not retain all drops for " + key.getId());
                }
            }
        } catch (RuntimeException failure) {
            List<BlockPos> rollback = new ArrayList<>(changed.keySet());
            for (int i = rollback.size() - 1; i >= 0; i--) {
                BlockPos at = rollback.get(i);
                try {
                    level.setBlock(at, changed.get(at), Block.UPDATE_ALL);
                    if (!level.getBlockState(at).equals(changed.get(at))) {
                        throw new IllegalStateException("Failed to restore the spruce block at " + at);
                    }
                } catch (RuntimeException rollbackError) {
                    failure.addSuppressed(rollbackError);
                }
            }
            try {
                for (var balance : balances.entrySet()) {
                    MeOperations.restoreMe(storage, actor, balance.getKey(), balance.getValue());
                }
            } catch (RuntimeException rollbackError) {
                failure.addSuppressed(rollbackError);
            }
            if (failure.getSuppressed().length != 0) {
                throw new IllegalStateException("Spruce harvest rollback was incomplete", failure);
            }
            throw failure;
        }
        JsonObject stored = new JsonObject();
        for (var drop : drops.entrySet()) {
            if (drop.getValue() > 0) {
                String item = drop.getKey().getId().toString();
                int previouslyStored = stored.has(item) ? stored.get(item).getAsInt() : 0;
                stored.addProperty(item, Math.addExact(previouslyStored, drop.getValue()));
            }
        }
        result.add("dropsStored", stored);
        result.addProperty("saplingsFromMe", saplingsToExtract);
        result.addProperty("meLogsAfter", MeOperations.count(storage, wood));
        result.addProperty("meSaplingsAfter", MeOperations.count(storage, saplings));
        result.addProperty("leavesHarvested", leaves.size());
        result.addProperty("harvested", true);
        return result;
    }

    static void plantFromMe(ServerLevel level, BlockPos root, MeOperations.Endpoint endpoint) {
        checkPlot(level, root);
        List<BlockPos> positions = base(root);
        for (BlockPos at : BlockPos.betweenClosed(root.offset(-RADIUS, 0, -RADIUS),
                root.offset(RADIUS + 1, 24, RADIUS + 1))) {
            if (!level.isEmptyBlock(at)) {
                throw new BridgeError(409, "spruce_plot_occupied", "The planting and growth area must be empty: " + at);
            }
        }
        for (BlockPos at : positions) {
            BlockPos soil = at.below();
            if (!level.isEmptyBlock(soil) || !level.getBlockState(soil.below()).isFaceSturdy(level, soil.below(), Direction.UP)) {
                throw new BridgeError(409, "spruce_soil_site_invalid", "Each soil position needs empty space above a solid floor");
            }
        }

        var grid = endpoint.grid();
        var actor = MeOperations.playerAction(endpoint.level());
        var storage = grid.getStorageService().getInventory();
        var dirt = AEItemKey.of(Items.DIRT);
        var saplings = AEItemKey.of(Items.SPRUCE_SAPLING);
        int count = positions.size();
        long dirtBefore = MeOperations.count(storage, dirt);
        long saplingsBefore = MeOperations.count(storage, saplings);
        if (MeOperations.mutateMe(grid, storage, actor, dirt, count, false, Actionable.SIMULATE) != count
                || MeOperations.mutateMe(grid, storage, actor, saplings, count, false, Actionable.SIMULATE) != count) {
            throw new BridgeError(409, "spruce_materials_missing", "ME needs four dirt and four spruce saplings");
        }

        Map<BlockPos, BlockState> changed = new LinkedHashMap<>();
        try {
            if (MeOperations.mutateMe(grid, storage, actor, dirt, count, false, Actionable.MODULATE) != count
                    || MeOperations.mutateMe(grid, storage, actor, saplings, count, false, Actionable.MODULATE) != count) {
                throw new BridgeError(409, "spruce_materials_changed", "ME did not supply the reserved dirt and saplings");
            }
            for (BlockPos at : positions) {
                BlockPos soil = at.below();
                replace(level, soil, level.getBlockState(soil), Blocks.DIRT.defaultBlockState(), changed);
                replace(level, at, level.getBlockState(at), SAPLING, changed);
            }
        } catch (RuntimeException failure) {
            List<BlockPos> rollback = new ArrayList<>(changed.keySet());
            for (int index = rollback.size() - 1; index >= 0; index--) {
                BlockPos at = rollback.get(index);
                try {
                    level.setBlock(at, changed.get(at), Block.UPDATE_ALL);
                    if (!level.getBlockState(at).equals(changed.get(at))) {
                        throw new IllegalStateException("Failed to restore the planting site at " + at);
                    }
                } catch (RuntimeException rollbackError) {
                    failure.addSuppressed(rollbackError);
                }
            }
            try {
                MeOperations.restoreMe(storage, actor, dirt, dirtBefore);
                MeOperations.restoreMe(storage, actor, saplings, saplingsBefore);
            } catch (RuntimeException rollbackError) {
                failure.addSuppressed(rollbackError);
            }
            throw failure;
        }
    }

    static boolean growing(ServerLevel level, BlockPos root) {
        checkPlot(level, root);
        return base(root).stream().allMatch(at -> level.getBlockState(at).is(Blocks.SPRUCE_SAPLING));
    }

    static int recoverLooseDrops(ServerLevel level, BlockPos root, MeOperations.Endpoint endpoint) {
        checkPlot(level, root);
        AABB recoveryArea = new AABB(root.offset(-RADIUS, -1, -RADIUS), root.offset(RADIUS + 2, HEIGHT + 1, RADIUS + 2));
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, recoveryArea, entity -> {
            ItemStack stack = entity.getItem();
            return entity.getOwner() == null && (stack.is(Items.SPRUCE_SAPLING) || stack.is(Items.STICK));
        });
        if (items.isEmpty()) {
            return 0;
        }
        var grid = endpoint.grid();
        var actor = MeOperations.playerAction(endpoint.level());
        var storage = grid.getStorageService().getInventory();
        int stored = 0;
        for (ItemEntity entity : items) {
            ItemStack stack = entity.getItem();
            AEItemKey key = AEItemKey.of(stack);
            if (key == null || !ItemStack.isSameItemSameTags(stack, key.toStack())) {
                continue;
            }
            int moved = Math.toIntExact(MeOperations.mutateMe(grid, storage, actor, key, stack.getCount(), true, Actionable.MODULATE));
            if (moved == stack.getCount()) {
                entity.discard();
            } else if (moved > 0) {
                entity.setItem(stack.copyWithCount(stack.getCount() - moved));
            }
            stored += moved;
        }
        return stored;
    }

    private static void replace(ServerLevel level, BlockPos at, BlockState expected, BlockState replacement,
                                Map<BlockPos, BlockState> changed) {
        if (!level.getBlockState(at).equals(expected)) {
            throw new BridgeError(409, "spruce_changed", "A planned spruce block changed");
        }
        changed.put(at, expected);
        if (!level.setBlock(at, replacement, Block.UPDATE_ALL) || !level.getBlockState(at).equals(replacement)) {
            throw new BridgeError(409, "spruce_changed", "The world rejected a harvest or planting change");
        }
    }

    private static Map<BlockPos, BlockState> nearbyLeaves(ServerLevel level, Map<BlockPos, BlockState> logs) {
        var leaves = new LinkedHashMap<BlockPos, BlockState>();
        List<BlockPos> previous = new ArrayList<>(logs.keySet());
        for (int distance = 1; distance <= 6 && !previous.isEmpty(); distance++) {
            List<BlockPos> next = new ArrayList<>();
            for (BlockPos at : previous) {
                for (Direction direction : Direction.values()) {
                    BlockPos neighbor = at.relative(direction);
                    if (leaves.containsKey(neighbor) || !level.isInWorldBounds(neighbor) || !level.hasChunkAt(neighbor)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(neighbor);
                    if (state.is(Blocks.SPRUCE_LEAVES) && !state.getValue(LeavesBlock.PERSISTENT)
                            && state.getValue(LeavesBlock.DISTANCE) == distance) {
                        leaves.put(neighbor, state);
                        next.add(neighbor);
                    }
                }
            }
            previous = next;
        }
        return leaves;
    }

    private static Map<AEItemKey, Integer> leafDrops(ServerLevel level, Map<BlockPos, BlockState> leaves) {
        Map<AEItemKey, Integer> drops = new LinkedHashMap<>();
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
            return drops;
        }
        for (var entry : leaves.entrySet()) {
            for (ItemStack drop : Block.getDrops(entry.getValue(), level, entry.getKey(), null)) {
                AEItemKey key = AEItemKey.of(drop);
                if (key != null) {
                    if (!ItemStack.isSameItemSameTags(drop, key.toStack())) {
                        throw new BridgeError(409, "unsupported_tree_drop", "ME cannot represent " + key.getId() + " exactly");
                    }
                    drops.merge(key, drop.getCount(), Math::addExact);
                }
            }
        }
        return drops;
    }

    private static void checkPlot(ServerLevel level, BlockPos root) {
        BlockPos low = root.offset(-RADIUS, -1, -RADIUS);
        BlockPos high = root.offset(RADIUS + 1, HEIGHT, RADIUS + 1);
        if (!level.isInWorldBounds(low) || !level.isInWorldBounds(high)
                || !level.getWorldBorder().isWithinBounds(low) || !level.getWorldBorder().isWithinBounds(high)) {
            throw new BridgeError(409, "spruce_bounds", "The full spruce plot must be inside the world bounds");
        }
        for (int x = low.getX() >> 4; x <= high.getX() >> 4; x++) {
            for (int z = low.getZ() >> 4; z <= high.getZ() >> 4; z++) {
                if (!level.hasChunk(x, z)) {
                    throw new BridgeError(409, "chunk_not_loaded", "The full spruce plot must be loaded");
                }
            }
        }
    }

    private static Map<BlockPos, BlockState> inspectTree(ServerLevel level, BlockPos root, List<BlockPos> base) {
        checkPlot(level, root);
        BlockPos low = root.offset(-RADIUS, -1, -RADIUS);
        BlockPos high = root.offset(RADIUS + 1, HEIGHT, RADIUS + 1);
        if (!hasBase(level, root) || base.stream().anyMatch(at -> !SAPLING.canSurvive(level, at))) {
            throw new BridgeError(409, "not_giant_spruce", "The root needs a 2x2 vertical spruce trunk over suitable soil");
        }
        Map<BlockPos, BlockState> candidates = new LinkedHashMap<>();
        for (BlockPos at : BlockPos.betweenClosed(low, high)) {
            BlockState state = level.getBlockState(at);
            if (state.is(Blocks.SPRUCE_LOG)) {
                candidates.put(at.immutable(), state);
            }
        }

        var found = new HashSet<BlockPos>(base);
        var pending = new ArrayDeque<BlockPos>(base);
        boolean crown = false;
        while (!pending.isEmpty()) {
            BlockPos at = pending.removeFirst();
            if (at.getY() == low.getY() || at.getY() == high.getY() || at.getX() == low.getX()
                    || at.getX() == high.getX() || at.getZ() == low.getZ() || at.getZ() == high.getZ()
                    || (at.getY() == root.getY() && !base.contains(at)) || found.size() > 192) {
                throw new BridgeError(409, "spruce_not_isolated", "Connected spruce logs leave the supported tree plot");
            }
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = at.relative(direction);
                if (candidates.containsKey(neighbor) && found.add(neighbor)) {
                    pending.add(neighbor);
                }
                if (!crown && at.getY() >= root.getY() + 8) {
                    BlockState leaf = level.getBlockState(neighbor);
                    crown = leaf.is(Blocks.SPRUCE_LEAVES) && !leaf.getValue(LeavesBlock.PERSISTENT);
                }
            }
        }
        if (!crown) {
            throw new BridgeError(409, "not_giant_spruce", "The tall trunk needs a natural spruce canopy");
        }
        candidates.keySet().retainAll(found);
        return candidates;
    }

    private static boolean hasBase(ServerLevel level, BlockPos root) {
        for (BlockPos at : base(root)) {
            BlockState state = level.getBlockState(at);
            if (!state.is(Blocks.SPRUCE_LOG) || state.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y
                    || !level.getBlockState(at.above()).is(Blocks.SPRUCE_LOG)
                    || level.getBlockState(at.below()).is(Blocks.SPRUCE_LOG)) {
                return false;
            }
        }
        return true;
    }

    private static List<BlockPos> base(BlockPos root) {
        return List.of(root.immutable(), root.east(), root.south(), root.south().east());
    }
}
