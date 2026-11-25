package minecraft.automation.mcp;

import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;
import static net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE;

final class TankOperations {
    private TankOperations() {}

    static JsonObject contents(ServerLevel level, BlockPos position, Direction side) {
        IFluidHandler tank = tankAt(level, position, side);
        JsonObject result = new JsonObject();
        result.addProperty("dimension", level.dimension().location().toString());
        result.add("position", MinecraftAutomationBridge.positionJson(position));
        if (side != null) {
            result.addProperty("side", side.getName());
        }
        JsonArray compartments = new JsonArray();
        var totals = new LinkedHashMap<FluidVariant, Long>();
        int tankCount = tank.getTanks();
        for (int index = 0; index < tankCount; index++) {
            FluidStack fluid = tank.getFluidInTank(index);
            JsonObject row = new JsonObject();
            row.addProperty("index", index);
            row.addProperty("capacity", tank.getTankCapacity(index));
            row.addProperty("amount", fluid.getAmount());
            if (!fluid.isEmpty()) {
                row.addProperty("fluid", ForgeRegistries.FLUIDS.getKey(fluid.getFluid()).toString());
                totals.merge(FluidVariant.of(fluid), (long) fluid.getAmount(), Long::sum);
            }
            compartments.add(row);
            if (tankCount == 1) {
                result.add("capacity", row.get("capacity"));
                result.add("amount", row.get("amount"));
                if (!fluid.isEmpty()) {
                    result.add("fluid", row.get("fluid"));
                    result.addProperty("fluidToken", fluidToken(fluid));
                }
            }
        }
        JsonArray fluids = new JsonArray();
        totals.forEach((variant, amount) -> {
            JsonObject row = new JsonObject();
            row.addProperty("fluid", ForgeRegistries.FLUIDS.getKey(variant.fluid).toString());
            row.addProperty("amount", amount);
            row.addProperty("fluidToken", variant.token(amount));
            if (variant.tag != null) {
                row.addProperty("tag", variant.tag.toString());
            }
            fluids.add(row);
        });
        result.add("tanks", compartments);
        result.add("fluids", fluids);
        return result;
    }

    static JsonObject transfer(ServerLevel sourceLevel, BlockPos sourcePosition, Direction sourceSide,
                               ServerLevel targetLevel, BlockPos targetPosition, Direction targetSide, int amount,
                               String expectedFluidToken) {
        if (sourceLevel == targetLevel && sourcePosition.equals(targetPosition)) {
            throw new BridgeError(400, "same_tank", "Source and target must be different tanks");
        }
        if (amount < 1) {
            throw new BridgeError(400, "invalid_transfer", "Specify a positive transfer amount in mB");
        }
        IFluidHandler source = tankAt(sourceLevel, sourcePosition, sourceSide);
        IFluidHandler target = fillingTarget(targetLevel, targetPosition, targetSide);
        if (source == target) {
            throw new BridgeError(400, "same_tank", "Source and target expose the same fluid handler");
        }
        var sourceBalances = balances(source);
        var targetBalances = balances(target);
        FluidStack moving = select(sourceBalances, expectedFluidToken, amount);
        requireDrain(source, moving);
        requireFill(target, moving);

        try {
            drain(source, sourceBalances, moving);
            fill(target, targetBalances, moving);
            if (!balances(source).equals(adjusted(sourceBalances, moving, -amount))) {
                throw new BridgeError(409, "balance_mismatch", "The transfer endpoints may share fluid storage");
            }
        } catch (RuntimeException failure) {
            try {
                // Remove any deposited part before restoring the source.
                restore(target, targetBalances, moving, 0);
                restore(source, sourceBalances, moving, 0);
                if (!balances(target).equals(targetBalances)) {
                    throw new IllegalStateException("Restoring the source changed the target fluid balances");
                }
            } catch (RuntimeException rollbackError) {
                failure.addSuppressed(rollbackError);
                throw new IllegalStateException("Tank transfer rollback did not finish", failure);
            }
            throw failure;
        }

        JsonObject result = new JsonObject();
        long sourceBefore = amount(sourceBalances, moving);
        long targetBefore = amount(targetBalances, moving);
        result.addProperty("fluid", ForgeRegistries.FLUIDS.getKey(moving.getFluid()).toString());
        result.addProperty("moved", amount);
        result.addProperty("sourceBefore", sourceBefore);
        result.addProperty("sourceAfter", sourceBefore - amount);
        result.addProperty("targetBefore", targetBefore);
        result.addProperty("targetAfter", targetBefore + amount);
        result.addProperty("totalBefore", sourceBefore + targetBefore);
        result.addProperty("totalAfter", sourceBefore + targetBefore);
        return result;
    }

    static IFluidHandler fillingTarget(ServerLevel level, BlockPos position, Direction side) {
        if (ModList.get().isLoaded("gtceu")) {
            GtBoilerProtection.beforeFluidInsertion(level, position);
        }
        return tankAt(level, position, side);
    }

    static IFluidHandler tankAt(ServerLevel level, BlockPos position, Direction side) {
        BlockEntity entity = level.getBlockEntity(position);
        if (entity == null || entity.isRemoved()) {
            throw new BridgeError(409, "tank_unavailable", "No fluid handler at the given position");
        }
        IFluidHandler tank = entity.getCapability(ForgeCapabilities.FLUID_HANDLER, side).resolve().orElseThrow(
                () -> new BridgeError(409, "tank_unavailable", "No Forge fluid handler on the requested side"));
        if (tank.getTanks() < 1) {
            throw new BridgeError(409, "tank_unavailable", "The fluid handler does not expose any readable tanks");
        }
        return tank;
    }

    static Map<FluidVariant, Long> balances(IFluidHandler tank) {
        var totals = new LinkedHashMap<FluidVariant, Long>();
        for (int index = 0, count = tank.getTanks(); index < count; index++) {
            FluidStack fluid = tank.getFluidInTank(index);
            if (!fluid.isEmpty()) {
                totals.merge(FluidVariant.of(fluid), (long) fluid.getAmount(), Long::sum);
            }
        }
        return totals;
    }

    static FluidStack select(Map<FluidVariant, Long> before, String token, int amount) {
        for (var entry : before.entrySet()) {
            if (entry.getKey().token(entry.getValue()).equals(token)) {
                if (entry.getValue() < amount) {
                    throw new BridgeError(409, "insufficient_fluid", "Source tank contains less fluid than requested");
                }
                return entry.getKey().stack(amount);
            }
        }
        throw new BridgeError(409, "source_changed", "Source fluid no longer matches the tank read");
    }

    static long amount(Map<FluidVariant, Long> balances, FluidStack fluid) {
        return balances.getOrDefault(FluidVariant.of(fluid), 0L);
    }

    private static Map<FluidVariant, Long> adjusted(Map<FluidVariant, Long> before, FluidStack moving, int delta) {
        var expected = new LinkedHashMap<>(before);
        FluidVariant variant = FluidVariant.of(moving);
        long amount = expected.getOrDefault(variant, 0L) + delta;
        if (amount == 0) {
            expected.remove(variant);
        } else {
            expected.put(variant, amount);
        }
        return expected;
    }

    static void requireDrain(IFluidHandler tank, FluidStack moving) {
        if (!matches(tank.drain(moving.copy(), SIMULATE), moving)) {
            throw new BridgeError(409, "drain_refused", "Source cannot drain the full fluid amount");
        }
    }

    static void requireFill(IFluidHandler tank, FluidStack moving) {
        if (tank.fill(moving.copy(), SIMULATE) != moving.getAmount()) {
            throw new BridgeError(409, "target_full", "Target cannot accept the full fluid amount");
        }
    }

    static void drain(IFluidHandler tank, Map<FluidVariant, Long> before, FluidStack moving) {
        if (!matches(tank.drain(moving.copy(), EXECUTE), moving)
                || !balances(tank).equals(adjusted(before, moving, -moving.getAmount()))) {
            throw new BridgeError(409, "drain_refused", "Source did not retain the expected fluid balances");
        }
    }

    static void fill(IFluidHandler tank, Map<FluidVariant, Long> before, FluidStack moving) {
        if (tank.fill(moving.copy(), EXECUTE) != moving.getAmount()
                || !balances(tank).equals(adjusted(before, moving, moving.getAmount()))) {
            throw new BridgeError(409, "fill_refused", "Target did not retain the expected fluid balances");
        }
    }

    static boolean matches(FluidStack actual, FluidStack expected) {
        return actual.getAmount() == expected.getAmount() && (expected.isEmpty() || actual.isFluidEqual(expected));
    }

    private static String fluidToken(FluidStack fluid) {
        String state = fluid.writeToNBT(new CompoundTag()).toString();
        return Hashing.sha256().hashString(state, StandardCharsets.UTF_8).toString();
    }

    static void restore(IFluidHandler tank, Map<FluidVariant, Long> before, FluidStack moving, int retainedDelta) {
        var expected = adjusted(before, moving, retainedDelta);
        var current = balances(tank);
        long delta = amount(current, moving) - amount(expected, moving);
        if (delta > 0) {
            tank.drain(new FluidStack(moving, Math.toIntExact(delta)), EXECUTE);
        } else if (delta < 0) {
            tank.fill(new FluidStack(moving, Math.toIntExact(-delta)), EXECUTE);
        }
        if (!balances(tank).equals(expected)) {
            throw new IllegalStateException("Tank did not restore the expected fluid balances");
        }
    }

    record FluidVariant(Fluid fluid, CompoundTag tag) {
        static FluidVariant of(FluidStack stack) {
            return new FluidVariant(stack.getFluid(), stack.getTag() == null ? null : stack.getTag().copy());
        }

        FluidStack stack(int amount) {
            return new FluidStack(fluid, amount, tag == null ? null : tag.copy());
        }

        String token(long amount) {
            if (amount <= Integer.MAX_VALUE) {
                return fluidToken(stack((int) amount));
            }
            String state = stack(1).writeToNBT(new CompoundTag()) + ":" + amount;
            return Hashing.sha256().hashString(state, StandardCharsets.UTF_8).toString();
        }
    }
}
