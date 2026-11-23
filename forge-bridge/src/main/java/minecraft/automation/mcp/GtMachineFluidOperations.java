package minecraft.automation.mcp;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import com.google.gson.JsonObject;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

final class GtMachineFluidOperations {
    private GtMachineFluidOperations() {}

    static JsonObject transferMe(ServerLevel machineLevel, BlockPos position, ServerLevel driveLevel,
                                 BlockPos drive, boolean toMachine, int tank, ResourceLocation fluid,
                                 int amount, String expectedToken) {
        if (amount < 1) {
            throw new BridgeError(400, "invalid_transfer", "Specify a positive amount in mB");
        }
        var buffer = GtMachineOperations.fluidBufferAt(machineLevel, position, toMachine);
        if (tank < 0 || tank >= buffer.getTanks()) {
            throw new BridgeError(400, "invalid_machine_tank", "The GT machine fluid slot does not exist");
        }
        FluidStack before = buffer.getFluidInTank(tank).copy();
        var grid = MeOperations.gridAt(driveLevel, drive);
        var actor = MeOperations.playerAction(driveLevel);
        var storage = grid.getStorageService().getInventory();
        AEFluidKey key;
        if (toMachine) {
            key = MeOperations.resolveMeStack(storage, AEFluidKey.class, fluid, expectedToken);
        } else {
            if (before.isEmpty() || !fluid.equals(ForgeRegistries.FLUIDS.getKey(before.getFluid()))
                    || !GtMachineOperations.machineFluidToken(before).equals(expectedToken)) {
                throw new BridgeError(409, "source_changed", "GT fluid no longer matches the machine read");
            }
            key = AEFluidKey.of(before.getFluid(), before.getTag());
        }
        FluidStack moving = FluidStack.create(key.getFluid(), amount, key.copyTag());
        if (toMachine ? buffer.fill(tank, moving.copy(), true, false) != amount
                : !matches(buffer.drain(tank, moving.copy(), true, false), moving)) {
            throw new BridgeError(409, "machine_rejected", "GT fluid slot cannot move the full amount");
        }
        long meBefore = MeOperations.count(storage, key);
        long meAfter;
        long amountAfter;
        int transferred = amount;
        if (!toMachine) {
            FluidStack expected = fluidWithAmount(moving, before.getAmount() - amount);
            transferred = MeOperations.deposit(grid, storage, actor, key, amount, () -> {
                if (!matches(buffer.drain(tank, moving.copy(), false, true), moving)
                        || !matches(buffer.getFluidInTank(tank), expected)) {
                    throw new BridgeError(409, "machine_rejected", "GT did not drain the planned amount");
                }
            }, accepted -> restore(buffer, tank, fluidWithAmount(moving, before.getAmount() - accepted)));
            meAfter = MeOperations.count(storage, key);
            amountAfter = buffer.getFluidInTank(tank).getAmount();
        } else {
            amountAfter = Math.addExact(before.getAmount(), amount);
            FluidStack expected = moving.copy(amountAfter);
            meAfter = MeOperations.withdraw(grid, storage, actor, key, amount, meBefore, () -> {
                if (buffer.fill(tank, moving.copy(), false, true) != amount
                        || !matches(buffer.getFluidInTank(tank), expected)) {
                    throw new BridgeError(409, "machine_rejected", "GT did not retain the planned fluid");
                }
            }, () -> restore(buffer, tank, before));
        }
        JsonObject result = new JsonObject();
        result.addProperty("fluid", fluid.toString());
        result.addProperty("requested", amount);
        result.addProperty("moved", transferred);
        result.addProperty("meBefore", meBefore);
        result.addProperty("meAfter", meAfter);
        result.addProperty("machineBefore", before.getAmount());
        result.addProperty("machineAfter", amountAfter);
        return result;
    }

    private static FluidStack fluidWithAmount(FluidStack fluid, long amount) {
        return amount == 0 ? FluidStack.empty() : fluid.copy(amount);
    }

    private static boolean matches(FluidStack actual, FluidStack expected) {
        return actual.getAmount() == expected.getAmount() && (expected.isEmpty() || actual.isFluidEqual(expected));
    }

    private static void restore(com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank buffer,
                                int tank, FluidStack before) {
        if (!matches(buffer.getFluidInTank(tank), before)) {
            buffer.setFluidInTank(tank, before.copy());
            if (!matches(buffer.getFluidInTank(tank), before)) {
                throw new IllegalStateException("GT fluid slot did not restore");
            }
        }
    }

    static JsonObject prepareTestCoal(ServerLevel level, BlockPos drive) {
        return prepareTestItems(level, drive, AEItemKey.of(Items.COAL), 1);
    }

    static JsonObject prepareTestDuctTape(ServerLevel level, BlockPos drive) {
        var tape = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse("gtceu:duct_tape"));
        if (tape == null || tape == Items.AIR) {
            throw new BridgeError(409, "fixture_unavailable", "The duct tape item is unavailable");
        }
        return prepareTestItems(level, drive, AEItemKey.of(tape), 1);
    }

    static JsonObject prepareTestLogs(ServerLevel level, BlockPos drive) {
        return prepareTestItems(level, drive, AEItemKey.of(Items.OAK_LOG), 32);
    }

    private static JsonObject prepareTestItems(ServerLevel level, BlockPos drive, AEItemKey key, int required) {
        var grid = MeOperations.gridAt(level, drive);
        var actor = MeOperations.playerAction(level);
        var storage = grid.getStorageService().getInventory();

        long before = MeOperations.count(storage, key);
        long shortage = required - before;
        if (shortage > 0) {
            if (MeOperations.mutateMe(grid, storage, actor, key, shortage, true, Actionable.SIMULATE) != shortage) {
                throw new BridgeError(409, "fixture_refused", "ME cannot accept the test inputs");
            }
            try {
                if (MeOperations.mutateMe(grid, storage, actor, key, shortage, true, Actionable.MODULATE) != shortage
                        || MeOperations.count(storage, key) != required) {
                    throw new BridgeError(409, "fixture_refused", "ME did not retain the test inputs");
                }
            } catch (RuntimeException failure) {
                MeOperations.restoreMe(storage, actor, key, before);
                throw failure;
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("item", key.getId().toString());
        result.addProperty("seeded", shortage > 0);
        result.addProperty("count", MeOperations.count(storage, key));
        return result;
    }
}
