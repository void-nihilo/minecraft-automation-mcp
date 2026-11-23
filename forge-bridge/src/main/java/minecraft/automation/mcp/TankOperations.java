package minecraft.automation.mcp;

import com.google.common.hash.Hashing;
import com.google.gson.JsonObject;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;

import static net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;
import static net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE;

final class TankOperations {
    private static final ResourceLocation BRONZE_DRUM = new ResourceLocation("gtceu", "bronze_drum");

    private TankOperations() {}

    static JsonObject contents(ServerLevel level, BlockPos position) {
        IFluidHandler tank = tankAt(level, position);
        FluidStack fluid = snapshot(tank);
        JsonObject result = new JsonObject();
        result.addProperty("dimension", level.dimension().location().toString());
        result.add("position", MinecraftAutomationBridge.positionJson(position));
        result.addProperty("capacity", tank.getTankCapacity(0));
        result.addProperty("amount", fluid.getAmount());
        if (!fluid.isEmpty()) {
            result.addProperty("fluid", ForgeRegistries.FLUIDS.getKey(fluid.getFluid()).toString());
            result.addProperty("fluidToken", fluidToken(fluid));
        }
        return result;
    }

    static JsonObject transfer(ServerLevel sourceLevel, BlockPos sourcePosition,
                               ServerLevel targetLevel, BlockPos targetPosition, int amount,
                               String expectedFluidToken) {
        if (sourceLevel == targetLevel && sourcePosition.equals(targetPosition)) {
            throw new BridgeError(400, "same_tank", "Source and target must be different tanks");
        }
        if (amount < 1) {
            throw new BridgeError(400, "invalid_transfer", "Specify a positive transfer amount in mB");
        }
        IFluidHandler source = tankAt(sourceLevel, sourcePosition);
        IFluidHandler target = tankAt(targetLevel, targetPosition);
        FluidStack sourceBefore = snapshot(source);
        FluidStack targetBefore = snapshot(target);
        if (sourceBefore.isEmpty() || !fluidToken(sourceBefore).equals(expectedFluidToken)) {
            throw new BridgeError(409, "source_changed", "Source fluid no longer matches the tank read");
        }
        if (sourceBefore.getAmount() < amount) {
            throw new BridgeError(409, "insufficient_fluid", "Source tank contains less fluid than requested");
        }

        FluidStack moving = new FluidStack(sourceBefore, amount);
        if ((!targetBefore.isEmpty() && !targetBefore.isFluidEqual(moving)) || !target.isFluidValid(0, moving)) {
            throw new BridgeError(409, "incompatible_fluid", "Target tank cannot accept this fluid");
        }
        if (!matches(source.drain(moving.copy(), SIMULATE), moving)) {
            throw new BridgeError(409, "drain_refused", "Source tank cannot drain the full amount");
        }
        if (target.fill(moving.copy(), SIMULATE) != amount) {
            throw new BridgeError(409, "target_full", "Target tank lacks room for the full transfer");
        }

        FluidStack sourceAfter = new FluidStack(sourceBefore, sourceBefore.getAmount() - amount);
        FluidStack targetAfter = new FluidStack(moving, targetBefore.getAmount() + amount);
        try {
            FluidStack drained = source.drain(moving.copy(), EXECUTE);
            if (!matches(drained, moving) || target.fill(drained.copy(), EXECUTE) != amount
                    || !matches(snapshot(source), sourceAfter) || !matches(snapshot(target), targetAfter)) {
                throw new BridgeError(409, "transfer_refused", "Tanks did not retain the planned fluid amounts");
            }
        } catch (RuntimeException failure) {
            try {
                // Remove any deposited part before restoring the source.
                restore(target, targetBefore, moving);
                restore(source, sourceBefore, moving);
            } catch (RuntimeException rollbackError) {
                failure.addSuppressed(rollbackError);
                throw new IllegalStateException("Tank transfer rollback did not finish", failure);
            }
            throw failure;
        }

        JsonObject result = new JsonObject();
        result.addProperty("fluid", ForgeRegistries.FLUIDS.getKey(moving.getFluid()).toString());
        result.addProperty("moved", amount);
        result.addProperty("sourceBefore", sourceBefore.getAmount());
        result.addProperty("sourceAfter", sourceAfter.getAmount());
        result.addProperty("targetBefore", targetBefore.getAmount());
        result.addProperty("targetAfter", targetAfter.getAmount());
        result.addProperty("totalBefore", (long) sourceBefore.getAmount() + targetBefore.getAmount());
        result.addProperty("totalAfter", (long) sourceAfter.getAmount() + targetAfter.getAmount());
        return result;
    }

    static IFluidHandler tankAt(ServerLevel level, BlockPos position) {
        BlockEntity entity = level.getBlockEntity(position);
        if (!BRONZE_DRUM.equals(ForgeRegistries.BLOCKS.getKey(level.getBlockState(position).getBlock()))
                || entity == null || entity.isRemoved()) {
            throw new BridgeError(409, "not_supported_tank", "The position must contain a GT bronze drum");
        }
        IFluidHandler tank = entity.getCapability(ForgeCapabilities.FLUID_HANDLER).resolve().orElseThrow(
                () -> new BridgeError(409, "tank_unavailable", "Drum fluid access is unavailable"));
        if (tank.getTanks() != 1) {
            throw new BridgeError(409, "not_supported_tank", "The drum must expose one fluid compartment");
        }
        return tank;
    }

    static FluidStack snapshot(IFluidHandler tank) {
        return tank.getFluidInTank(0).copy();
    }

    static boolean matches(FluidStack actual, FluidStack expected) {
        return actual.getAmount() == expected.getAmount() && (expected.isEmpty() || actual.isFluidEqual(expected));
    }

    static String fluidToken(FluidStack fluid) {
        String state = fluid.writeToNBT(new CompoundTag()).toString();
        return Hashing.sha256().hashString(state, StandardCharsets.UTF_8).toString();
    }

    static void restore(IFluidHandler tank, FluidStack before, FluidStack moving) {
        FluidStack current = snapshot(tank);
        if (matches(current, before)) {
            return;
        }
        if (!current.isEmpty() && !current.isFluidEqual(moving)) {
            throw new IllegalStateException("Tank changed to another fluid during the transfer");
        }
        int delta = current.getAmount() - before.getAmount();
        if (delta > 0) {
            tank.drain(new FluidStack(moving, delta), EXECUTE);
        } else if (delta < 0) {
            tank.fill(new FluidStack(moving, -delta), EXECUTE);
        }
        if (!matches(snapshot(tank), before)) {
            throw new IllegalStateException("Tank did not restore its original fluid");
        }
    }

    static JsonObject prepareTestTanks(ServerLevel level, BlockPos source, BlockPos target) {
        Block drum = ForgeRegistries.BLOCKS.getValue(BRONZE_DRUM);
        if (drum == null || drum == Blocks.AIR) {
            throw new BridgeError(409, "tank_unavailable", "The GT bronze drum is not registered");
        }
        if (source.equals(target) || !MinecraftAutomationBridge.isClearFixtureSite(level, source) || !MinecraftAutomationBridge.isClearFixtureSite(level, target)) {
            throw new BridgeError(409, "fixture_site_unavailable", "Test drums need two distinct air blocks above solid ground");
        }
        try {
            if (!level.setBlock(source, drum.defaultBlockState(), Block.UPDATE_ALL)
                    || !level.setBlock(target, drum.defaultBlockState(), Block.UPDATE_ALL)) {
                throw new BridgeError(409, "fixture_refused", "The game did not place both test drums");
            }
            fillTestTank(level, source, 12000);
            fillTestTank(level, target, 30000);
            JsonObject result = new JsonObject();
            result.add("source", contents(level, source));
            result.add("target", contents(level, target));
            return result;
        } catch (RuntimeException failure) {
            for (BlockPos position : new BlockPos[] {target, source}) {
                if (level.getBlockState(position).is(drum)) {
                    try {
                        level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    } catch (RuntimeException rollbackError) {
                        failure.addSuppressed(rollbackError);
                    }
                }
            }
            throw failure;
        }
    }

    private static void fillTestTank(ServerLevel level, BlockPos position, int amount) {
        IFluidHandler tank = tankAt(level, position);
        if (tank.fill(new FluidStack(Fluids.WATER, amount), EXECUTE) != amount) {
            throw new BridgeError(409, "fixture_refused", "Test drum did not accept the expected amount of water");
        }
    }
}
