package minecraft.automation.mcp;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import minecraft.automation.mcp.ChestOperations.SlotChange;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TreeFarmOperations {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHECK_INTERVAL_TICKS = 100;
    private static final int RADIUS = 5;
    private static final int HEIGHT = 16;
    private static final int MAX_LOGS = 128;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private Plot plot;
    private String state = "idle";
    private String problem;
    private String detail;
    private long treesHarvested;
    private long logsStored;
    private long saplingsPlanted;

    JsonObject start(ServerLevel level, BlockPos root, BlockPos supply, BlockPos output, String treeName) {
        Tree tree = switch (treeName) {
            case "oak" -> new Tree(treeName, Blocks.OAK_SAPLING, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
            case "birch" -> new Tree(treeName, Blocks.BIRCH_SAPLING, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
            default -> throw new IllegalArgumentException("Tree must be oak or birch");
        };
        Plot requested = new Plot(level, root, supply, output, tree);
        if (requested.equals(plot)) {
            return status();
        }
        if (plot != null) {
            throw blocked("farm_exists", "Stop the existing tree farm before starting another");
        }
        if (supply.equals(output) || insideGuard(root, supply) || insideGuard(root, output)) {
            throw blocked("invalid_chests", "Use two different chest positions outside the tree plot and its border");
        }
        checkArea(requested);
        ChestOperations.chestAt(level, supply);
        ChestOperations.chestAt(level, output);
        checkRoot(requested);
        treeLogs(requested);
        plot = requested;
        treesHarvested = 0;
        logsStored = 0;
        saplingsPlanted = 0;
        state = "waiting";
        return status();
    }

    JsonObject status() {
        JsonObject result = new JsonObject();
        result.addProperty("active", plot != null && !state.equals("error"));
        result.addProperty("state", state);
        if (plot != null) {
            result.addProperty("tree", plot.tree.name);
            result.addProperty("dimension", plot.level.dimension().location().toString());
            result.add("position", MinecraftAutomationBridge.positionJson(plot.root));
            result.add("supply", MinecraftAutomationBridge.positionJson(plot.supply));
            result.add("output", MinecraftAutomationBridge.positionJson(plot.output));
            result.addProperty("treesHarvested", treesHarvested);
            result.addProperty("logsStored", logsStored);
            result.addProperty("saplingsPlanted", saplingsPlanted);
        }
        if (problem != null) {
            result.addProperty("code", problem);
            result.addProperty("message", detail);
        }
        return result;
    }

    JsonObject stop() {
        JsonObject result = status();
        if (plot != null) {
            result.addProperty("active", false);
            result.addProperty("state", "stopped");
        }
        plot = null;
        state = "idle";
        problem = null;
        detail = null;
        return result;
    }

    JsonObject verify(ServerLevel level, BlockPos root) {
        if (plot != null) {
            throw blocked("farm_exists", "Stop the existing tree farm before running the verification");
        }
        BlockPos supply = root.east(RADIUS + 2);
        BlockPos output = supply.east(2);
        Tree tree = new Tree("oak", Blocks.OAK_SAPLING, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
        Plot test = new Plot(level, root, supply, output, tree);
        checkArea(test);
        treeLogs(test);

        Map<BlockPos, BlockState> fixture = new LinkedHashMap<>();
        if (level.isEmptyBlock(root.below())) {
            fixture.put(root.below(), Blocks.DIRT.defaultBlockState());
        } else {
            checkRoot(test);
        }
        int logCount = 4;
        for (int height = 0; height < logCount; height++) {
            fixture.put(root.above(height), tree.log.defaultBlockState());
        }
        fixture.put(root.above(logCount - 1).north(), tree.leaves.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1));
        fixture.put(supply, Blocks.CHEST.defaultBlockState());
        fixture.put(output, Blocks.CHEST.defaultBlockState());

        Map<BlockPos, BlockState> original = new LinkedHashMap<>();
        for (BlockPos position : fixture.keySet()) {
            BlockState before = level.getBlockState(position);
            if (!before.isAir() || level.getBlockEntity(position) != null) {
                throw blocked("test_site_occupied", "Verification requires empty space at " + position);
            }
            original.put(position, before);
        }
        List<BlockPos> attempted = new ArrayList<>();
        JsonObject result;
        try {
            for (Map.Entry<BlockPos, BlockState> entry : fixture.entrySet()) {
                BlockPos position = entry.getKey();
                attempted.add(position);
                replace(level, position, original.get(position), entry.getValue());
            }
            ChestBlockEntity source = ChestOperations.chestAt(level, supply);
            source.setItem(0, new ItemStack(tree.sapling));
            start(level, root, supply, output, tree.name);
            runCycle(test);
            ItemStack harvested = ChestOperations.chestAt(level, output).getItem(0);
            if (!level.getBlockState(root).is(tree.sapling) || !source.getItem(0).isEmpty()
                    || !harvested.is(tree.log.asItem()) || harvested.getCount() != logCount
                    || fixture.keySet().stream().filter(position -> fixture.get(position).is(tree.log))
                            .filter(position -> !position.equals(root)).anyMatch(position -> !level.isEmptyBlock(position))) {
                throw new IllegalStateException("Tree farm did not harvest and replant the test tree");
            }
            result = status();
        } finally {
            stop();
            boolean restored = true;
            for (int index = attempted.size() - 1; index >= 0; index--) {
                BlockPos position = attempted.get(index);
                if (level.getBlockEntity(position) instanceof ChestBlockEntity chest) {
                    chest.clearContent();
                }
                BlockState stateAtStart = original.get(position);
                if (!level.getBlockState(position).equals(stateAtStart)) {
                    level.setBlock(position, stateAtStart, Block.UPDATE_ALL);
                }
                restored &= level.getBlockState(position).equals(stateAtStart) && level.getBlockEntity(position) == null;
            }
            if (!restored) {
                throw new IllegalStateException("Tree farm test site was not fully restored");
            }
        }
        result.addProperty("active", false);
        result.addProperty("state", "verified");
        result.addProperty("siteRestored", true);
        return result;
    }

    void tick(MinecraftServer server) {
        if (plot == null || state.equals("error") || server.getTickCount() % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        problem = null;
        detail = null;
        try {
            runCycle(plot);
        } catch (BridgeError expected) {
            state = "blocked";
            problem = expected.code();
            detail = expected.getMessage();
        } catch (RuntimeException failure) {
            LOGGER.error("Minecraft Automation MCP tree farm stopped at {} after an unexpected error", plot.root, failure);
            state = "error";
            problem = "farm_error";
            detail = "Inspect the tree plot and chests before stopping and restarting the farm";
        }
    }

    private void runCycle(Plot plot) {
        checkArea(plot);
        BlockState rootState = checkRoot(plot);
        if (rootState.is(plot.tree.sapling)) {
            state = "growing";
            return;
        }

        Map<BlockPos, BlockState> logs = treeLogs(plot);
        List<SlotChange> changes = new ArrayList<>();
        ChestBlockEntity supply = ChestOperations.chestAt(plot.level, plot.supply);
        ChestBlockEntity output = ChestOperations.chestAt(plot.level, plot.output);
        changes.add(reserveSapling(supply, plot.tree));
        if (!logs.isEmpty()) {
            ItemStack wood = new ItemStack(plot.tree.log);
            int remaining = ChestOperations.planInsertions(output, wood, logs.size(), false, changes);
            remaining = ChestOperations.planInsertions(output, wood, remaining, true, changes);
            if (remaining != 0) {
                throw blocked("output_full", "The log chest needs room for the whole tree");
            }
        }

        harvestAndPlant(plot, rootState, logs, changes);
        saplingsPlanted++;
        if (!logs.isEmpty()) {
            treesHarvested++;
            logsStored += logs.size();
        }
        state = "growing";
    }

    private static void checkArea(Plot plot) {
        BlockPos min = plot.root.offset(-RADIUS - 1, -1, -RADIUS - 1);
        BlockPos max = plot.root.offset(RADIUS + 1, HEIGHT, RADIUS + 1);
        for (BlockPos position : List.of(min, max, plot.supply, plot.output)) {
            if (!plot.level.isInWorldBounds(position) || !plot.level.getWorldBorder().isWithinBounds(position)) {
                throw blocked("farm_out_of_bounds", "The tree plot and both chests must be inside the world bounds");
            }
            if (!plot.level.hasChunkAt(position)) {
                throw blocked("chunk_not_loaded", "The full tree plot and both chests must be loaded");
            }
        }
        // The guarded plot spans at most two chunks per axis. Check the two other corners too.
        if (!plot.level.hasChunkAt(new BlockPos(min.getX(), min.getY(), max.getZ()))
                || !plot.level.hasChunkAt(new BlockPos(max.getX(), min.getY(), min.getZ()))) {
            throw blocked("chunk_not_loaded", "The full tree plot and both chests must be loaded");
        }
    }

    private static boolean insideGuard(BlockPos root, BlockPos position) {
        return Math.abs((long) position.getX() - root.getX()) <= RADIUS + 1
                && Math.abs((long) position.getZ() - root.getZ()) <= RADIUS + 1
                && (long) position.getY() - root.getY() >= -1
                && (long) position.getY() - root.getY() <= HEIGHT;
    }

    private static BlockState checkRoot(Plot plot) {
        BlockState rootState = plot.level.getBlockState(plot.root);
        if (!rootState.isAir() && !rootState.is(plot.tree.sapling) && !rootState.is(plot.tree.log)) {
            throw blocked("plot_obstructed", "The planting position must be air, the selected sapling, or its trunk");
        }
        if (!plot.tree.sapling.defaultBlockState().canSurvive(plot.level, plot.root)) {
            throw blocked("invalid_soil", "The planting position needs soil suitable for the selected sapling");
        }
        return rootState;
    }

    private static Map<BlockPos, BlockState> treeLogs(Plot plot) {
        Map<BlockPos, BlockState> logs = new HashMap<>();
        for (BlockPos position : BlockPos.betweenClosed(
                plot.root.offset(-RADIUS - 1, -1, -RADIUS - 1),
                plot.root.offset(RADIUS + 1, HEIGHT, RADIUS + 1))) {
            BlockState block = plot.level.getBlockState(position);
            if (!block.is(plot.tree.log) && !block.is(BlockTags.LOGS)) {
                continue;
            }
            if (!block.is(plot.tree.log) || Math.abs(position.getX() - plot.root.getX()) > RADIUS
                    || Math.abs(position.getZ() - plot.root.getZ()) > RADIUS
                    || position.getY() < plot.root.getY() || position.getY() >= plot.root.getY() + HEIGHT) {
                throw blocked("plot_not_isolated", "Other logs or logs on the plot border block harvesting");
            }
            logs.put(position.immutable(), block);
            if (logs.size() > MAX_LOGS) {
                throw blocked("tree_too_large", "The tree exceeds the farm's harvest limit");
            }
        }
        if (logs.isEmpty()) {
            return logs;
        }
        if (logs.size() < 4 || !verticalLog(logs.get(plot.root)) || !verticalLog(logs.get(plot.root.above()))) {
            throw blocked("not_a_tree", "No complete matching trunk at the planting position");
        }

        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        visited.add(plot.root);
        pending.add(plot.root);
        boolean naturalLeaves = false;
        while (!pending.isEmpty()) {
            BlockPos log = pending.removeFirst();
            // A large oak may join some of its branches diagonally.
            for (BlockPos neighbor : BlockPos.betweenClosed(log.offset(-1, -1, -1), log.offset(1, 1, 1))) {
                if (logs.containsKey(neighbor) && visited.add(neighbor.immutable())) {
                    pending.addLast(neighbor.immutable());
                }
            }
            if (!naturalLeaves && log.getY() > plot.root.getY() + 1) {
                for (Direction direction : Direction.values()) {
                    BlockState leaf = plot.level.getBlockState(log.relative(direction));
                    if (leaf.is(plot.tree.leaves) && !leaf.getValue(LeavesBlock.PERSISTENT)) {
                        naturalLeaves = true;
                        break;
                    }
                }
            }
        }
        if (visited.size() != logs.size()) {
            throw blocked("plot_not_isolated", "Separate logs in the plot block harvesting");
        }
        if (!naturalLeaves) {
            throw blocked("not_a_tree", "The trunk needs a matching natural canopy");
        }
        return logs;
    }

    private static boolean verticalLog(BlockState block) {
        return block != null && block.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y;
    }

    private static SlotChange reserveSapling(ChestBlockEntity supply, Tree tree) {
        for (int slot = 0; slot < supply.getContainerSize(); slot++) {
            ItemStack stack = supply.getItem(slot);
            if (stack.is(tree.sapling.asItem())) {
                return new SlotChange(supply, slot, stack.copy(), stack.copyWithCount(stack.getCount() - 1));
            }
        }
        throw blocked("no_saplings", "Add matching saplings to the supply chest");
    }

    private static void harvestAndPlant(Plot plot, BlockState rootState, Map<BlockPos, BlockState> logs,
                                        List<SlotChange> changes) {
        List<BlockPos> cleared = new ArrayList<>();
        BlockState sapling = plot.tree.sapling.defaultBlockState();
        try {
            List<BlockPos> branches = logs.keySet().stream().filter(position -> !position.equals(plot.root))
                    .sorted(Comparator.comparingInt((BlockPos position) -> position.getY()).reversed()).toList();
            for (BlockPos position : branches) {
                cleared.add(position);
                replace(plot.level, position, logs.get(position), AIR);
            }
            cleared.add(plot.root);
            replace(plot.level, plot.root, rootState, sapling);
            if (!ChestOperations.matchesBefore(changes)) {
                throw blocked("chest_changed", "A chest slot changed while planting");
            }
            ChestOperations.applyChanges(changes);
        } catch (RuntimeException failure) {
            for (int index = cleared.size() - 1; index >= 0; index--) {
                BlockPos position = cleared.get(index);
                BlockState original = position.equals(plot.root) ? rootState : logs.get(position);
                try {
                    if (!plot.level.getBlockState(position).equals(original)) {
                        plot.level.setBlock(position, original, Block.UPDATE_ALL);
                        if (!plot.level.getBlockState(position).equals(original)) {
                            throw new IllegalStateException("Failed to restore tree plot at " + position);
                        }
                    }
                } catch (RuntimeException rollbackError) {
                    failure.addSuppressed(rollbackError);
                }
            }
            if (failure.getSuppressed().length != 0) {
                throw new IllegalStateException("Tree farm rollback did not finish", failure);
            }
            throw failure;
        }
    }

    private static void replace(ServerLevel level, BlockPos position, BlockState expected, BlockState replacement) {
        if (!level.getBlockState(position).equals(expected)
                || !level.setBlock(position, replacement, Block.UPDATE_ALL)
                || !level.getBlockState(position).equals(replacement)) {
            throw blocked("plot_changed", "The game did not accept a planned tree block change");
        }
    }

    private static BridgeError blocked(String code, String message) {
        return new BridgeError(409, code, message);
    }

    private record Tree(String name, Block sapling, Block log, Block leaves) {}

    private record Plot(ServerLevel level, BlockPos root, BlockPos supply, BlockPos output, Tree tree) {}
}
