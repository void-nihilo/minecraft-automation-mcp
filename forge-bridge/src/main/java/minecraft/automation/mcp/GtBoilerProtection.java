package minecraft.automation.mcp;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.common.machine.steam.SteamSolidBoilerMachine;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

final class GtBoilerProtection {
    private static final ResourceLocation PROTECTED_BOILER = ResourceLocation.tryParse("gtceu:lp_steam_solid_boiler");

    private GtBoilerProtection() {}

    static void beforeItemInsertion(ServerLevel level, BlockPos position) {
        SteamSolidBoilerMachine boiler = protectedBoilerAt(level, position);
        if (boiler != null && (boiler.isHasNoWater() || boiler.waterTank.getFluidInTank(0).isEmpty())) {
            throw new BridgeError(409, "boiler_needs_water", "Confirm water in the solid boiler before adding fuel");
        }
    }

    static void beforeFluidInsertion(ServerLevel level, BlockPos position) {
        SteamSolidBoilerMachine boiler = protectedBoilerAt(level, position);
        if (boiler != null && boiler.isHasNoWater()) {
            throw new BridgeError(409, "dry_boiler", "The boiler entered a dry state and must be checked before filling");
        }
    }

    private static SteamSolidBoilerMachine protectedBoilerAt(ServerLevel level, BlockPos position) {
        if (MetaMachine.getMachine(level, position) instanceof SteamSolidBoilerMachine boiler
                && PROTECTED_BOILER.equals(boiler.getDefinition().getId())) {
            return boiler;
        }
        return null;
    }
}
