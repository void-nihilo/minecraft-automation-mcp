package minecraft.automation.mcp;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.SimpleTieredMachine;
import com.gregtechceu.gtceu.api.machine.WorkableTieredMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.steam.SimpleSteamMachine;
import com.gregtechceu.gtceu.api.machine.steam.SteamBoilerMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.common.machine.steam.SteamSolidBoilerMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.EnergyHatchPartMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.FluidHatchPartMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.ItemBusPartMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.MaintenanceHatchPartMachine;
import com.gregtechceu.gtceu.config.ConfigHolder;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.common.machine.multiblock.primitive.PrimitiveWorkableMachine;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;

final class GtMachineOperations {
    private GtMachineOperations() {}

    static JsonObject inspect(ServerLevel level, BlockPos position) {
        MetaMachine machine = MetaMachine.getMachine(level, position);
        if (machine == null) {
            throw new BridgeError(409, "not_gt_machine", "The position must contain a GT machine");
        }
        return inspect(level, position, machine, true);
    }

    static JsonObject nativeStatus(ServerLevel level, BlockPos position) {
        MetaMachine machine = MetaMachine.getMachine(level, position);
        return machine == null ? null : inspect(level, position, machine, false);
    }

    private static JsonObject inspect(ServerLevel level, BlockPos position, MetaMachine machine, boolean includeInventories) {
        JsonObject result = new JsonObject();
        result.addProperty("block", ForgeRegistries.BLOCKS.getKey(level.getBlockState(position).getBlock()).toString());
        result.addProperty("facing", machine.getFrontFacing().getSerializedName());
        result.add("position", MinecraftAutomationBridge.positionJson(position));
        if (machine instanceof MultiblockControllerMachine controller) {
            result.addProperty("formed", controller.isFormed());
            JsonArray parts = new JsonArray();
            for (var part : controller.getParts()) {
                BlockPos partPosition = part.self().getPos();
                JsonObject entry = new JsonObject();
                entry.add("position", MinecraftAutomationBridge.positionJson(partPosition));
                entry.addProperty("block", ForgeRegistries.BLOCKS.getKey(level.getBlockState(partPosition).getBlock()).toString());
                parts.add(entry);
            }
            result.add("parts", parts);
        }
        if (machine instanceof WorkableTieredMachine workable) {
            result.addProperty("tier", workable.getTier());
            describeEnergy(result, workable.energyContainer);
        }
        if (machine instanceof WorkableElectricMultiblockMachine electric && electric.isFormed()) {
            describeEnergy(result, electric.getEnergyContainer());
        }
        if (machine instanceof EnergyHatchPartMachine hatch) {
            describeEnergy(result, hatch.energyContainer);
        }
        if (includeInventories && machine instanceof ItemBusPartMachine bus) {
            result.add("items", items(bus.getInventory()));
        }
        if (includeInventories && machine instanceof FluidHatchPartMachine hatch) {
            result.add("fluids", fluids(hatch.tank));
        }
        if (machine instanceof MaintenanceHatchPartMachine hatch) {
            result.addProperty("maintenanceProblems", hatch.getNumMaintenanceProblems());
            if (includeInventories) {
                result.add("items", items(maintenanceInventory(hatch)));
            }
        }
                ProcessBuffers buffers = buffersFor(machine);
        if (buffers != null) {
            describeRecipe(result, buffers.logic, buffers.recipeType);
            if (includeInventories) {
                result.add("itemInputs", items(buffers.inputItems));
                result.add("itemOutputs", items(buffers.outputItems));
                result.add("fluidInputs", fluids(buffers.inputFluids));
                result.add("fluidOutputs", fluids(buffers.outputFluids));
            }
        } else if (machine instanceof WorkableMultiblockMachine multi) {
            describeRecipe(result, multi.getRecipeLogic(), multi.getRecipeType());
        }
        if (machine instanceof SimpleTieredMachine simple) {
            result.addProperty("autoOutputItems", simple.isAutoOutputItems());
            result.addProperty("autoOutputFluids", simple.isAutoOutputFluids());
        }
        if (machine instanceof SteamBoilerMachine boiler) {
            result.addProperty("dry", boiler.isHasNoWater());
        }
        NotifiableItemStackHandler circuitInventory = circuits(machine);
        if (circuitInventory != null && circuitInventory.getSlots() > 0) {
            ItemStack circuit = circuitInventory.getStackInSlot(0);
            if (!circuit.isEmpty()) {
                result.addProperty("circuit", IntCircuitBehaviour.getCircuitConfiguration(circuit));
            }
        }
                return result;
    }

    private static void describeEnergy(JsonObject result, IEnergyContainer energy) {
        result.addProperty("energy", energy.getEnergyStored());
        result.addProperty("energyCapacity", energy.getEnergyCapacity());
        result.addProperty("inputVoltage", energy.getInputVoltage());
    }

    private static void describeRecipe(JsonObject result, RecipeLogic logic, GTRecipeType recipeType) {
        result.addProperty("status", logic.getStatus().name());
        result.addProperty("workingEnabled", logic.isWorkingEnabled());
        result.addProperty("working", logic.isWorking());
        result.addProperty("progressTicks", logic.getProgress());
        result.addProperty("maxProgressTicks", logic.getMaxProgress());
        result.addProperty("recipeType", recipeType.registryName.toString());
        var recipe = logic.getLastRecipe();
        if (recipe != null) {
            result.addProperty("recipe", recipe.id.toString());
            result.addProperty("recipeDurationTicks", recipe.duration);
            long eut = recipe.getTickInputContents(EURecipeCapability.CAP).stream()
                    .mapToLong(content -> ((Number) content.getContent()).longValue()).sum();
            result.addProperty("recipeEUPerTick", eut);
        }
    }

    static JsonObject configure(ServerLevel level, BlockPos position, Boolean enabled, Integer circuit) {
        MetaMachine machine = MetaMachine.getMachine(level, position);
        if (enabled == null && circuit == null) {
            throw new BridgeError(400, "empty_settings", "Specify workingEnabled or circuit");
        }
        RecipeLogic logic = null;
        if (enabled != null) {
            if (machine instanceof WorkableMultiblockMachine multi && multi.isFormed()) {
                logic = multi.getRecipeLogic();
            } else {
                logic = requireBuffers(machine).logic;
            }
        }
        if (circuit != null) {
            NotifiableItemStackHandler inventory = circuits(machine);
            if (inventory == null || inventory.getSlots() == 0 || circuit < -1 || circuit > IntCircuitBehaviour.CIRCUIT_MAX) {
                throw new BridgeError(409, "unsupported_circuit", "This machine or circuit setting is unavailable");
            }
            if (ConfigHolder.INSTANCE.machines.ghostCircuit) {
                inventory.setStackInSlot(0, circuit < 0 ? ItemStack.EMPTY : IntCircuitBehaviour.stack(circuit));
            } else {
                ItemStack realCircuit = inventory.getStackInSlot(0).copy();
                if (circuit < 0 || !IntCircuitBehaviour.isIntegratedCircuit(realCircuit)) {
                    throw new BridgeError(409, "physical_circuit_required", "Place a physical integrated circuit first");
                }
                IntCircuitBehaviour.setCircuitConfiguration(realCircuit, circuit);
                inventory.setStackInSlot(0, realCircuit);
            }
        }
        if (logic != null) {
            logic.setWorkingEnabled(enabled);
        }
        return inspect(level, position);
    }

    private static NotifiableItemStackHandler circuits(MetaMachine machine) {
        if (machine instanceof SimpleTieredMachine simple) {
            return simple.getCircuitInventory();
        }
        if (machine instanceof ItemBusPartMachine bus) {
            return bus.getCircuitInventory();
        }
        if (machine instanceof FluidHatchPartMachine hatch) {
            return hatch.getCircuitInventory();
        }
        return null;
    }

    private static NotifiableItemStackHandler maintenanceInventory(MaintenanceHatchPartMachine hatch) {
        return hatch.getTraits().stream().filter(NotifiableItemStackHandler.class::isInstance)
                .map(NotifiableItemStackHandler.class::cast).findFirst().orElseThrow(
                        () -> new BridgeError(409, "maintenance_unavailable", "The maintenance hatch has no item slot"));
    }

    private static ProcessBuffers requireBuffers(MetaMachine machine) {
        if (machine instanceof MultiblockControllerMachine controller && !controller.isFormed()) {
            throw new BridgeError(409, "multiblock_unformed", "The GT multiblock is not formed");
        }
        ProcessBuffers buffers = buffersFor(machine);
        if (buffers == null) {
            throw new BridgeError(409, "not_supported_machine", "The machine does not expose supported process buffers");
        }
        return buffers;
    }

    private static ProcessBuffers buffersFor(MetaMachine machine) {
        if (machine instanceof WorkableTieredMachine workable) {
            return new ProcessBuffers(workable.getRecipeLogic(), workable.getRecipeType(),
                    workable.importItems, workable.exportItems, workable.importFluids, workable.exportFluids);
        }
        if (machine instanceof PrimitiveWorkableMachine primitive) {
            return new ProcessBuffers(primitive.getRecipeLogic(), primitive.getRecipeType(),
                    primitive.importItems, primitive.exportItems, primitive.importFluids, primitive.exportFluids);
        }
        if (machine instanceof SteamSolidBoilerMachine boiler) {
            return new ProcessBuffers(boiler.getRecipeLogic(), boiler.getRecipeType(),
                    boiler.fuelHandler, boiler.ashHandler, boiler.waterTank, boiler.steamTank);
        }
        if (machine instanceof SimpleSteamMachine steam) {
            return new ProcessBuffers(steam.getRecipeLogic(), steam.getRecipeType(),
                    steam.importItems, steam.exportItems, steam.steamTank, null);
        }
        return null;
    }

    private static JsonArray items(IItemTransfer buffer) {
        JsonArray items = new JsonArray();
        for (int slot = 0; slot < buffer.getSlots(); slot++) {
            ItemStack stack = buffer.getStackInSlot(slot);
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slot);
            entry.addProperty("count", stack.getCount());
            if (!stack.isEmpty()) {
                entry.addProperty("item", ForgeRegistries.ITEMS.getKey(stack.getItem()).toString());
                entry.addProperty("stackToken", ItemHandlerOperations.stackToken(stack));
            }
            items.add(entry);
        }
        return items;
    }

    private static JsonArray fluids(NotifiableFluidTank buffer) {
        JsonArray fluids = new JsonArray();
        if (buffer == null) {
            return fluids;
        }
        for (int tank = 0; tank < buffer.getTanks(); tank++) {
            var stack = buffer.getFluidInTank(tank);
            JsonObject entry = new JsonObject();
            entry.addProperty("tank", tank);
            entry.addProperty("amount", stack.getAmount());
            entry.addProperty("capacity", buffer.getTankCapacity(tank));
            if (!stack.isEmpty()) {
                entry.addProperty("fluid", ForgeRegistries.FLUIDS.getKey(stack.getFluid()).toString());
                entry.addProperty("fluidToken", machineFluidToken(stack));
            }
            fluids.add(entry);
        }
        return fluids;
    }

    static String machineFluidToken(com.lowdragmc.lowdraglib.side.fluid.FluidStack stack) {
        return Hashing.sha256().hashString(stack.saveToTag(new CompoundTag()).toString(),
                StandardCharsets.UTF_8).toString();
    }

    record ProcessBuffers(RecipeLogic logic, GTRecipeType recipeType, IItemTransfer inputItems,
                          IItemTransfer outputItems, NotifiableFluidTank inputFluids, NotifiableFluidTank outputFluids) {}

    static JsonObject serviceMaintenance(ServerLevel level, BlockPos position, MeOperations.Endpoint endpoint, String expectedToken) {
        if (!(MetaMachine.getMachine(level, position) instanceof MaintenanceHatchPartMachine hatch)) {
            throw new BridgeError(409, "not_maintenance_hatch", "The position must contain a GT maintenance hatch");
        }
        if (!hatch.hasMaintenanceProblems()) {
            JsonObject result = inspect(level, position);
            result.addProperty("tapeUsed", 0);
            return result;
        }
        var grid = endpoint.grid();
        var actor = MeOperations.playerAction(endpoint.level());
        var storage = grid.getStorageService().getInventory();
        var tape = MeOperations.resolveMeStack(storage, AEItemKey.class, ResourceLocation.tryParse("gtceu:duct_tape"), expectedToken);
        long stock = MeOperations.count(storage, tape);
        if (MeOperations.mutateMe(grid, storage, actor, tape, 1, false, Actionable.SIMULATE) != 1) {
            throw new BridgeError(409, "missing_duct_tape", "ME could not supply one duct tape");
        }
        byte before = hatch.getMaintenanceProblems();
        boolean taped = hatch.isTaped();
        try {
            if (MeOperations.mutateMe(grid, storage, actor, tape, 1, false, Actionable.MODULATE) != 1
                    || MeOperations.count(storage, tape) != stock - 1) {
                throw new BridgeError(409, "missing_duct_tape", "ME did not supply one duct tape");
            }
            hatch.fixAllMaintenanceProblems();
            hatch.setTaped(true);
            if (hatch.hasMaintenanceProblems()) {
                throw new BridgeError(409, "maintenance_failed", "GT did not clear the maintenance problems");
            }
        } catch (RuntimeException failure) {
            try {
                hatch.setMaintenanceProblems(before);
                hatch.setTaped(taped);
                MeOperations.restoreMe(storage, actor, tape, stock);
            } catch (RuntimeException rollback) {
                failure.addSuppressed(rollback);
                throw new IllegalStateException("GT maintenance rollback did not finish", failure);
            }
            throw failure;
        }
        JsonObject result = inspect(level, position);
        result.addProperty("tapeUsed", 1);
        return result;
    }
}
