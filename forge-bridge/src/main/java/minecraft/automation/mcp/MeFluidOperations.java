package minecraft.automation.mcp;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.google.gson.JsonObject;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

final class MeFluidOperations {
    private MeFluidOperations() {}

    static JsonObject transfer(ServerLevel tankLevel, BlockPos tankPosition, Direction tankSide, MeOperations.Endpoint endpoint, boolean toMe, ResourceLocation fluidId, int amount,
                               String expectedSourceToken) {
        if (amount < 1) {
            throw new BridgeError(400, "invalid_transfer", "Specify a positive transfer amount in mB");
        }
        var tank = toMe ? TankOperations.tankAt(tankLevel, tankPosition, tankSide)
                : TankOperations.fillingTarget(tankLevel, tankPosition, tankSide);
        var tankBalances = TankOperations.balances(tank);
        IGrid grid = endpoint.grid();
        IActionSource actor = MeOperations.playerAction(endpoint.level());
        MEStorage storage = grid.getStorageService().getInventory();

        AEFluidKey key;
        FluidStack moving;
        if (toMe) {
            moving = TankOperations.select(tankBalances, expectedSourceToken, amount);
            key = AEFluidKey.of(moving);
            if (key == null || !key.getId().equals(fluidId)) {
                throw new BridgeError(409, "source_changed", "Tank fluid no longer matches the tank read");
            }
            TankOperations.requireDrain(tank, moving);
        } else {
            key = MeOperations.resolveMeStack(storage, AEFluidKey.class, fluidId, expectedSourceToken);
            moving = key.toStack(amount);
            TankOperations.requireFill(tank, moving);
        }

        long tankBefore = TankOperations.amount(tankBalances, moving);
        long meBefore = MeOperations.count(storage, key);
        long meAfter;
        long tankAfter;
        int moved = amount;
        if (toMe) {
            moved = MeOperations.deposit(grid, storage, actor, key, amount,
                    () -> TankOperations.drain(tank, tankBalances, moving),
                    accepted -> TankOperations.restore(tank, tankBalances, moving, -accepted));
            meAfter = MeOperations.count(storage, key);
            tankAfter = tankBefore - moved;
        } else {
            meAfter = MeOperations.withdraw(grid, storage, actor, key, amount, meBefore,
                    () -> TankOperations.fill(tank, tankBalances, moving),
                    () -> TankOperations.restore(tank, tankBalances, moving, 0));
            tankAfter = tankBefore + amount;
        }

        JsonObject result = new JsonObject();
        result.addProperty("direction", toMe ? "to_me" : "from_me");
        result.addProperty("fluid", fluidId.toString());
        result.addProperty("requested", amount);
        result.addProperty("moved", moved);
        result.addProperty("tankBefore", tankBefore);
        result.addProperty("tankAfter", tankAfter);
        result.addProperty("meBefore", meBefore);
        result.addProperty("meAfter", meAfter);
        result.addProperty("totalBefore", meBefore + tankBefore);
        result.addProperty("totalAfter", meAfter + tankAfter);
        return result;
    }

    static JsonObject fillBucket(MeOperations.Endpoint endpoint, ResourceLocation fluidId,
                                 String fluidToken, String bucketToken) {
        IGrid grid = endpoint.grid();
        MEStorage storage = grid.getStorageService().getInventory();
        IActionSource actor = MeOperations.playerAction(endpoint.level());
        AEFluidKey fluid = MeOperations.resolveMeStack(storage, AEFluidKey.class, fluidId, fluidToken);
        AEItemKey emptyBucket = MeOperations.resolveMeStack(storage, AEItemKey.class,
                ForgeRegistries.ITEMS.getKey(Items.BUCKET), bucketToken);
        int amount = FluidType.BUCKET_VOLUME;
        FluidStack requested = fluid.toStack(amount);
        ItemStack filled = FluidUtil.getFilledBucket(requested);
        AEItemKey filledBucket = AEItemKey.of(filled);
        if (filledBucket == null || filled.is(Items.BUCKET)
                || FluidUtil.getFluidContained(filled)
                        .filter(contents -> TankOperations.matches(contents, requested)).isEmpty()) {
            throw new BridgeError(409, "unsupported_bucket_fluid",
                    "The exact fluid cannot be stored in a filled bucket");
        }

        MeOperations.requireWithdrawal(grid, storage, actor, fluid, amount);
        MeOperations.requireWithdrawal(grid, storage, actor, emptyBucket, 1);
        long fluidBefore = MeOperations.count(storage, fluid);
        long emptyBefore = MeOperations.count(storage, emptyBucket);
        long filledBefore = MeOperations.count(storage, filledBucket);
        long filledExpected = filledBefore == Long.MAX_VALUE ? filledBefore : filledBefore + 1;

        try {
            if (MeOperations.mutateMe(grid, storage, actor, fluid, amount, false, Actionable.MODULATE) != amount
                    || !MeOperations.matchesWithdrawal(fluidBefore, amount, MeOperations.count(storage, fluid))
                    || MeOperations.mutateMe(grid, storage, actor, emptyBucket, 1, false, Actionable.MODULATE) != 1
                    || !MeOperations.matchesWithdrawal(emptyBefore, 1, MeOperations.count(storage, emptyBucket))) {
                throw new BridgeError(409, "bucket_input_refused", "ME did not provide the requested fluid and bucket");
            }
            if (MeOperations.mutateMe(grid, storage, actor, filledBucket, 1, true, Actionable.SIMULATE) != 1
                    || MeOperations.mutateMe(grid, storage, actor, filledBucket, 1, true, Actionable.MODULATE) != 1
                    || MeOperations.count(storage, filledBucket) != filledExpected) {
                throw new BridgeError(409, "bucket_output_refused", "ME did not retain the filled bucket");
            }
        } catch (RuntimeException failure) {
            try {
                MeOperations.restoreMe(storage, actor, filledBucket, filledBefore);
                MeOperations.restoreMe(storage, actor, emptyBucket, emptyBefore);
                MeOperations.restoreMe(storage, actor, fluid, fluidBefore);
            } catch (RuntimeException rollback) {
                failure.addSuppressed(rollback);
                throw new IllegalStateException("ME bucket filling rollback did not finish", failure);
            }
            throw failure;
        }

        JsonObject result = new JsonObject();
        result.addProperty("fluid", fluidId.toString());
        result.addProperty("amount", amount);
        result.addProperty("item", filledBucket.getId().toString());
        result.addProperty("count", 1);
        result.addProperty("fluidAfter", MeOperations.count(storage, fluid));
        result.addProperty("emptyBucketsAfter", MeOperations.count(storage, emptyBucket));
        result.addProperty("filledBucketsAfter", MeOperations.count(storage, filledBucket));
        return result;
    }
}
