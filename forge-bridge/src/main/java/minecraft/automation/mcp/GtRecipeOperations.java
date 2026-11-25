package minecraft.automation.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class GtRecipeOperations {
    private static final int LIMIT = 20;

    private GtRecipeOperations() {}

    static JsonObject find(MinecraftServer server, ResourceLocation id, boolean fluid, ResourceLocation type) {
        var recipes = server.getRecipeManager().getRecipes().stream()
                .filter(GTRecipe.class::isInstance).map(GTRecipe.class::cast)
                .filter(recipe -> type == null || type.equals(recipe.recipeType.registryName))
                .filter(recipe -> produces(recipe, id, fluid))
                .sorted(Comparator.comparing(recipe -> recipe.id))
                .limit(LIMIT + 1L).toList();
        JsonArray entries = new JsonArray();
        recipes.stream().limit(LIMIT).forEach(recipe -> entries.add(describe(recipe)));
        JsonObject result = new JsonObject();
        result.add("recipes", entries);
        result.addProperty("truncated", recipes.size() > LIMIT);
        return result;
    }

    private static boolean produces(GTRecipe recipe, ResourceLocation id, boolean fluid) {
        var contents = fluid ? recipe.getOutputContents(FluidRecipeCapability.CAP)
                : recipe.getOutputContents(ItemRecipeCapability.CAP);
        return contents.stream().flatMap(content -> variants(content).stream()).anyMatch(id::equals);
    }

    private static JsonObject describe(GTRecipe recipe) {
        JsonObject entry = new JsonObject();
        entry.addProperty("recipe", recipe.id.toString());
        entry.addProperty("type", recipe.recipeType.registryName.toString());
        entry.addProperty("ticks", recipe.duration);
        entry.addProperty("EUt", recipe.getTickInputContents(EURecipeCapability.CAP).stream()
                .mapToLong(content -> ((Number) content.getContent()).longValue()).sum());
        entry.add("itemInputs", contents(recipe.getInputContents(ItemRecipeCapability.CAP)));
        entry.add("itemOutputs", contents(recipe.getOutputContents(ItemRecipeCapability.CAP)));
        entry.add("fluidInputs", contents(recipe.getInputContents(FluidRecipeCapability.CAP)));
        entry.add("fluidOutputs", contents(recipe.getOutputContents(FluidRecipeCapability.CAP)));
        return entry;
    }

    private static JsonArray contents(List<Content> contents) {
        JsonArray result = new JsonArray();
        for (Content content : contents) {
            Object value = content.getContent();
            JsonObject info = new JsonObject();
            long amount = value instanceof SizedIngredient sized ? sized.getAmount()
                    : value instanceof FluidIngredient fluid ? fluid.getAmount() : 1;
            info.addProperty("amount", amount);
            info.addProperty("chance", content.chance);
            info.addProperty("maxChance", content.maxChance);
            JsonArray examples = new JsonArray();
            variants(content).stream().limit(8).forEach(id -> examples.add(id.toString()));
            info.add("examples", examples);
            result.add(info);
        }
        return result;
    }

    private static List<ResourceLocation> variants(Content content) {
        if (content.getContent() instanceof Ingredient ingredient) {
            return Arrays.stream(ingredient.getItems())
                    .map(stack -> ForgeRegistries.ITEMS.getKey(stack.getItem())).distinct().toList();
        }
        if (content.getContent() instanceof FluidIngredient ingredient) {
            return Arrays.stream(ingredient.getStacks())
                    .map(stack -> ForgeRegistries.FLUIDS.getKey(stack.getFluid())).distinct().toList();
        }
        return List.of();
    }
}
