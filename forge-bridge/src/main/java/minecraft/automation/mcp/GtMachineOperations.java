package minecraft.automation.mcp;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.SimpleTieredMachine;
import com.gregtechceu.gtceu.api.machine.WorkableTieredMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.common.machine.multiblock.part.EnergyHatchPartMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.FluidHatchPartMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.ItemBusPartMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.MaintenanceHatchPartMachine;
import com.gregtechceu.gtceu.config.ConfigHolder;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.common.data.GTMachines;
import com.gregtechceu.gtceu.common.machine.multiblock.primitive.PrimitiveWorkableMachine;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;

final class GtMachineOperations {
    private GtMachineOperations() {}

    static JsonObject inspect(ServerLevel level, BlockPos position) {
        MetaMachine machine = MetaMachine.getMachine(level, position);
        if (machine == null) {
            throw new BridgeError(409, "not_gt_machine", "The position must contain a GT machine");
        }
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
            result.addProperty("energy", workable.energyContainer.getEnergyStored());
            result.addProperty("energyCapacity", workable.energyContainer.getEnergyCapacity());
            result.addProperty("inputVoltage", workable.energyContainer.getInputVoltage());
        }
        if (machine instanceof WorkableElectricMultiblockMachine electric && electric.isFormed()) {
            var energy = electric.getEnergyContainer();
            result.addProperty("energy", energy.getEnergyStored());
            result.addProperty("energyCapacity", energy.getEnergyCapacity());
            result.addProperty("inputVoltage", energy.getInputVoltage());
        }
        if (machine instanceof EnergyHatchPartMachine hatch) {
            result.addProperty("energy", hatch.energyContainer.getEnergyStored());
            result.addProperty("energyCapacity", hatch.energyContainer.getEnergyCapacity());
            result.addProperty("inputVoltage", hatch.energyContainer.getInputVoltage());
        }
        if (machine instanceof ItemBusPartMachine bus) {
            result.add("items", items(bus.getInventory()));
        }
        if (machine instanceof FluidHatchPartMachine hatch) {
            result.add("fluids", fluids(hatch.tank));
        }
        if (machine instanceof MaintenanceHatchPartMachine hatch) {
            result.addProperty("maintenanceProblems", hatch.getNumMaintenanceProblems());
            result.add("items", items(maintenanceInventory(hatch)));
        }
                ProcessBuffers buffers = buffersFor(machine);
        if (buffers != null) {
            describeRecipe(result, buffers.logic, buffers.recipeType);
            result.add("itemInputs", items(buffers.inputItems));
            result.add("itemOutputs", items(buffers.outputItems));
            result.add("fluidInputs", fluids(buffers.inputFluids));
            result.add("fluidOutputs", fluids(buffers.outputFluids));
        } else if (machine instanceof WorkableMultiblockMachine multi) {
            describeRecipe(result, multi.getRecipeLogic(), multi.getRecipeType());
        }
        if (machine instanceof SimpleTieredMachine simple) {
            result.addProperty("autoOutputItems", simple.isAutoOutputItems());
            result.addProperty("autoOutputFluids", simple.isAutoOutputFluids());
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

    private static IItemTransfer itemBufferAt(ServerLevel level, BlockPos position, boolean input) {
        MetaMachine machine = MetaMachine.getMachine(level, position);
        if (machine instanceof ItemBusPartMachine bus) {
            return bus.getInventory();
        }
                ProcessBuffers buffers = requireBuffers(machine);
        return input ? buffers.inputItems : buffers.outputItems;
    }

    private static NotifiableItemStackHandler maintenanceInventory(MaintenanceHatchPartMachine hatch) {
        return hatch.getTraits().stream().filter(NotifiableItemStackHandler.class::isInstance)
                .map(NotifiableItemStackHandler.class::cast).findFirst().orElseThrow(
                        () -> new BridgeError(409, "maintenance_unavailable", "The maintenance hatch has no item slot"));
    }

    static NotifiableFluidTank fluidBufferAt(ServerLevel level, BlockPos position, boolean input) {
        MetaMachine machine = MetaMachine.getMachine(level, position);
        if (machine instanceof FluidHatchPartMachine hatch) {
            return hatch.tank;
        }
        ProcessBuffers buffers = requireBuffers(machine);
        return input ? buffers.inputFluids : buffers.outputFluids;
    }
    static JsonObject transferMe(ServerLevel machineLevel, BlockPos position, ServerLevel driveLevel,
                                 BlockPos drive, boolean toMachine, int slot, ResourceLocation item,
                                 int amount, String expectedToken) {

        IItemTransfer buffer = itemBufferAt(machineLevel, position, toMachine);
        ItemStack before = slotSnapshot(buffer, slot);
        var grid = MeOperations.gridAt(driveLevel, drive);
        var action = MeOperations.playerAction(driveLevel);
        var storage = grid.getStorageService().getInventory();
        AEItemKey key;
        ItemStack moving;
        if (toMachine) {
            key = MeOperations.resolveMeStack(storage, AEItemKey.class, item, expectedToken);
            moving = key.toStack(amount);
            requireInsertion(buffer, slot, moving);
        } else {
            moving = requireExtraction(buffer, slot, before, item, amount, expectedToken);
            key = AEItemKey.of(moving);
            if (key == null || !ItemStack.isSameItemSameTags(moving, key.toStack())) {
                throw new BridgeError(409, "unsupported_item", "AE2 cannot represent this stack exactly");
            }
        }
        long meBefore = MeOperations.count(storage, key);
        long meAfter;
        int transferred = amount;
        if (!toMachine) {
            transferred = MeOperations.deposit(grid, storage, action, key, amount,
                    () -> extractExact(buffer, slot, moving),
                    accepted -> restoreSlot(buffer, slot, before.copyWithCount(before.getCount() - accepted)));
            meAfter = MeOperations.count(storage, key);
        } else {
            meAfter = MeOperations.withdraw(grid, storage, action, key, amount, meBefore,
                    () -> insertExact(buffer, slot, before, moving), () -> restoreSlot(buffer, slot, before));
        }
        JsonObject result = moved(item, transferred);
        result.addProperty("requested", amount);
        result.addProperty("meBefore", meBefore);
        result.addProperty("meAfter", meAfter);
        return result;
    }

    static JsonObject transferMachines(ServerLevel sourceLevel, BlockPos sourcePosition, int sourceSlot,
                                       ServerLevel targetLevel, BlockPos targetPosition, int targetSlot,
                                       ResourceLocation item, int amount, String expectedToken) {
        if (sourceLevel == targetLevel && sourcePosition.equals(targetPosition)) {
            throw new BridgeError(400, "same_machine", "Source and target must be different machines");
        }
        IItemTransfer source = itemBufferAt(sourceLevel, sourcePosition, false);
        IItemTransfer target = itemBufferAt(targetLevel, targetPosition, true);
        ItemStack sourceBefore = slotSnapshot(source, sourceSlot);
        ItemStack targetBefore = slotSnapshot(target, targetSlot);
        ItemStack moving = requireExtraction(source, sourceSlot, sourceBefore, item, amount, expectedToken);
        requireInsertion(target, targetSlot, moving);
        try {
            extractExact(source, sourceSlot, moving);
            insertExact(target, targetSlot, targetBefore, moving);
        } catch (RuntimeException failure) {
            try {
                restoreSlot(target, targetSlot, targetBefore);
                restoreSlot(source, sourceSlot, sourceBefore);
            } catch (RuntimeException rollback) {
                failure.addSuppressed(rollback);
                throw new IllegalStateException("GT machine transfer rollback did not finish", failure);
            }
            throw failure;
        }
        return moved(item, amount);
    }

    private static JsonObject moved(ResourceLocation item, int amount) {
        JsonObject result = new JsonObject();
        result.addProperty("item", item.toString());
        result.addProperty("moved", amount);
        return result;
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
        return null;
    }

    private static WorkableTieredMachine workableAt(ServerLevel level, BlockPos position) {
        if (!(MetaMachine.getMachine(level, position) instanceof WorkableTieredMachine machine)) {
            throw new BridgeError(409, "not_supported_machine", "The position must contain a supported GT single-block machine");
        }
        return machine;
    }

    private static ItemStack slotSnapshot(IItemTransfer buffer, int slot) {
        if (slot < 0 || slot >= buffer.getSlots()) {
            throw new BridgeError(400, "invalid_machine_slot", "The machine slot does not exist");
        }
        return buffer.getStackInSlot(slot).copy();
    }

    private static ItemStack requireExtraction(IItemTransfer buffer, int slot, ItemStack before,
                                               ResourceLocation item, int amount, String expectedToken) {
        if (before.isEmpty() || !item.equals(ForgeRegistries.ITEMS.getKey(before.getItem()))
                || !ChestOperations.stackToken(before).equals(expectedToken)) {
            throw new BridgeError(409, "source_changed", "The machine slot no longer matches the inventory read");
        }
        if (amount < 1 || amount > before.getCount()) {
            throw new BridgeError(409, "insufficient_items", "The machine slot contains fewer items than requested");
        }
        ItemStack moving = before.copyWithCount(amount);
        if (!ItemStack.matches(buffer.extractItem(slot, amount, true), moving)) {
            throw new BridgeError(409, "machine_rejected", "The output slot cannot provide the requested stack");
        }
        return moving;
    }

    private static void requireInsertion(IItemTransfer buffer, int slot, ItemStack moving) {
        if (moving.isEmpty() || !buffer.insertItem(slot, moving.copy(), true).isEmpty()) {
            throw new BridgeError(409, "machine_rejected", "The input slot cannot accept the full requested stack");
        }
    }

    private static void extractExact(IItemTransfer buffer, int slot, ItemStack moving) {
        int previous = buffer.getStackInSlot(slot).getCount();
        if (!ItemStack.matches(buffer.extractItem(slot, moving.getCount(), false), moving)
                || buffer.getStackInSlot(slot).getCount() != previous - moving.getCount()) {
            throw new BridgeError(409, "machine_rejected", "The output slot did not provide the planned stack");
        }
    }

    private static void insertExact(IItemTransfer buffer, int slot, ItemStack before, ItemStack moving) {
        if (!buffer.insertItem(slot, moving.copy(), false).isEmpty()
                || !ItemStack.matches(buffer.getStackInSlot(slot),
                moving.copyWithCount(before.getCount() + moving.getCount()))) {
            throw new BridgeError(409, "machine_rejected", "The input slot did not retain the planned stack");
        }
    }

    private static void restoreSlot(IItemTransfer buffer, int slot, ItemStack before) {
        if (!ItemStack.matches(buffer.getStackInSlot(slot), before)) {
            buffer.setStackInSlot(slot, before.copy());
            if (!ItemStack.matches(buffer.getStackInSlot(slot), before)) {
                throw new IllegalStateException("The GT item slot did not restore");
            }
        }
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
                entry.addProperty("stackToken", ChestOperations.stackToken(stack));
            }
            items.add(entry);
        }
        return items;
    }

    private static JsonArray fluids(NotifiableFluidTank buffer) {
        JsonArray fluids = new JsonArray();
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

    static JsonObject serviceMaintenance(ServerLevel level, BlockPos position, ServerLevel driveLevel,
                                         BlockPos drive, String expectedToken) {
        if (!(MetaMachine.getMachine(level, position) instanceof MaintenanceHatchPartMachine hatch)) {
            throw new BridgeError(409, "not_maintenance_hatch", "The position must contain a GT maintenance hatch");
        }
        if (!hatch.hasMaintenanceProblems()) {
            JsonObject result = inspect(level, position);
            result.addProperty("tapeUsed", 0);
            return result;
        }
        var grid = MeOperations.gridAt(driveLevel, drive);
        var actor = MeOperations.playerAction(driveLevel);
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

    static JsonObject prepareTestHatchCharge(ServerLevel level, BlockPos position) {
        if (!(MetaMachine.getMachine(level, position) instanceof EnergyHatchPartMachine hatch)
                || hatch.energyContainer.getInputVoltage() <= 0) {
            throw new BridgeError(409, "not_energy_input", "The test requires a GT energy input hatch");
        }
        long added = hatch.energyContainer.addEnergy(hatch.energyContainer.getEnergyCanBeInserted());
        JsonObject result = inspect(level, position);
        result.addProperty("charged", added);
        return result;
    }

    static JsonObject prepareTestFurnace(ServerLevel level, BlockPos position) {
        Block furnace = GTMachines.ELECTRIC_FURNACE[1].getBlock();
        if (level.getBlockState(position).is(furnace)) {
            return inspect(level, position);
        }
        if (!MinecraftAutomationBridge.isClearFixtureSite(level, position)) {
            throw new BridgeError(409, "fixture_site_unavailable", "The test furnace needs an air block above solid ground");
        }
        try {
            if (!level.setBlock(position, furnace.defaultBlockState(), Block.UPDATE_ALL)) {
                throw new BridgeError(409, "fixture_refused", "The game did not place the test furnace");
            }
            var machine = workableAt(level, position);
            machine.getRecipeLogic().setWorkingEnabled(false);
            long stored = machine.energyContainer.addEnergy(machine.energyContainer.getEnergyCapacity());
            if (stored <= 0) {
                throw new BridgeError(409, "fixture_refused", "The test furnace did not accept a charge");
            }
            return inspect(level, position);
        } catch (RuntimeException failure) {
            if (level.getBlockState(position).is(furnace)) {
                level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            throw failure;
        }
    }
}
