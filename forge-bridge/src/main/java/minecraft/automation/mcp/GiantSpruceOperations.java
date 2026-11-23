package minecraft.automation.mcp;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

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
        Block drive = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse("ae2:drive"));
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
                    if (drive != null && drive != Blocks.AIR && block.is(drive) && drives.size() < 64) {
                        drives.add(MinecraftAutomationBridge.positionJson(at));
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
        return result;
    }

    static JsonObject run(ServerLevel level, BlockPos root, ServerLevel meLevel,
                          BlockPos drive, String expectedPlan) {
        List<BlockPos> base = base(root);
        Map<BlockPos, BlockState> logs = inspectTree(level, root, base);
        var grid = MeOperations.gridAt(meLevel, drive);
        var actor = MeOperations.playerAction(meLevel);
        var storage = grid.getStorageService().getInventory();
        var wood = AEItemKey.of(Items.SPRUCE_LOG);
        var saplings = AEItemKey.of(Items.SPRUCE_SAPLING);
        int count = logs.size();
        long woodBefore = MeOperations.count(storage, wood);
        long saplingsBefore = MeOperations.count(storage, saplings);
        int fromMe = (int) Math.min(base.size(), saplingsBefore);
        int fromLeaves = base.size() - fromMe;
        Map<BlockPos, BlockState> leaves = fromLeaves == 0 ? Map.of() : nearbyLeaves(level, logs);
        long woodAfter = Math.addExact(woodBefore, count);
        long saplingsAfter = saplingsBefore - fromMe;

        StringBuilder signature = new StringBuilder(level.dimension().location() + "|" + meLevel.dimension().location()
                + "|" + drive + "|" + woodBefore + "|" + saplingsBefore);
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
        result.add("drive", MinecraftAutomationBridge.positionJson(drive));
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

        LeafHarvest leafHarvest = gatherSaplings(level, leaves, fromLeaves);
        Map<BlockPos, BlockState> changed = new LinkedHashMap<>();
        var ordered = logs.keySet().stream().sorted(Comparator.comparingInt((BlockPos pos) -> pos.getY()).reversed()).toList();
        try {
            if (fromMe > 0 && (MeOperations.mutateMe(grid, storage, actor, saplings, fromMe, false, Actionable.MODULATE) != fromMe
                    || MeOperations.count(storage, saplings) != saplingsAfter)) {
                throw new BridgeError(409, "spruce_me_unavailable", "ME did not supply the planned spruce saplings");
            }
            for (BlockPos at : leafHarvest.positions) {
                replace(level, at, leaves.get(at), Blocks.AIR.defaultBlockState(), changed);
            }
            for (BlockPos at : ordered) {
                BlockState replacement = base.contains(at) ? SAPLING : Blocks.AIR.defaultBlockState();
                replace(level, at, logs.get(at), replacement, changed);
            }
            if (MeOperations.mutateMe(grid, storage, actor, wood, count, true, Actionable.MODULATE) != count
                    || MeOperations.count(storage, wood) != woodAfter) {
                throw new BridgeError(409, "spruce_me_unavailable", "ME did not retain all harvested spruce logs");
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
                MeOperations.restoreMe(storage, actor, wood, woodBefore);
                MeOperations.restoreMe(storage, actor, saplings, saplingsBefore);
            } catch (RuntimeException rollbackError) {
                failure.addSuppressed(rollbackError);
            }
            if (failure.getSuppressed().length != 0) {
                throw new IllegalStateException("Spruce harvest rollback was incomplete", failure);
            }
            throw failure;
        }
        for (ItemStack stack : leafHarvest.surplus) {
            Block.popResource(level, root, stack);
        }
        result.addProperty("meLogsAfter", woodAfter);
        result.addProperty("meSaplingsAfter", saplingsAfter);
        result.addProperty("leavesHarvested", leafHarvest.positions.size());
        result.addProperty("harvested", true);
        return result;
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

    private static LeafHarvest gatherSaplings(ServerLevel level, Map<BlockPos, BlockState> leaves, int needed) {
        List<BlockPos> positions = new ArrayList<>();
        List<ItemStack> surplus = new ArrayList<>();
        for (var entry : leaves.entrySet()) {
            if (needed == 0) {
                break;
            }
            BlockPos at = entry.getKey();
            positions.add(at);
            for (ItemStack drop : Block.getDrops(entry.getValue(), level, at, null)) {
                if (drop.is(Items.SPRUCE_SAPLING)) {
                    int used = Math.min(needed, drop.getCount());
                    needed -= used;
                    drop.shrink(used);
                }
                if (!drop.isEmpty()) {
                    surplus.add(drop);
                }
            }
        }
        if (needed != 0) {
            throw new BridgeError(409, "spruce_saplings_short", "The planned leaves did not yield enough saplings; no blocks were changed");
        }
        return new LeafHarvest(positions, surplus);
    }

    private record LeafHarvest(List<BlockPos> positions, List<ItemStack> surplus) {}

    private static Map<BlockPos, BlockState> inspectTree(ServerLevel level, BlockPos root, List<BlockPos> base) {
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
