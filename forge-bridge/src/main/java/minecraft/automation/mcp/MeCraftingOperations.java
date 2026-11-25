package minecraft.automation.mcp;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionHost;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.core.definitions.AEItems;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingLinkNexus;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

final class MeCraftingOperations {
    private final Map<UUID, Order> orders = new HashMap<>();
    private final Map<UUID, Future<ICraftingPlan>> calculations = new LinkedHashMap<>();

    static JsonObject installPattern(MeOperations.Endpoint endpoint, BlockPos providerPosition, Direction providerSide,
                                     int slot, ResourceLocation recipe, JsonArray requestedGrid) {
        ServerLevel level = endpoint.level();
        IGrid grid = endpoint.grid();
        if (requestedGrid.size() != 9) {
            throw new IllegalArgumentException("Supply nine recipe slots");
        }

        var inventory = grid.getStorageService().getInventory().getAvailableStacks();
        ItemStack[] inputs = new ItemStack[9];
        for (int i = 0; i < inputs.length; i++) {
            var entry = requestedGrid.get(i);
            inputs[i] = ItemStack.EMPTY;
            if (!entry.isJsonNull()) {
                if (!entry.isJsonObject()) {
                    throw new IllegalArgumentException("Each recipe slot must be an item object or null");
                }
                var input = entry.getAsJsonObject();
                var item = MinecraftAutomationBridge.resourceId(input, "item");
                inputs[i] = input.has("meToken")
                        ? MeOperations.resolveMeStack(inventory, AEItemKey.class, item,
                                MinecraftAutomationBridge.stringField(input, "meToken")).toStack()
                        : plainItem(item);
            }
        }

        var provider = providerAt(level, providerPosition, providerSide, grid);
        var encoded = MeWorkbenchOperations.encodePattern(level, recipe, inputs);
        JsonObject result = installEncodedPattern(level, grid, provider, slot, encoded);
        result.addProperty("recipe", recipe.toString());
        return result;
    }

    static JsonObject installProcessingPattern(MeOperations.Endpoint endpoint, BlockPos providerPosition, Direction providerSide,
                                               int slot, JsonArray inputs, JsonArray outputs) {
        ServerLevel level = endpoint.level();
        IGrid grid = endpoint.grid();
        var provider = providerAt(level, providerPosition, providerSide, grid);
        var inventory = grid.getStorageService().getInventory().getAvailableStacks();
        var encoded = PatternDetailsHelper.encodeProcessingPattern(
                processingItems(inputs, 9, inventory), processingItems(outputs, 3, inventory));
        return installEncodedPattern(level, grid, provider, slot, encoded);
    }

    private static GenericStack[] processingItems(JsonArray entries, int max,
                                                   appeng.api.stacks.KeyCounter inventory) {
        if (entries.isEmpty() || entries.size() > max) {
            throw new IllegalArgumentException("Invalid number of processing pattern items");
        }
        var stacks = new GenericStack[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            if (!entries.get(i).isJsonObject()) {
                throw new IllegalArgumentException("Expected a processing pattern item");
            }
            var entry = entries.get(i).getAsJsonObject();
            var item = MinecraftAutomationBridge.resourceId(entry, "item");
            int amount = MinecraftAutomationBridge.coordinate(entry, "amount");
            if (amount < 1) {
                throw new IllegalArgumentException("Specify a positive processing item amount");
            }
            AEItemKey key = entry.has("meToken")
                    ? MeOperations.resolveMeStack(inventory, AEItemKey.class, item,
                            MinecraftAutomationBridge.stringField(entry, "meToken"))
                    : AEItemKey.of(plainItem(item));
            stacks[i] = new GenericStack(key, amount);
        }
        return stacks;
    }

    private static PatternTarget providerAt(ServerLevel level, BlockPos position, Direction side, IGrid grid) {
        var candidates = new LinkedHashSet<PatternContainer>();
        var entity = level.getBlockEntity(position);
        if (entity instanceof PatternContainer container) {
            candidates.add(container);
        }
        if (ModList.get().isLoaded("gtceu")
                && MetaMachine.getMachine(level, position) instanceof PatternContainer container) {
            candidates.add(container);
        }
        var nodeHost = GridHelper.getNodeHost(level, position);
        for (Direction face : side == null ? Direction.values() : new Direction[] {side}) {
            if (entity instanceof IPartHost parts && parts.getPart(face) instanceof PatternContainer container) {
                candidates.add(container);
            }
            var node = nodeHost == null ? null : nodeHost.getGridNode(face);
            if (node != null && node.getOwner() instanceof PatternContainer container) {
                candidates.add(container);
            }
        }
        PatternTarget selected = null;
        for (PatternContainer container : candidates) {
            if (container.getGrid() != grid) {
                continue;
            }
            ICraftingProvider provider = craftingProvider(container, grid);
            if (provider != null) {
                if (selected != null) {
                    throw new BridgeError(409, "ambiguous_provider", "Several pattern containers are available here; specify the provider side");
                }
                selected = new PatternTarget(container.getTerminalPatternInventory(), provider);
            }
        }
        if (selected == null) {
            throw new BridgeError(409, "provider_unavailable", "Use an active AE2 pattern container on this ME network");
        }
        return selected;
    }

    private static ICraftingProvider craftingProvider(PatternContainer container, IGrid grid) {
        IGridNode node = container instanceof IActionHost host ? host.getActionableNode()
                : container instanceof IPart part ? part.getGridNode() : null;
        if (node != null && node.getGrid() == grid && node.isActive()) {
            ICraftingProvider provider = node.getService(ICraftingProvider.class);
            if (provider != null) {
                return provider;
            }
        }
        for (IGridNode candidate : grid.getNodes()) {
            if (candidate.getOwner() == container && candidate.isActive()) {
                ICraftingProvider provider = candidate.getService(ICraftingProvider.class);
                if (provider != null) {
                    return provider;
                }
            }
        }
        return null;
    }

    private record PatternTarget(InternalInventory inventory, ICraftingProvider provider) {
        boolean registered(IPatternDetails details) {
            return provider.getAvailablePatterns().stream()
                    .anyMatch(pattern -> pattern.getDefinition().equals(details.getDefinition()));
        }
    }

    private static JsonObject installEncodedPattern(ServerLevel level, IGrid grid, PatternTarget provider,
                                                     int slot, ItemStack encoded) {
        var patterns = provider.inventory();
        if (slot < 0 || slot >= patterns.size()) {
            throw new IllegalArgumentException("Supply a valid provider slot");
        }
        var storage = grid.getStorageService().getInventory();
        var details = PatternDetailsHelper.decodePattern(encoded, level);
        if (details == null) {
            throw new BridgeError(409, "invalid_pattern", "AE2 could not decode the encoded pattern");
        }
        ItemStack old = patterns.getStackInSlot(slot);
        boolean alreadyInstalled = ItemStack.matches(old, encoded);
        AEItemKey blank = AEItemKey.of(AEItems.BLANK_PATTERN.asItem());
        long blanksBefore = MeOperations.count(storage, blank);
        if (alreadyInstalled) {
            if (!provider.registered(details)) {
                throw new BridgeError(409, "pattern_unregistered", "The installed stack is not registered by this provider");
            }
        } else {
            if (!old.isEmpty() || !patterns.insertItem(slot, encoded.copy(), true).isEmpty()) {
                throw new BridgeError(409, "provider_slot_unavailable", "Choose an empty pattern provider slot");
            }
            var actor = MeOperations.playerAction(level);
            if (MeOperations.mutateMe(grid, storage, actor, blank, 1, false, Actionable.SIMULATE) != 1) {
                throw new BridgeError(409, "no_blank_pattern", "ME needs one blank pattern");
            }
            MeOperations.withdraw(grid, storage, actor, blank, 1, blanksBefore, () -> {
                if (!patterns.insertItem(slot, encoded.copy(), false).isEmpty()
                        || !ItemStack.matches(patterns.getStackInSlot(slot), encoded)
                        || !provider.registered(details)) {
                    throw new BridgeError(409, "pattern_install_refused", "AE2 did not register the pattern");
                }
            }, () -> {
                ItemStack current = patterns.getStackInSlot(slot);
                if (ItemStack.matches(current, encoded)) {
                    patterns.extractItem(slot, 1, false);
                }
                if (!patterns.getStackInSlot(slot).isEmpty()) {
                    throw new IllegalStateException("The pattern container did not restore its empty slot");
                }
            });
        }
        JsonObject result = stack(details.getPrimaryOutput());
        result.addProperty("slot", slot);
        result.addProperty("alreadyInstalled", alreadyInstalled);
        result.addProperty("blankPatternsInMe", MeOperations.count(storage, blank));
        return result;
    }

    static JsonObject craftables(MeOperations.Endpoint endpoint, ResourceLocation item) {
        IGrid grid = endpoint.grid();
        var service = grid.getCraftingService();
        var inventory = grid.getStorageService().getInventory().getAvailableStacks();
        JsonArray variants = new JsonArray();
        for (var key : service.getCraftables(k -> k instanceof AEItemKey && k.getId().equals(item))) {
            JsonObject variant = stack(new GenericStack(key, inventory.get(key)));
            variant.addProperty("patterns", service.getCraftingFor(key).size());
            variants.add(variant);
        }
        JsonObject result = new JsonObject();
        result.addProperty("item", item.toString());
        result.add("variants", variants);
        return result;
    }

    JsonObject order(MeOperations.Endpoint endpoint, UUID id, ResourceLocation item, String variant, int amount) {
        ServerLevel level = endpoint.level();
        if (amount < 1) {
            throw new IllegalArgumentException("Specify a positive crafting amount");
        }
        var request = new OrderRequest(endpoint, item, variant, amount);
        var existing = orders.get(id);
        if (existing != null) {
            if (!existing.request.equals(request)) {
                throw new BridgeError(409, "order_id_in_use", "Order ID already belongs to a different request");
            }
            return existing.status();
        }

        IGrid grid = endpoint.grid();
        var service = grid.getCraftingService();
        var keys = service.getCraftables(key -> key instanceof AEItemKey && key.getId().equals(item)
                && (variant == null || variant.equals(variantId(key))));
        if (keys.size() != 1) {
            throw new BridgeError(409, keys.isEmpty() ? "not_craftable" : "ambiguous_output",
                    keys.isEmpty() ? "AE2 has no active pattern for this item" : "Select a craftable variant");
        }
        var output = (AEItemKey) keys.iterator().next();
        var actor = MeOperations.playerAction(level);
        var calculation = service.beginCraftingCalculation(level, () -> actor, output, amount,
                CalculationStrategy.REPORT_MISSING_ITEMS);
        var order = new Order(id, request, grid, output);
        orders.put(id, order);
        calculations.put(id, calculation);
        return order.status();
    }

    JsonObject status(UUID id, boolean cancel) {
        Order order = orders.get(id);
        if (order == null) {
            throw new BridgeError(404, "unknown_order", "No such order in the current world session");
        }
        if (cancel) {
            var calculation = calculations.remove(id);
            if (calculation != null) {
                calculation.cancel(true);
                order.state = "canceled";
            } else if (order.link != null && order.cpu.craftingLogic.getLastLink() == order.link
                    && !order.link.isDone() && !order.link.isCanceled()) {
                order.cpu.cancelJob();
            }
        }
        return order.status();
    }

    void tick() {
        var pending = calculations.entrySet().iterator();
        while (pending.hasNext()) {
            var entry = pending.next();
            if (!entry.getValue().isDone()) {
                continue;
            }
            pending.remove();
            Order order = orders.get(entry.getKey());
            try {
                order.submit(entry.getValue().get());
            } catch (ExecutionException | RuntimeException failure) {
                order.fail(failure instanceof BridgeError error ? error.code() : "crafting_calculation_failed");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                order.fail("crafting_calculation_interrupted");
            }
        }
    }

    void clear() {
        calculations.values().forEach(future -> future.cancel(true));
        calculations.clear();
        orders.clear();
    }

    private static ItemStack plainItem(ResourceLocation id) {
        var item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null || item == Items.AIR) {
            throw new BridgeError(400, "unknown_item", "Unknown recipe ingredient: " + id);
        }
        return item.getDefaultInstance();
    }

    private static JsonObject stack(GenericStack stack) {
        JsonObject result = new JsonObject();
        result.addProperty("keyType", stack.what().getType().getId().toString());
        result.addProperty("item", stack.what().getId().toString());
        result.addProperty("variant", variantId(stack.what()));
        result.addProperty("amount", stack.amount());
        return result;
    }

    private static String variantId(AEKey key) {
        return Hashing.sha256().hashString(key.toTag().toString(), StandardCharsets.UTF_8).toString();
    }

    private record OrderRequest(MeOperations.Endpoint endpoint, ResourceLocation item, String variant, int amount) {}

    private static final class Order {
        final UUID id;
        final OrderRequest request;
        final IGrid grid;
        final AEItemKey output;
        String state = "calculating";
        String error;
        JsonArray missing;
        CraftingCPUCluster cpu;
        ICraftingLink link;
        long before;

        Order(UUID id, OrderRequest request, IGrid grid, AEItemKey output) {
            this.id = id;
            this.request = request;
            this.grid = grid;
            this.output = output;
        }

        void submit(ICraftingPlan plan) {
            if (plan.simulation()) {
                missing = new JsonArray();
                for (var shortage : plan.missingItems()) {
                    missing.add(stack(new GenericStack(shortage.getKey(), shortage.getLongValue())));
                }
                fail("missing_ingredients");
                return;
            }
            if (request.endpoint.grid() != grid) {
                fail("me_network_changed");
                return;
            }
            var actor = MeOperations.playerAction(request.endpoint.level());
            var service = grid.getCraftingService();
            cpu = service.getCpus().stream().filter(CraftingCPUCluster.class::isInstance)
                    .map(CraftingCPUCluster.class::cast)
                    .filter(candidate -> candidate.isActive() && !candidate.isBusy()
                            && candidate.getAvailableStorage() >= plan.bytes() && candidate.canBeAutoSelectedFor(actor))
                    .findFirst().orElse(null);
            if (cpu == null) {
                fail("no_suitable_cpu_found");
                return;
            }
            before = MeOperations.count(grid.getStorageService().getInventory(), output);
            var submitted = service.submitJob(plan, null, cpu, true, actor);
            if (!submitted.successful()) {
                fail(submitted.errorCode().name().toLowerCase(Locale.ROOT));
                return;
            }
            // A private nexus records completion without changing standalone ME delivery.
            link = cpu.craftingLogic.getLastLink();
            if (link instanceof CraftingLink standalone && standalone.isStandalone()) {
                standalone.setNexus(new CraftingLinkNexus(standalone.getCraftingID()));
            }
            state = "running";
        }

        void fail(String code) {
            state = "failed";
            error = code;
        }

        JsonObject status() {
            JsonObject result = stack(new GenericStack(output, request.amount));
            result.addProperty("orderId", id.toString());
            boolean activeLink = link != null && cpu.craftingLogic.getLastLink() == link && !cpu.isDestroyed();
            result.addProperty("state", link == null ? state : link.isCanceled() ? "canceled"
                    : link.isDone() ? "completed" : activeLink ? "running" : "interrupted");
            if (error != null) {
                result.addProperty("error", error);
            }
            if (missing != null) {
                result.add("missing", missing);
            }
            if (link != null) {
                result.addProperty("ae2JobId", link.getCraftingID().toString());
                result.addProperty("storedBefore", before);
                if (request.endpoint.level().hasChunkAt(request.endpoint.position())) {
                    try {
                        if (request.endpoint.grid() == grid) {
                            result.addProperty("storedNow", MeOperations.count(grid.getStorageService().getInventory(), output));
                        }
                    } catch (BridgeError ignored) {
                        // The job link remains readable while the ME network is offline.
                    }
                }
                var progress = activeLink ? cpu.getJobStatus() : null;
                if (progress != null) {
                    result.addProperty("processedItems", progress.progress());
                    result.addProperty("totalWorkItems", progress.totalItems());
                }
            }
            return result;
        }
    }
}
