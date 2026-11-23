package minecraft.automation.mcp;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

record CheatPermissions(boolean allowTestFixtures, boolean allowTestBlockWrites) {
    static final String FILE_NAME = "minecraft-automation-mcp-permissions.properties";
    private static final CheatPermissions DISABLED = new CheatPermissions(false, false);

    static void createDefaultFile(Path path) throws IOException {
        try {
            Files.writeString(path, """
                    # Changes take effect while Minecraft is running.
                    # Allow test fixtures that can create items, fluids, energy, or blocks.
                    allowTestFixtures=false
                    # Allow set_test_block at the configured test coordinate.
                    allowTestBlockWrites=false
                    """, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ignored) {
            // Keep the user's configuration.
        }
    }

    static CheatPermissions read(Path path) {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            return DISABLED;
        }
        return new CheatPermissions(
                "true".equals(properties.getProperty("allowTestFixtures")),
                "true".equals(properties.getProperty("allowTestBlockWrites")));
    }

    boolean allows(Permission permission) {
        return switch (permission) {
            case TEST_FIXTURES -> allowTestFixtures;
            case TEST_BLOCK_WRITES -> allowTestBlockWrites;
        };
    }

    static Permission requiredFor(String operation) {
        return switch (operation) {
            case "set_test_block" -> Permission.TEST_BLOCK_WRITES;
            case "verify_tree_farm",
                    "prepare_chest_test", "prepare_tank_test", "prepare_me_test",
                    "prepare_me_fluid_cell", "prepare_me_workbench_test",
                    "prepare_gt_coal_test", "prepare_gt_duct_tape_test",
                    "prepare_gt_circuit_logs_test", "prepare_gt_hatch_charge_test",
                    "prepare_gt_furnace_test", "prepare_gt_materials_test",
                    "prepare_gt_recipe_inputs_test", "start_gt_recipe_power_test",
                    "stop_gt_recipe_power_test" -> Permission.TEST_FIXTURES;
            default -> null;
        };
    }

    enum Permission {
        TEST_FIXTURES("allowTestFixtures"),
        TEST_BLOCK_WRITES("allowTestBlockWrites");

        final String key;

        Permission(String key) {
            this.key = key;
        }
    }
}
