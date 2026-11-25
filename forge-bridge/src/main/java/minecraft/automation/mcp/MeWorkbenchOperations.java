package minecraft.automation.mcp;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MeWorkbenchOperations {
    private static final int GRID_SIZE = 9;

    private MeWorkbenchOperations() {}

    static JsonObject describe(ServerLevel level, ResourceLocation id) {
        return describeRecipe(level, recipe(level, id));
    }

    static JsonObject findByOutput(ServerLevel level, ResourceLocation item) {
        JsonArray matches = new JsonArray();
        boolean truncated = false;
        for (CraftingRecipe recipe : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack product = recipe.getResultItem(level.registryAccess());
            if (!product.isEmpty() && item.equals(ForgeRegistries.ITEMS.getKey(product.getItem()))) {
                if (matches.size() == 24) {
                    truncated = true;
                    break;
                }
                matches.add(describeRecipe(level, recipe));
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("item", item.toString());
        result.add("recipes", matches);
        result.addProperty("truncated", truncated);
        return result;
    }

    private static JsonObject describeRecipe(ServerLevel level, CraftingRecipe recipe) {
        JsonObject result = new JsonObject();
        result.addProperty("recipe", recipe.getId().toString());
        if (recipe instanceof ShapedRecipe shaped) {
            result.addProperty("width", shaped.getWidth());
            result.addProperty("height", shaped.getHeight());
        }
        ItemStack output = recipe.getResultItem(level.registryAccess());
        if (!output.isEmpty()) {
            result.add("output", itemInfo(output));
        }
        JsonArray ingredients = new JsonArray();
        for (var ingredient : recipe.getIngredients()) {
            JsonObject row = new JsonObject();
            JsonArray examples = new JsonArray();
            ItemStack[] variants = ingredient.getItems();
            for (int i = 0; i < variants.length && i < 12; i++) {
                examples.add(ForgeRegistries.ITEMS.getKey(variants[i].getItem()).toString());
            }
            row.addProperty("empty", ingredient.isEmpty());
            row.addProperty("variantCount", variants.length);
            row.add("examples", examples);
            ingredients.add(row);
        }
        result.add("ingredients", ingredients);
        return result;
    }

    static JsonObject craft(MeOperations.Endpoint endpoint, ResourceLocation recipeId, JsonArray gridRequest) {
        CraftingRecipe recipe = recipe(endpoint.level(), recipeId);
        var grid = endpoint.grid();
        var stacks = grid.getStorageService().getInventory().getAvailableStacks();
        return craft(endpoint.level(), grid, recipe, resolveGrid(stacks, gridRequest)).result();
    }

    static JsonObject craftBatch(MeOperations.Endpoint endpoint, ResourceLocation recipeId,
                                 JsonArray gridRequest, int repetitions) {
        var recipe = recipe(endpoint.level(), recipeId);
        var grid = endpoint.grid();
        var storage = grid.getStorageService().getInventory();
        AEItemKey[] selected = resolveGrid(storage.getAvailableStacks(), gridRequest);
        JsonObject result = new JsonObject();
        result.addProperty("recipe", recipeId.toString());
        result.addProperty("requested", repetitions);
        JsonArray crafts = new JsonArray();
        for (int index = 0; index < repetitions; index++) {
            try {
                Craft craft = craft(endpoint.level(), grid, recipe, selected);
                crafts.add(craft.result());
                for (int slot = 0; slot < GRID_SIZE; slot++) {
                    ItemStack remainder = craft.remainders().get(slot);
                    if (selected[slot] != null && !remainder.isEmpty() && remainder.isDamageableItem()
                            && remainder.is(selected[slot].getItem())) {
                        selected[slot] = AEItemKey.of(remainder);
                    }
                }
            } catch (BridgeError exception) {
                JsonObject error = new JsonObject();
                error.addProperty("code", exception.code());
                error.addProperty("message", exception.getMessage());
                result.add("blocked", error);
                break;
            }
        }
        result.addProperty("completed", crafts.size());
        result.add("crafts", crafts);
        return result;
    }

    private static AEItemKey[] resolveGrid(KeyCounter stacks, JsonArray gridRequest) {
        if (gridRequest.size() != GRID_SIZE) {
            throw new BridgeError(400, "invalid_crafting_grid", "Supply nine row-major grid slots");
        }
        AEItemKey[] selected = new AEItemKey[GRID_SIZE];
        for (int slot = 0; slot < GRID_SIZE; slot++) {
            JsonElement requested = gridRequest.get(slot);
            if (!requested.isJsonNull()) {
                if (!requested.isJsonObject()) {
                    throw new BridgeError(400, "invalid_crafting_grid", "A slot must be an item object or null");
                }
                JsonObject entry = requested.getAsJsonObject();
                ResourceLocation id = MinecraftAutomationBridge.resourceId(entry, "item");
                String token = MinecraftAutomationBridge.stringField(entry, "expectedSourceToken");
                selected[slot] = MeOperations.resolveMeStack(stacks, AEItemKey.class, id, token);
            }
        }
        return selected;
    }

    private static Craft craft(ServerLevel level, appeng.api.networking.IGrid grid, CraftingRecipe recipe,
                               AEItemKey[] selected) {
        var storage = grid.getStorageService().getInventory();
        var actor = MeOperations.playerAction(level);
        var initial = storage.getAvailableStacks();
        var craftingGrid = new TransientCraftingContainer(new WorkbenchMenu(), 3, 3);
        Map<AEItemKey, Integer> inputs = new LinkedHashMap<>();
        List<ItemStack> sourceSlots = new ArrayList<>(GRID_SIZE);
        for (int slot = 0; slot < GRID_SIZE; slot++) {
            AEItemKey key = selected[slot];
            ItemStack item = ItemStack.EMPTY;
            if (key != null) {
                item = key.toStack();
                inputs.merge(key, 1, Integer::sum);
            }
            sourceSlots.add(item.copy());
            craftingGrid.setItem(slot, item);
        }
        for (var input : inputs.entrySet()) {
            if (initial.get(input.getKey()) < input.getValue()
                    || MeOperations.mutateMe(grid, storage, actor, input.getKey(),
                    input.getValue(), false, Actionable.SIMULATE) != input.getValue()) {
                throw new BridgeError(409, "insufficient_items", "ME cannot provide " + input.getKey().getId());
            }
        }

        ItemStack output;
        List<ItemStack> remainders;
        Player previousPlayer = ForgeHooks.getCraftingPlayer();
        ForgeHooks.setCraftingPlayer(null);
        try {
            if (!recipe.matches(craftingGrid, level)) {
                throw new BridgeError(409, "recipe_mismatch", "The selected items do not match the current game recipe");
            }
            output = recipe.assemble(craftingGrid, level.registryAccess()).copy();
            if (output.isEmpty()) {
                throw new BridgeError(409, "empty_crafting_result", "The game recipe returned no crafting result");
            }
            remainders = recipe.getRemainingItems(craftingGrid).stream().map(ItemStack::copy).toList();
        } finally {
            ForgeHooks.setCraftingPlayer(previousPlayer);
        }
        if (remainders.size() != GRID_SIZE) {
            throw new BridgeError(409, "unsupported_remainders", "The recipe returned an invalid remaining-items list");
        }

        Map<AEItemKey, Integer> produced = new LinkedHashMap<>();
        addOutput(produced, output);
        remainders.forEach(stack -> addOutput(produced, stack));
        Map<AEItemKey, Long> balances = new LinkedHashMap<>();
        inputs.keySet().forEach(key -> balances.put(key, initial.get(key)));
        produced.keySet().forEach(key -> balances.putIfAbsent(key, initial.get(key)));
        Map<AEItemKey, Long> expectedBalances = new LinkedHashMap<>(balances);

        try {
            for (var entry : inputs.entrySet()) {
                var key = entry.getKey();
                int amount = entry.getValue();
                long extracted = MeOperations.mutateMe(grid, storage, actor, key, amount, false, Actionable.MODULATE);
                long after = MeOperations.count(storage, key);
                if (extracted != amount || !MeOperations.matchesWithdrawal(balances.get(key), amount, after)) {
                    throw new BridgeError(409, "craft_input_refused", "ME did not provide the planned input " + key.getId());
                }
                expectedBalances.put(key, after);
            }
            for (var entry : produced.entrySet()) {
                var key = entry.getKey();
                int amount = entry.getValue();
                long before = expectedBalances.get(key);
                long expected = before > Long.MAX_VALUE - amount ? Long.MAX_VALUE : before + amount;
                if (MeOperations.mutateMe(grid, storage, actor, key, amount, true, Actionable.SIMULATE) != amount
                        || MeOperations.mutateMe(grid, storage, actor, key, amount, true, Actionable.MODULATE) != amount
                        || MeOperations.count(storage, key) != expected) {
                    throw new BridgeError(409, "craft_output_refused", "ME cannot retain the result or remainder " + key.getId());
                }
                expectedBalances.put(key, expected);
            }
            for (var entry : expectedBalances.entrySet()) {
                if (MeOperations.count(storage, entry.getKey()) != entry.getValue()) {
                    throw new BridgeError(409, "craft_balance_mismatch", "ME did not retain the planned item balance");
                }
            }
        } catch (RuntimeException failure) {
            try {
                for (var entry : balances.entrySet()) {
                    if (MeOperations.count(storage, entry.getKey()) > entry.getValue()) {
                        MeOperations.restoreMe(storage, actor, entry.getKey(), entry.getValue());
                    }
                }
                for (var entry : balances.entrySet()) {
                    MeOperations.restoreMe(storage, actor, entry.getKey(), entry.getValue());
                }
            } catch (RuntimeException rollback) {
                failure.addSuppressed(rollback);
                throw new IllegalStateException("ME crafting rollback did not finish", failure);
            }
            throw failure;
        }

        JsonObject result = new JsonObject();
        result.addProperty("recipe", recipe.getId().toString());
        result.add("output", itemInfo(output));
        JsonObject withdrawn = new JsonObject();
        Map<ResourceLocation, Integer> inputCounts = new LinkedHashMap<>();
        inputs.forEach((key, amount) -> inputCounts.merge(key.getId(), amount, Integer::sum));
        inputCounts.forEach((id, amount) -> withdrawn.addProperty(id.toString(), amount));
        result.add("withdrawn", withdrawn);
        JsonArray returned = new JsonArray();
        for (int slot = 0; slot < remainders.size(); slot++) {
            ItemStack stack = remainders.get(slot);
            if (!stack.isEmpty()) {
                JsonObject entry = itemInfo(stack);
                entry.addProperty("slot", slot);
                ItemStack original = sourceSlots.get(slot);
                if (original.is(stack.getItem()) && original.getMaxDamage() > 0) {
                    entry.addProperty("damageBefore", original.getDamageValue());
                }
                returned.add(entry);
            }
        }
        result.add("remainders", returned);
        return new Craft(result, remainders);
    }

    private record Craft(JsonObject result, List<ItemStack> remainders) {}

    static ItemStack encodePattern(ServerLevel level, ResourceLocation recipeId, ItemStack[] inputs) {
        CraftingRecipe recipe = recipe(level, recipeId);
        var grid = new TransientCraftingContainer(new WorkbenchMenu(), 3, 3);
        for (int slot = 0; slot < GRID_SIZE; slot++) {
            grid.setItem(slot, inputs[slot]);
        }
        Player previousPlayer = ForgeHooks.getCraftingPlayer();
        ForgeHooks.setCraftingPlayer(null);
        try {
            if (!recipe.matches(grid, level)) {
                throw new BridgeError(409, "recipe_mismatch", "The selected items do not match the current game recipe");
            }
            ItemStack output = recipe.assemble(grid, level.registryAccess());
            if (output.isEmpty() || recipe.isSpecial()) {
                throw new BridgeError(409, "unsupported_pattern_recipe", "AE2 requires a recipe with a fixed crafting result");
            }
            return PatternDetailsHelper.encodeCraftingPattern(recipe, inputs, output, false, false);
        } finally {
            ForgeHooks.setCraftingPlayer(previousPlayer);
        }
    }

    private static void addOutput(Map<AEItemKey, Integer> produced, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        AEItemKey key = AEItemKey.of(stack);
        if (key == null || !ItemStack.isSameItemSameTags(stack, key.toStack())) {
            throw new BridgeError(409, "unsupported_crafting_output", "ME cannot preserve the result stack exactly");
        }
        produced.merge(key, stack.getCount(), Integer::sum);
    }

    private static CraftingRecipe recipe(ServerLevel level, ResourceLocation id) {
        return level.getRecipeManager().byKey(id)
                .filter(CraftingRecipe.class::isInstance).map(CraftingRecipe.class::cast)
                .orElseThrow(() -> new BridgeError(404, "crafting_recipe_not_found", "No registered workbench recipe: " + id));
    }

    private static JsonObject itemInfo(ItemStack stack) {
        JsonObject json = new JsonObject();
        json.addProperty("item", ForgeRegistries.ITEMS.getKey(stack.getItem()).toString());
        json.addProperty("count", stack.getCount());
        if (stack.getMaxDamage() > 0) {
            json.addProperty("damage", stack.getDamageValue());
            json.addProperty("maxDamage", stack.getMaxDamage());
        }
        return json;
    }

    static JsonObject prepareTestStock(ServerLevel level, BlockPos drive, boolean needsFlint) {
        Map<AEKey, Integer> required = new LinkedHashMap<>();
        if (needsFlint) {
            required.put(AEItemKey.of(Items.FLINT), 3);
        } else {
            requireTestItem(required, "gtceu:bronze_plate", 6);
            required.put(AEItemKey.of(Items.BRICKS), 1);
            requireTestItem(required, "gtceu:iron_hammer", 1);
            requireTestItem(required, "gtceu:iron_wrench", 1);
        }
        var grid = new MeOperations.Endpoint(level, drive, null).grid();
        var actor = MeOperations.playerAction(level);
        var storage = grid.getStorageService().getInventory();
        Map<AEKey, Long> balances = new LinkedHashMap<>();
        JsonObject seeded = new JsonObject();
        try {
            for (var entry : required.entrySet()) {
                AEKey key = entry.getKey();
                long before = MeOperations.count(storage, key);
                balances.put(key, before);
                long shortage = entry.getValue() - before;
                if (shortage <= 0) {
                    continue;
                }
                if (MeOperations.mutateMe(grid, storage, actor, key, shortage, true, Actionable.SIMULATE) != shortage
                        || MeOperations.mutateMe(grid, storage, actor, key, shortage, true, Actionable.MODULATE) != shortage
                        || MeOperations.count(storage, key) != entry.getValue()) {
                    throw new BridgeError(409, "fixture_refused", "ME did not retain the test input " + key.getId());
                }
                seeded.addProperty(key.getId().toString(), shortage);
            }
        } catch (RuntimeException failure) {
            balances.forEach((key, before) -> MeOperations.restoreMe(storage, actor, key, before));
            throw failure;
        }
        JsonObject result = new JsonObject();
        result.add("seeded", seeded);
        return result;
    }

    private static void requireTestItem(Map<AEKey, Integer> required, String name, int amount) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(name));
        if (item == null || item == Items.AIR) {
            throw new BridgeError(409, "fixture_unavailable", "The test item is unavailable: " + name);
        }
        required.put(AEItemKey.of(item.getDefaultInstance()), amount);
    }

    private static final class WorkbenchMenu extends AbstractContainerMenu {
        private WorkbenchMenu() {
            super(null, 0);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }
}
