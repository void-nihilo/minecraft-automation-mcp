package minecraft.automation.mcp;

import appeng.api.stacks.AEItemKey;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

final class HostInventoryOperations {
    private HostInventoryOperations() {}

    static JsonObject contents(MinecraftServer server) {
        Inventory inventory = host(server).getInventory();
        JsonArray items = new JsonArray();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                JsonObject item = new JsonObject();
                item.addProperty("slot", slot);
                item.addProperty("item", ForgeRegistries.ITEMS.getKey(stack.getItem()).toString());
                item.addProperty("count", stack.getCount());
                item.addProperty("stackToken", ItemHandlerOperations.stackToken(stack));
                items.add(item);
            }
        }
        JsonObject result = new JsonObject();
        result.add("items", items);
        return result;
    }

    static JsonObject transfer(MinecraftServer server, MeOperations.Endpoint endpoint, int slot,
                               ResourceLocation item, int amount, String token, boolean toMe) {
        if (slot < 0 || slot >= Inventory.INVENTORY_SIZE || amount < 1) {
            throw new BridgeError(400, "invalid_inventory_transfer", "Use a main inventory slot and a positive amount");
        }
        ServerPlayer player = host(server);
        Inventory inventory = player.getInventory();
        ItemStack source = inventory.getItem(slot).copy();
        var grid = endpoint.grid();
        var storage = grid.getStorageService().getInventory();
        var actor = MeOperations.playerAction(endpoint.level());
        AEItemKey key;
        int moved;
        if (toMe) {
            key = AEItemKey.of(source);
            if (key == null || !key.getId().equals(item) || !ItemHandlerOperations.stackToken(source).equals(token)) {
                throw new BridgeError(409, "source_changed", "The host inventory slot no longer matches the requested stack");
            }
            if (source.getCount() < amount || !ItemStack.isSameItemSameTags(source, key.toStack())) {
                throw new BridgeError(409, "invalid_source_stack", "The inventory slot cannot supply the requested stack exactly");
            }
            moved = MeOperations.deposit(grid, storage, actor, key, amount,
                    () -> inventory.setItem(slot, source.copyWithCount(source.getCount() - amount)),
                    accepted -> inventory.setItem(slot, source.copyWithCount(source.getCount() - accepted)));
        } else {
            key = MeOperations.resolveMeStack(storage, AEItemKey.class, item, token);
            ItemStack stack = key.toStack();
            if ((!source.isEmpty() && !ItemStack.isSameItemSameTags(source, stack))
                    || amount > Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize()) - source.getCount()) {
                throw new BridgeError(409, "target_full", "The host inventory slot does not have enough room");
            }
            MeOperations.withdraw(grid, storage, actor, key, amount, MeOperations.count(storage, key),
                    () -> inventory.setItem(slot, stack.copyWithCount(source.getCount() + amount)),
                    () -> inventory.setItem(slot, source));
            moved = amount;
        }
        inventory.setChanged();
        player.inventoryMenu.broadcastChanges();
        JsonObject result = new JsonObject();
        result.addProperty("item", item.toString());
        result.addProperty("direction", toMe ? "to_me" : "from_me");
        result.addProperty("moved", moved);
        result.addProperty("slotCount", inventory.getItem(slot).getCount());
        result.addProperty("meAfter", MeOperations.count(storage, key));
        return result;
    }

    static ServerPlayer host(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(server.getSingleplayerProfile().getName());
        if (player == null) {
            throw new BridgeError(409, "no_player", "The singleplayer host has not joined the world yet");
        }
        return player;
    }
}
