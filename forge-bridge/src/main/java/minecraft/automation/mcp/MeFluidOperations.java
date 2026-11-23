package minecraft.automation.mcp;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.storage.MEStorage;
import appeng.blockentity.storage.DriveBlockEntity;
import com.google.gson.JsonObject;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.registries.ForgeRegistries;

import static net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;
import static net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE;

final class MeFluidOperations {
    private MeFluidOperations() {}

    static JsonObject transfer(ServerLevel tankLevel, BlockPos tankPosition, ServerLevel meLevel,
                               BlockPos drivePosition, boolean toMe, ResourceLocation fluidId, int amount,
                               String expectedSourceToken) {
        if (amount < 1) {
            throw new BridgeError(400, "invalid_transfer", "Specify a positive transfer amount in mB");
        }
        IFluidHandler tank = TankOperations.tankAt(tankLevel, tankPosition);
        FluidStack tankBefore = TankOperations.snapshot(tank);
        IGrid grid = MeOperations.gridAt(meLevel, drivePosition);
        IActionSource actor = MeOperations.playerAction(meLevel);
        MEStorage storage = grid.getStorageService().getInventory();

        AEFluidKey key;
        if (toMe) {
            key = AEFluidKey.of(tankBefore);
            if (key == null || !key.getId().equals(fluidId)
                    || !TankOperations.fluidToken(tankBefore).equals(expectedSourceToken)) {
                throw new BridgeError(409, "source_changed", "Tank fluid no longer matches the tank read");
            }
            if (tankBefore.getAmount() < amount) {
                throw new BridgeError(409, "insufficient_fluid", "Source tank contains less fluid than requested");
            }
        } else {
            key = MeOperations.resolveMeStack(storage, AEFluidKey.class, fluidId, expectedSourceToken);
        }

        FluidStack moving = key.toStack(amount);
        if (toMe) {
            if (!TankOperations.matches(tank.drain(moving.copy(), SIMULATE), moving)) {
                throw new BridgeError(409, "drain_refused", "Source tank cannot drain the full amount");
            }
        } else {
            if ((!tankBefore.isEmpty() && !tankBefore.isFluidEqual(moving)) || !tank.isFluidValid(0, moving)) {
                throw new BridgeError(409, "incompatible_fluid", "Target tank cannot accept this fluid");
            }
            if (tank.fill(moving.copy(), SIMULATE) != amount) {
                throw new BridgeError(409, "target_full", "Target tank lacks room for the full transfer");
            }
        }

        long meBefore = MeOperations.count(storage, key);
        long meAfter;
        int tankAfter;
        int moved = amount;
        if (toMe) {
            FluidStack expectedTank = key.toStack(tankBefore.getAmount() - amount);
            moved = MeOperations.deposit(grid, storage, actor, key, amount, () -> {
                if (!TankOperations.matches(tank.drain(moving.copy(), EXECUTE), moving)
                        || !TankOperations.matches(TankOperations.snapshot(tank), expectedTank)) {
                    throw new BridgeError(409, "transfer_refused", "The source tank did not drain the planned amount");
                }
            }, accepted -> TankOperations.restore(tank, key.toStack(tankBefore.getAmount() - accepted), moving));
            meAfter = MeOperations.count(storage, key);
            tankAfter = TankOperations.snapshot(tank).getAmount();
        } else {
            MeOperations.requireWithdrawal(grid, storage, actor, key, amount);
            meAfter = Math.subtractExact(meBefore, amount);
            tankAfter = tankBefore.getAmount() + amount;
            FluidStack expectedTank = key.toStack(tankAfter);
            try {
                if (MeOperations.mutateMe(grid, storage, actor, key, amount, false, Actionable.MODULATE) != amount) {
                    throw new BridgeError(409, "me_transfer_refused", "ME did not extract the planned amount");
                }
                if (tank.fill(moving.copy(), EXECUTE) != amount) {
                    throw new BridgeError(409, "transfer_refused", "The target tank did not accept the planned amount");
                }
                if (MeOperations.count(storage, key) != meAfter
                        || !TankOperations.matches(TankOperations.snapshot(tank), expectedTank)) {
                    throw new BridgeError(409, "balance_mismatch", "Tank and ME did not retain the planned balances");
                }
            } catch (RuntimeException failure) {
                try {
                    TankOperations.restore(tank, tankBefore, moving);
                    MeOperations.restoreMe(storage, actor, key, meBefore);
                } catch (RuntimeException rollbackError) {
                    failure.addSuppressed(rollbackError);
                    throw new IllegalStateException("ME fluid transfer rollback did not finish", failure);
                }
                throw failure;
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("direction", toMe ? "to_me" : "from_me");
        result.addProperty("fluid", fluidId.toString());
        result.addProperty("requested", amount);
        result.addProperty("moved", moved);
        result.addProperty("tankBefore", tankBefore.getAmount());
        result.addProperty("tankAfter", tankAfter);
        result.addProperty("meBefore", meBefore);
        result.addProperty("meAfter", meAfter);
        result.addProperty("totalBefore", meBefore + tankBefore.getAmount());
        result.addProperty("totalAfter", meAfter + tankAfter);
        return result;
    }

    static JsonObject prepareTestFluidCell(ServerLevel level, BlockPos position) {
        if (!(level.getBlockEntity(position) instanceof DriveBlockEntity drive) || drive.isRemoved()) {
            throw new BridgeError(409, "not_me_drive", "The position must contain an ME Drive");
        }
        Item cell = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse("ae2:fluid_storage_cell_1k"));
        if (cell == null || cell == net.minecraft.world.item.Items.AIR) {
            throw new BridgeError(409, "fixture_unavailable", "The AE2 1k fluid storage cell is unavailable");
        }
        var inventory = drive.getInternalInventory();
        int emptySlot = -1;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack existing = inventory.getStackInSlot(slot);
            if (existing.is(cell)) {
                return cellResult(slot, false);
            }
            if (emptySlot < 0 && existing.isEmpty()) {
                emptySlot = slot;
            }
        }
        if (emptySlot < 0) {
            throw new BridgeError(409, "drive_full", "The test ME Drive has no empty cell slot");
        }
        inventory.setItemDirect(emptySlot, new ItemStack(cell));
        if (!inventory.getStackInSlot(emptySlot).is(cell)) {
            throw new BridgeError(409, "fixture_refused", "The ME Drive did not retain the fluid cell");
        }
        return cellResult(emptySlot, true);
    }

    private static JsonObject cellResult(int slot, boolean installed) {
        JsonObject result = new JsonObject();
        result.addProperty("item", "ae2:fluid_storage_cell_1k");
        result.addProperty("slot", slot);
        result.addProperty("installed", installed);
        return result;
    }
}
