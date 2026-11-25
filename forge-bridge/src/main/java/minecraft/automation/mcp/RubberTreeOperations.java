package minecraft.automation.mcp;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import com.google.gson.JsonObject;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.Map;

final class RubberTreeOperations {
    private static final ResourceLocation RUBBER_LOG = ResourceLocation.tryParse("gtceu:rubber_log");
    private static final int MAX_HEIGHT = 16;
    private static final int CHANGE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private RubberTreeOperations() {}

    static JsonObject harvest(ServerLevel level, BlockPos root, MeOperations.Endpoint endpoint) {
        if (!level.isInWorldBounds(root) || !level.hasChunkAt(root)
                || !level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)
                || !level.getBlockState(root.below()).is(net.minecraft.tags.BlockTags.DIRT)) {
            throw new BridgeError(409, "rubber_plot_unavailable", "Provide a loaded rubber tree growing on dirt with block drops enabled");
        }
        Map<BlockPos, BlockState> logs = new LinkedHashMap<>();
        Map<AEItemKey, Integer> drops = new LinkedHashMap<>();
        BlockPos at = root;
        while (naturalRubberLog(level.getBlockState(at))) {
            if (logs.size() == MAX_HEIGHT) {
                throw new BridgeError(409, "rubber_trunk_too_tall", "The natural rubber trunk is taller than allowed");
            }
            BlockState state = level.getBlockState(at);
            logs.put(at, state);
            for (var drop : Block.getDrops(state, level, at, null)) {
                AEItemKey key = AEItemKey.of(drop);
                if (key != null) {
                    drops.merge(key, drop.getCount(), Math::addExact);
                }
            }
            at = at.above();
        }
        if (logs.size() < 2 || drops.isEmpty()) {
            throw new BridgeError(409, "rubber_tree_missing", "The position must be the base of a natural rubber tree");
        }

        var grid = endpoint.grid();
        var storage = grid.getStorageService().getInventory();
        var actor = MeOperations.playerAction(endpoint.level());
        Map<AEItemKey, Long> balances = new LinkedHashMap<>();
        for (var drop : drops.entrySet()) {
            balances.put(drop.getKey(), MeOperations.count(storage, drop.getKey()));
            if (MeOperations.mutateMe(grid, storage, actor, drop.getKey(), drop.getValue(), true, Actionable.SIMULATE)
                    != drop.getValue()) {
                throw new BridgeError(409, "rubber_me_full", "ME cannot accept all rubber tree drops");
            }
        }

        try {
            for (BlockPos pos : logs.keySet()) {
                if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), CHANGE_FLAGS)) {
                    throw new BridgeError(409, "rubber_trunk_changed", "Could not harvest the natural rubber trunk");
                }
            }
            for (var drop : drops.entrySet()) {
                AEItemKey key = drop.getKey();
                int amount = drop.getValue();
                long before = balances.get(key);
                long expected = before > Long.MAX_VALUE - amount ? Long.MAX_VALUE : before + amount;
                if (MeOperations.mutateMe(grid, storage, actor, key, amount, true, Actionable.MODULATE) != amount
                        || MeOperations.count(storage, key) != expected) {
                    throw new BridgeError(409, "rubber_me_full", "ME did not retain all rubber tree drops");
                }
            }
        } catch (RuntimeException failure) {
            try {
                logs.forEach((pos, state) -> level.setBlock(pos, state, CHANGE_FLAGS));
                balances.forEach((key, before) -> MeOperations.restoreMe(storage, actor, key, before));
            } catch (RuntimeException rollback) {
                failure.addSuppressed(rollback);
                throw new IllegalStateException("Rubber tree harvest rollback did not finish", failure);
            }
            throw failure;
        }

        logs.forEach((pos, state) -> level.updateNeighborsAt(pos, state.getBlock()));
        JsonObject result = new JsonObject();
        result.addProperty("logsHarvested", logs.size());
        JsonObject stored = new JsonObject();
        drops.forEach((key, amount) -> stored.addProperty(key.getId().toString(), amount));
        result.add("stored", stored);
        return result;
    }

    private static boolean naturalRubberLog(BlockState state) {
        return RUBBER_LOG.equals(ForgeRegistries.BLOCKS.getKey(state.getBlock()))
                && state.getValues().entrySet().stream()
                        .anyMatch(entry -> entry.getKey().getName().equals("natural") && Boolean.TRUE.equals(entry.getValue()));
    }
}
