package minecraft.automation.mcp;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.EnergyHatchPartMachine;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

final class GtProductionTestFixtures {
    private static final long POWER_LIMIT = 65536;
    private static final long POWER_TICKS = 1000;

    private PowerSupply power;

    static JsonObject prepareInputs(ServerLevel level, BlockPos drive) {
        var oxygen = ForgeRegistries.FLUIDS.getValue(ResourceLocation.tryParse("gtceu:oxygen"));
        if (oxygen == null || oxygen == Fluids.EMPTY) {
            throw new BridgeError(409, "fixture_unavailable", "Required test fluid gtceu:oxygen is unavailable");
        }
        Map<AEKey, Integer> required = new LinkedHashMap<>();
        required.put(AEItemKey.of(registeredItem("gtceu:duct_tape")), 2);
        required.put(AEItemKey.of(Items.GLASS), 1);
        required.put(AEItemKey.of(registeredItem("gtceu:sulfur_dust")), 1);
        required.put(AEFluidKey.of(oxygen), 2100);
        return prepareStock(level, drive, required);
    }

    static JsonObject prepareStock(ServerLevel level, BlockPos drive, Map<? extends AEKey, Integer> required) {
        return prepareStock(level, drive, required, "ME did not retain the test input ");
    }

    static JsonObject prepareStock(ServerLevel level, BlockPos drive, Map<? extends AEKey, Integer> required,
                                   String refusalPrefix) {
        var grid = MeOperations.gridAt(level, drive);
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
                    throw new BridgeError(409, "fixture_refused", refusalPrefix + key.getId());
                }
                seeded.addProperty(key.getId().toString(), shortage);
            }
        } catch (RuntimeException failure) {
            for (var entry : balances.entrySet()) {
                MeOperations.restoreMe(storage, actor, entry.getKey(), entry.getValue());
            }
            throw failure;
        }
        JsonObject result = new JsonObject();
        result.add("seeded", seeded);
        return result;
    }

    private static Item registeredItem(String name) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(name));
        if (item == null || item == Items.AIR) {
            throw new BridgeError(409, "fixture_unavailable", "Required test item is unavailable: " + name);
        }
        return item;
    }

    JsonObject start(ServerLevel level, BlockPos position) {
        if (power != null) {
            throw new BridgeError(409, "test_power_busy", "A test power supply is already armed");
        }
        if (!(MetaMachine.getMachine(level, position) instanceof WorkableElectricMultiblockMachine machine)
                || !machine.isFormed()) {
            throw new BridgeError(409, "multiblock_unformed", "The test requires a formed GT electric multiblock");
        }
        String id = ForgeRegistries.BLOCKS.getKey(level.getBlockState(position).getBlock()).toString();
        if (!"gtceu:electric_blast_furnace".equals(id) && !"gtceu:large_chemical_reactor".equals(id)) {
            throw new BridgeError(409, "fixture_unavailable", "The power fixture is reserved for the two recipe tests");
        }
        List<EnergyHatchPartMachine> hatches = machine.getParts().stream()
                .map(part -> part.self()).filter(EnergyHatchPartMachine.class::isInstance)
                .map(EnergyHatchPartMachine.class::cast)
                .filter(hatch -> hatch.energyContainer.getInputVoltage() > 0)
                .toList();
        if (hatches.isEmpty()) {
            throw new BridgeError(409, "missing_energy_hatch", "The multiblock has no native energy input hatches");
        }
        power = new PowerSupply(level, position, machine, hatches);
        return power.report();
    }

    JsonObject stop(ServerLevel level, BlockPos position) {
        if (power == null || power.level != level || !power.position.equals(position)) {
            throw new BridgeError(409, "test_power_missing", "The configured structure has no armed power supply");
        }
        JsonObject report = power.report();
        power = null;
        return report;
    }

    void tick(MinecraftServer server, BooleanSupplier allowed) {
        PowerSupply supply = power;
        if (supply == null) {
            return;
        }
        if (!allowed.getAsBoolean()) {
            power = null;
            return;
        }
        if (supply.level.getServer() != server || supply.ticks >= POWER_TICKS || supply.delivered >= POWER_LIMIT) {
            return;
        }
        supply.ticks++;
        if (!supply.level.hasChunkAt(supply.position)
                || MetaMachine.getMachine(supply.level, supply.position) != supply.machine
                || !supply.machine.isFormed()) {
            return;
        }
        for (EnergyHatchPartMachine hatch : supply.hatches) {
            BlockPos position = hatch.getPos();
            if (!supply.level.hasChunkAt(position) || MetaMachine.getMachine(supply.level, position) != hatch) {
                continue;
            }
            var energy = hatch.energyContainer;
            long voltage = energy.getInputVoltage();
            long availableAmps = Math.min(energy.getInputAmperage(), (POWER_LIMIT - supply.delivered) / voltage);
            if (availableAmps > 0) {
                long deliveredAmps = energy.acceptEnergyFromNetwork(hatch.getFrontFacing(), voltage, availableAmps);
                supply.delivered += voltage * deliveredAmps;
            }
        }
    }

    void clear(MinecraftServer server) {
        if (power != null && power.level.getServer() == server) {
            power = null;
        }
    }

    private static final class PowerSupply {
        private final ServerLevel level;
        private final BlockPos position;
        private final WorkableElectricMultiblockMachine machine;
        private final List<EnergyHatchPartMachine> hatches;
        private final long initialEnergy;
        private long ticks;
        private long delivered;

        private PowerSupply(ServerLevel level, BlockPos position, WorkableElectricMultiblockMachine machine,
                            List<EnergyHatchPartMachine> hatches) {
            this.level = level;
            this.position = position;
            this.machine = machine;
            this.hatches = hatches;
            this.initialEnergy = energyStored();
        }

        private long energyStored() {
            return hatches.stream().mapToLong(hatch -> hatch.energyContainer.getEnergyStored()).sum();
        }

        private JsonObject report() {
            long stored = energyStored();
            JsonObject report = new JsonObject();
            report.addProperty("initialEnergy", initialEnergy);
            report.addProperty("deliveredEnergy", delivered);
            report.addProperty("storedEnergy", stored);
            report.addProperty("usedEnergy", initialEnergy + delivered - stored);
            report.addProperty("testTicks", ticks);
            return report;
        }
    }
}
