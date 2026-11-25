package minecraft.automation.mcp;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.block.IMachineBlock;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.pattern.MultiblockShapeInfo;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
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

    static JsonObject layout(ResourceLocation id, int previewIndex, Map<BlockPos, ResourceLocation> parts) {
        return layout(registeredMultiblock(id), previewIndex, parts);
    }

    private static MultiblockMachineDefinition registeredMultiblock(ResourceLocation id) {
        if (GTRegistries.MACHINES.get(id) instanceof MultiblockMachineDefinition definition) {
            return definition;
        }
        Block block = ForgeRegistries.BLOCKS.getValue(id);
        if (block instanceof IMachineBlock machine && machine.getDefinition() instanceof MultiblockMachineDefinition definition) {
            return definition;
        }
        throw new BridgeError(404, "unknown_multiblock", "No registered GT multiblock for " + id);
    }

    static JsonObject layout(ServerLevel level, BlockPos position, int previewIndex,
                             Map<BlockPos, ResourceLocation> parts) {
        BlockState state = level.getBlockState(position);
        if (!(state.getBlock() instanceof IMachineBlock machine)
                || !(machine.getDefinition() instanceof MultiblockMachineDefinition definition)) {
            throw new BridgeError(409, "not_multiblock_controller", "The position must contain a GT multiblock controller");
        }
        JsonObject result = layout(definition, previewIndex, parts);
        var facing = machine.getRotationState().property;
        if (state.hasProperty(facing)) {
            result.addProperty("controllerFacing", state.getValue(facing).getSerializedName());
        }
        return result;
    }

    private static JsonObject layout(MultiblockMachineDefinition definition, int previewIndex,
                                     Map<BlockPos, ResourceLocation> parts) {
        var previews = definition.getMatchingShapes();
        var shape = withParts(previewPlacements(definition, previews, previewIndex), parts);
        var materialCounts = new LinkedHashMap<ResourceLocation, Integer>();
        var blocksWithoutItem = new LinkedHashMap<ResourceLocation, Integer>();
        JsonArray cells = new JsonArray();
        JsonObject result = new JsonObject();
        for (Placement placement : shape) {
            BlockPos offset = placement.offset;
            BlockState state = placement.state;
            Block block = state.getBlock();
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
            JsonObject cell = new JsonObject();
            cell.add("offset", MinecraftAutomationBridge.positionJson(offset));
            cell.addProperty("block", blockId.toString());
            cells.add(cell);
            if (offset.equals(BlockPos.ZERO) && block instanceof IMachineBlock machine) {
                var facing = machine.getRotationState().property;
                if (state.hasProperty(facing)) {
                    result.addProperty("previewFacing", state.getValue(facing).getSerializedName());
                }
            }
            if (!state.isAir()) {
                var item = block.asItem();
                if (item == Items.AIR) {
                    blocksWithoutItem.merge(blockId, 1, Integer::sum);
                } else {
                    materialCounts.merge(ForgeRegistries.ITEMS.getKey(item), 1, Integer::sum);
                }
            }
        }
        result.addProperty("structure", definition.getId().toString());
        result.addProperty("controllerBlock", ForgeRegistries.BLOCKS.getKey(definition.getBlock()).toString());
        result.addProperty("previewCount", previews.size());
        result.addProperty("previewIndex", previewIndex);
        result.addProperty("customized", !parts.isEmpty());
        result.add("materials", countsJson(materialCounts));
        if (!blocksWithoutItem.isEmpty()) {
            result.add("blocksWithoutItem", countsJson(blocksWithoutItem));
        }
        result.add("layout", cells);
        return result;
    }

    private static JsonObject countsJson(Map<ResourceLocation, Integer> counts) {
        JsonObject result = new JsonObject();
        counts.forEach((id, count) -> result.addProperty(id.toString(), count));
        return result;
    }

    static JsonObject build(ServerLevel level, BlockPos controllerPosition,
                            MeOperations.Endpoint endpoint, ResourceLocation id,
                            int previewIndex, Map<BlockPos, ResourceLocation> parts) {
        var definition = registeredMultiblock(id);
        var shape = withParts(previewPlacements(definition, definition.getMatchingShapes(), previewIndex), parts);
        for (Placement placement : shape) {
            BlockPos at = controllerPosition.offset(placement.offset);
            if (!level.isInWorldBounds(at) || !level.hasChunkAt(at)
                    || !level.getBlockState(at).is(Blocks.AIR) || !level.getEntitiesOfClass(LivingEntity.class,
                    new AABB(at), LivingEntity::isAlive).isEmpty()) {
                throw new BridgeError(409, "build_site_unavailable", "The GT multiblock site must be empty and fully loaded: " + at);
            }
        }
        var grid = endpoint.grid();
        var storage = grid.getStorageService().getInventory();
        var actor = MeOperations.playerAction(endpoint.level());
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
            JsonObject result = materials(materials, definition.getId());
            result.addProperty("previewIndex", previewIndex);
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

    private static List<Placement> previewPlacements(MultiblockMachineDefinition definition,
                                                      List<MultiblockShapeInfo> previews, int previewIndex) {
        if (previews.isEmpty()) {
            throw new BridgeError(409, "pattern_unavailable", "The GT multiblock has no registered preview");
        }
        if (previewIndex < 0 || previewIndex >= previews.size()) {
            throw new BridgeError(400, "invalid_preview_index", "Choose a preview index from 0 to " + (previews.size() - 1));
        }
        BlockInfo[][][] blocks = previews.get(previewIndex).getBlocks();
        Block controller = definition.getBlock();
        BlockPos anchor = null;
        BlockState controllerState = null;
        List<Placement> cells = new ArrayList<>();
        for (int x = 0; x < blocks.length; x++) {
            for (int y = 0; y < blocks[x].length; y++) {
                for (int z = 0; z < blocks[x][y].length; z++) {
                    BlockInfo block = blocks[x][y][z];
                    BlockState state = block == null ? Blocks.AIR.defaultBlockState() : block.getBlockState();
                    BlockPos offset = new BlockPos(x, y, z);
                    if (state.is(controller)) {
                        if (anchor != null) {
                            throw new BridgeError(409, "ambiguous_preview_controller", "The GT preview contains multiple controller blocks");
                        }
                        anchor = offset;
                        controllerState = state;
                    } else {
                        cells.add(new Placement(offset, state));
                    }
                }
            }
        }
        if (anchor == null) {
            throw new BridgeError(409, "pattern_unavailable", "The GT multiblock preview has no controller");
        }
        List<Placement> result = new ArrayList<>(cells.size() + 1);
        for (Placement cell : cells) {
            result.add(new Placement(cell.offset.subtract(anchor), cell.state));
        }
        result.add(new Placement(BlockPos.ZERO, controllerState));
        return result;
    }

    private static List<Placement> withParts(List<Placement> shape, Map<BlockPos, ResourceLocation> parts) {
        if (parts.isEmpty()) {
            return shape;
        }
        var pending = new LinkedHashMap<>(parts);
        var result = new ArrayList<Placement>(shape.size());
        for (Placement original : shape) {
            ResourceLocation id = pending.remove(original.offset);
            if (id == null) {
                result.add(original);
                continue;
            }
            if (original.offset.equals(BlockPos.ZERO) || original.state.isAir()) {
                throw new BridgeError(400, "invalid_part_offset", "Choose a non-air preview position other than the controller");
            }
            if (!ForgeRegistries.BLOCKS.containsKey(id)) {
                throw new BridgeError(400, "unknown_part", "No registered block for " + id);
            }
            Block replacement = ForgeRegistries.BLOCKS.getValue(id);
            BlockState target = replacement.defaultBlockState();
            if (target.isAir()) {
                throw new BridgeError(400, "invalid_part", "A replacement must be a non-air block");
            }
            if (replacement instanceof IMachineBlock machine) {
                var facing = machine.getRotationState().property;
                if (target.hasProperty(facing) && original.state.hasProperty(facing)) {
                    target = target.setValue(facing, original.state.getValue(facing));
                }
            }
            result.add(new Placement(original.offset, target));
        }
        if (!pending.isEmpty()) {
            throw new BridgeError(400, "invalid_part_offset", "A part offset is outside the preview");
        }
        return result;
    }

    private static Map<AEItemKey, Integer> materialCounts(List<Placement> shape) {
        Map<AEItemKey, Integer> counts = new LinkedHashMap<>();
        for (Placement cell : shape) {
            if (!cell.state.isAir()) {
                var item = cell.state.getBlock().asItem();
                if (item == Items.AIR) {
                    throw new BridgeError(409, "unavailable_build_material",
                            "No inventory item for " + ForgeRegistries.BLOCKS.getKey(cell.state.getBlock()));
                }
                counts.merge(AEItemKey.of(item), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static JsonObject materials(Map<AEItemKey, Integer> materials, ResourceLocation kind) {
        JsonObject result = new JsonObject();
        result.addProperty("structure", kind.toString());
        JsonObject list = new JsonObject();
        materials.forEach((key, count) -> list.addProperty(key.getId().toString(), count));
        result.add("materials", list);
        return result;
    }

    private record Placement(BlockPos offset, BlockState state) {}
}
