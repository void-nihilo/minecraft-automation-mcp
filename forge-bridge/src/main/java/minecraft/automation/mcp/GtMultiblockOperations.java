package minecraft.automation.mcp;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.block.IMachineBlock;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.common.data.GTMachines;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GtMultiblockOperations {
    private GtMultiblockOperations() {}

    static JsonObject layout(String kind) {
        var shape = shape(kind);
        JsonArray cells = new JsonArray();
        for (Placement placement : shape) {
            JsonObject cell = new JsonObject();
            cell.add("offset", MinecraftAutomationBridge.positionJson(placement.offset));
            cell.addProperty("block", ForgeRegistries.BLOCKS.getKey(placement.state.getBlock()).toString());
            cells.add(cell);
        }
        JsonObject result = materials(shape, kind);
        result.add("layout", cells);
        return result;
    }

    static JsonObject build(ServerLevel level, BlockPos controllerPosition,
                            ServerLevel driveLevel, BlockPos drive, String kind) {
        var shape = shape(kind);
        for (Placement placement : shape) {
            BlockPos at = controllerPosition.offset(placement.offset);
            if (!level.isInWorldBounds(at) || !level.hasChunkAt(at)
                    || !level.getBlockState(at).is(Blocks.AIR) || !level.getEntitiesOfClass(LivingEntity.class,
                    new AABB(at), LivingEntity::isAlive).isEmpty()) {
                throw new BridgeError(409, "build_site_unavailable", "The GT multiblock site must be empty and fully loaded: " + at);
            }
        }
        var grid = MeOperations.gridAt(driveLevel, drive);
        var storage = grid.getStorageService().getInventory();
        var actor = MeOperations.playerAction(driveLevel);
        var materials = materialCounts(shape);
        Map<AEItemKey, Long> balances = new LinkedHashMap<>();
        for (var entry : materials.entrySet()) {
            AEItemKey key = entry.getKey();
            int amount = entry.getValue();
            balances.put(key, MeOperations.count(storage, key));
            if (MeOperations.mutateMe(grid, storage, actor, key, amount, false, Actionable.SIMULATE) != amount) {
                throw new BridgeError(409, "missing_material", "ME cannot supply the full quantity of " + key.getId());
            }
        }

        List<BlockPos> placed = new ArrayList<>();
        try {
            for (var entry : materials.entrySet()) {
                var key = entry.getKey();
                int amount = entry.getValue();
                if (MeOperations.mutateMe(grid, storage, actor, key, amount, false, Actionable.MODULATE) != amount
                        || MeOperations.count(storage, key) != balances.get(key) - amount) {
                    throw new BridgeError(409, "material_withdrawal_failed", "ME did not supply " + key.getId());
                }
            }
            for (Placement placement : shape) {
                if (placement.state.isAir()) {
                    continue;
                }
                BlockPos at = controllerPosition.offset(placement.offset);
                placed.add(at);
                if (!level.setBlock(at, placement.state, Block.UPDATE_ALL) || !level.getBlockState(at).equals(placement.state)) {
                    throw new BridgeError(409, "placement_failed", "GT multiblock block did not retain its state: " + at);
                }
            }
            if (!(MetaMachine.getMachine(level, controllerPosition) instanceof MultiblockControllerMachine controller)
                    || !controller.checkPatternWithLock()) {
                throw new BridgeError(409, "pattern_mismatch", "GT rejected the placed multiblock pattern");
            }
            JsonObject result = materials(shape, kind);
            result.addProperty("placed", placed.size());
            result.addProperty("patternValid", true);
            result.add("controller", MinecraftAutomationBridge.positionJson(controllerPosition));
            return result;
        } catch (RuntimeException failure) {
            try {
                for (int i = placed.size() - 1; i >= 0; i--) {
                    BlockPos at = placed.get(i);
                    if (!level.getBlockState(at).is(Blocks.AIR)) {
                        level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    if (!level.getBlockState(at).is(Blocks.AIR)) {
                        throw new IllegalStateException("Could not restore GT multiblock site " + at);
                    }
                }
                for (var entry : balances.entrySet()) {
                    MeOperations.restoreMe(storage, actor, entry.getKey(), entry.getValue());
                }
            } catch (RuntimeException rollback) {
                failure.addSuppressed(rollback);
                throw new IllegalStateException("GT multiblock build rollback did not finish", failure);
            }
            throw failure;
        }
    }

    static JsonObject prepareTestMaterials(ServerLevel level, BlockPos drive, String kind) {
        var shape = shape(kind);
        GtProductionTestFixtures.prepareStock(level, drive, materialCounts(shape),
                "ME did not accept the test material ");
        return materials(shape, kind);
    }

    private static List<Placement> shape(String kind) {
        MultiblockMachineDefinition definition = switch (kind) {
            case "gtceu:coke_oven" -> GTMachines.COKE_OVEN;
            case "gtceu:electric_blast_furnace" -> GTMachines.ELECTRIC_BLAST_FURNACE;
            case "gtceu:large_chemical_reactor" -> GTMachines.LARGE_CHEMICAL_REACTOR;
            case "gtceu:pyrolyse_oven" -> GTMachines.PYROLYSE_OVEN;
            default -> throw new BridgeError(400, "unknown_multiblock", "Unsupported GT multiblock: " + kind);
        };
        BlockInfo[][][] blocks = definition.getMatchingShapes().get(0).getBlocks();
        Block controller = definition.getBlock();
        BlockPos anchor = null;
        List<Placement> cells = new ArrayList<>();
        for (int x = 0; x < blocks.length; x++) {
            for (int y = 0; y < blocks[x].length; y++) {
                for (int z = 0; z < blocks[x][y].length; z++) {
                    BlockInfo block = blocks[x][y][z];
                    BlockState state = block == null ? Blocks.AIR.defaultBlockState() : block.getBlockState();
                    if (definition == GTMachines.PYROLYSE_OVEN
                            && state.is(GTMachines.ENERGY_INPUT_HATCH[GTValues.LV].getBlock())) {
                        Block hatch = GTMachines.ENERGY_INPUT_HATCH_16A[GTValues.EV].getBlock();
                        state = hatch.defaultBlockState().setValue(((IMachineBlock) hatch).getRotationState().property, Direction.SOUTH);
                    }
                    BlockPos offset = new BlockPos(x, y, z);
                    if (state.is(controller)) {
                        anchor = offset;
                    }
                    cells.add(new Placement(offset, state));
                }
            }
        }
        if (anchor == null) {
            throw new BridgeError(409, "pattern_unavailable", "The GT multiblock preview has no controller");
        }
        List<Placement> result = new ArrayList<>();
        for (Placement cell : cells) {
            if (!cell.state.is(controller)) {
                result.add(new Placement(cell.offset.subtract(anchor), cell.state));
            }
        }
        result.add(new Placement(BlockPos.ZERO, controllerState(cells, controller)));
        return result;
    }

    private static BlockState controllerState(List<Placement> cells, Block controller) {
        return cells.stream().map(Placement::state).filter(state -> state.is(controller))
                .findFirst().orElseThrow();
    }

    private static Map<AEItemKey, Integer> materialCounts(List<Placement> shape) {
        Map<AEItemKey, Integer> counts = new LinkedHashMap<>();
        for (Placement cell : shape) {
            if (!cell.state.isAir()) {
                var key = AEItemKey.of(cell.state.getBlock().asItem());
                if (key == null) {
                    throw new BridgeError(409, "pattern_unavailable", "The preview includes a block without an item");
                }
                counts.merge(key, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static JsonObject materials(List<Placement> shape, String kind) {
        JsonObject result = new JsonObject();
        result.addProperty("structure", kind);
        JsonObject list = new JsonObject();
        materialCounts(shape).forEach((key, count) -> list.addProperty(key.getId().toString(), count));
        result.add("materials", list);
        return result;
    }

    private record Placement(BlockPos offset, BlockState state) {}
}
