package minecraft.automation.mcp;

import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.ICoverable;
import com.gregtechceu.gtceu.api.cover.CoverBehavior;
import com.gregtechceu.gtceu.api.cover.CoverDefinition;
import com.gregtechceu.gtceu.api.item.IComponentItem;
import com.gregtechceu.gtceu.common.item.CoverPlaceBehavior;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class MeGtCoverOperations {
    private MeGtCoverOperations() {}

    static JsonObject install(ServerLevel level, BlockPos position, Direction side,
                              MeOperations.Endpoint endpoint,
                              ResourceLocation item, String expectedMeToken) {
        ICoverable coverable = GTCapabilityHelper.getCoverable(level, position, side);
        if (coverable == null) {
            throw new BridgeError(409, "cover_target_unavailable", "The block does not expose GT cover access on the requested side");
        }
        if (coverable.getCoverAtSide(side) != null) {
            throw new BridgeError(409, "cover_side_occupied", "The requested side already has a cover");
        }

        var grid = endpoint.grid();
        MEStorage storage = grid.getStorageService().getInventory();
        var owner = HostInventoryOperations.host(level.getServer());
        var actor = IActionSource.ofPlayer(owner);
        AEItemKey key = MeOperations.resolveMeStack(storage, AEItemKey.class, item, expectedMeToken);
        ItemStack stack = key.toStack(1);
        CoverDefinition definition = coverDefinition(stack);
        if (!coverable.canPlaceCoverOnSide(definition, side)
                || !definition.createCoverBehavior(coverable, side).canAttach()) {
            throw new BridgeError(409, "cover_not_attachable", "GT does not allow this cover on the requested side");
        }

        long before = MeOperations.count(storage, key);
        long after = MeOperations.withdraw(grid, storage, actor, key, 1, before, () -> {
            if (!coverable.placeCoverOnSide(side, stack, definition, owner)) {
                throw new BridgeError(409, "cover_install_failed", "GT refused to install the cover");
            }
            CoverBehavior installed = coverable.getCoverAtSide(side);
            if (installed == null || installed.coverDefinition != definition
                    || installed.attachedSide != side) {
                throw new BridgeError(409, "cover_verification_failed", "GT did not retain the expected cover on the requested side");
            }
        }, () -> {
            CoverBehavior current = coverable.getCoverAtSide(side);
            if (current != null && (current.coverDefinition != definition
                    || !coverable.removeCover(false, side, owner) || coverable.getCoverAtSide(side) != null)) {
                throw new IllegalStateException("Could not restore the original cover state");
            }
        });

        JsonObject result = new JsonObject();
        result.addProperty("item", item.toString());
        result.addProperty("cover", definition.getId().toString());
        result.addProperty("side", side.getName());
        result.addProperty("installed", true);
        result.addProperty("meBefore", before);
        result.addProperty("meAfter", after);
        result.add("position", MinecraftAutomationBridge.positionJson(position));
        return result;
    }

    private static CoverDefinition coverDefinition(ItemStack stack) {
        if (stack.getItem() instanceof IComponentItem item) {
            var definitions = item.getComponents().stream()
                    .filter(CoverPlaceBehavior.class::isInstance)
                    .map(CoverPlaceBehavior.class::cast)
                    .map(CoverPlaceBehavior::coverDefinition)
                    .distinct().toList();
            if (definitions.size() == 1) {
                return definitions.get(0);
            }
        }
        throw new BridgeError(409, "invalid_cover_item", "The ME item must identify one GT cover");
    }
}
