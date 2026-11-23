package minecraft.automation.mcp;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import minecraft.automation.mcp.CheatPermissions.Permission;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.versions.forge.ForgeVersion;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Supplier;

@Mod("minecraft_automation_mcp")
public final class MinecraftAutomationBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final int MAX_REQUEST_BYTES = 8192;
    private static final Path CONFIG_DIRECTORY = FMLPaths.CONFIGDIR.get();
    private static final Path PERMISSIONS_FILE = CONFIG_DIRECTORY.resolve(CheatPermissions.FILE_NAME);
    private static final Path TOKEN_FILE = configFile("bridge.token");
    private static final Path TARGET_FILE = configFile("test-target.json");
    private static final Path CHEST_FIXTURE_FILE = configFile("chest-fixture.json");
    private static final Path TANK_FIXTURE_FILE = configFile("tank-fixture.json");
    private static final Path ME_FIXTURE_FILE = configFile("me-fixture.json");
    private static final Path ME_FLUID_FIXTURE_FILE = configFile("me-fluid-fixture.json");
    private static final Path GT_FURNACE_FIXTURE_FILE = configFile("gt-furnace-fixture.json");
    private static final Path GT_MATERIALS_FIXTURE_FILE = configFile("gt-materials-fixture.json");
    private static final Path GT_COAL_FIXTURE_FILE = configFile("gt-coal-fixture.json");
    private static final Path GT_CIRCUIT_LOGS_FIXTURE_FILE = configFile("gt-circuit-logs-fixture.json");
    private static final Path GT_HATCH_CHARGE_FIXTURE_FILE = configFile("gt-hatch-charge-fixture.json");
    private static final Path GT_DUCT_TAPE_FIXTURE_FILE = configFile("gt-duct-tape-fixture.json");
    private static final Path GT_RECIPE_INPUTS_FIXTURE_FILE = configFile("gt-recipe-inputs-fixture.json");
    private static final Path GT_RECIPE_POWER_FIXTURE_FILE = configFile("gt-recipe-power-fixture.json");
    private static final Path ME_WORKBENCH_FIXTURE_FILE = configFile("me-workbench-fixture.json");

    private final GtProductionTestFixtures gtProductionFixtures = new GtProductionTestFixtures();
    private final MeCraftingOperations meCrafting = new MeCraftingOperations();
    private final TreeFarmOperations treeFarm = new TreeFarmOperations();
    private final String instanceId = UUID.randomUUID().toString();
    private volatile MinecraftServer activeWorld;

    public MinecraftAutomationBridge() {
        MinecraftForge.EVENT_BUS.addListener(this::onWorldStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onWorldStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        createCheatPermissionsFile();
        startBridge();
    }

    private void onWorldStarted(ServerStartedEvent event) {
        if (event.getServer().isSingleplayer()) {
            activeWorld = event.getServer();
        }
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.getServer() == activeWorld) {
            gtProductionFixtures.tick(event.getServer(),
                    () -> CheatPermissions.read(PERMISSIONS_FILE).allowTestFixtures());
            meCrafting.tick();
            treeFarm.tick(event.getServer());
        }
    }

    private void onWorldStopping(ServerStoppingEvent event) {
        gtProductionFixtures.clear(event.getServer());
        if (activeWorld == event.getServer()) {
            activeWorld = null;
            meCrafting.clear();
            treeFarm.stop();
        }
    }

    private void startBridge() {
        try {
            String token = loadToken();
            InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0);
            HttpServer http = HttpServer.create(address, 8);
            http.createContext("/bridge", exchange -> handle(exchange, token));
            http.setExecutor(Executors.newFixedThreadPool(2, job -> daemon(job, "Minecraft Automation MCP HTTP worker")));

            // The HTTP dispatcher inherits the starter thread's daemon flag.
            daemon(() -> {
                http.start();
                int port = http.getAddress().getPort();
                registerBridge(port);
                LOGGER.info("Minecraft Automation MCP bridge listening on 127.0.0.1:{}", port);
            }, "Minecraft Automation MCP HTTP startup").start();
        } catch (IOException exception) {
            LOGGER.error("Minecraft Automation MCP bridge could not start", exception);
        }
    }

    private void registerBridge(int port) {
        Path directory = Path.of(System.getProperty("user.home"), ".minecraft-automation-mcp", "instances");
        Path registrationFile = directory.resolve(instanceId + ".json");
        try {
            Files.createDirectories(directory);
            JsonObject registration = new JsonObject();
            registration.addProperty("configDirectory", TOKEN_FILE.toAbsolutePath().normalize().getParent().toString());
            registration.addProperty("port", port);
            registration.addProperty("pid", ProcessHandle.current().pid());
            Path temporary = Files.createTempFile(directory, "bridge-", ".tmp");
            try {
                Files.writeString(temporary, GSON.toJson(registration));
                Files.move(temporary, registrationFile, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(registrationFile);
                } catch (IOException exception) {
                    LOGGER.warn("Minecraft Automation MCP bridge could not remove its local registration", exception);
                }
            }, "Minecraft Automation MCP bridge cleanup"));
        } catch (IOException exception) {
            LOGGER.error("Minecraft Automation MCP bridge could not publish its local registration", exception);
        }
    }

    private static Thread daemon(Runnable job, String name) {
        Thread thread = new Thread(job, name);
        thread.setDaemon(true);
        return thread;
    }

    private static Path configFile(String suffix) {
        return CONFIG_DIRECTORY.resolve("minecraft-automation-mcp-" + suffix);
    }

    private static void createCheatPermissionsFile() {
        try {
            CheatPermissions.createDefaultFile(PERMISSIONS_FILE);
        } catch (IOException exception) {
            LOGGER.warn("Minecraft Automation MCP could not create {}.", CheatPermissions.FILE_NAME, exception);
        }
    }

    private static void requireCheatPermission(Permission permission) {
        if (permission != null && !CheatPermissions.read(PERMISSIONS_FILE).allows(permission)) {
            throw new BridgeError(403, "cheat_permission_denied",
                    "Set " + permission.key + "=true in " + CheatPermissions.FILE_NAME + ".");
        }
    }

    private static String loadToken() throws IOException {
        if (Files.notExists(TOKEN_FILE)) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            Files.writeString(TOKEN_FILE, HexFormat.of().formatHex(random) + System.lineSeparator(),
                    StandardOpenOption.CREATE_NEW);
        }
        String token = Files.readString(TOKEN_FILE).strip();
        if (!token.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid token in " + TOKEN_FILE.getFileName());
        }
        return token;
    }

    private void handle(HttpExchange exchange, String token) throws IOException {
        try (exchange) {
            if (!"/bridge".equals(exchange.getRequestURI().getPath()) ||
                    !"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 404, error("not_found", "Unknown bridge endpoint"));
                return;
            }

            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !MessageDigest.isEqual(
                    authorization.getBytes(StandardCharsets.UTF_8),
                    ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
                respond(exchange, 401, error("unauthorized", "Invalid bridge token"));
                return;
            }
            if (!instanceId.equals(exchange.getRequestHeaders().getFirst("X-Minecraft-Automation-MCP-Instance"))) {
                respond(exchange, 409, error("instance_changed", "The bridge instance changed. Discover running instances again."));
                return;
            }

            byte[] requestBody = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (requestBody.length > MAX_REQUEST_BYTES) {
                respond(exchange, 413, error("request_too_large", "Bridge request exceeds size limit"));
                return;
            }

            try {
                JsonObject request = GSON.fromJson(new String(requestBody, StandardCharsets.UTF_8), JsonObject.class);
                respond(exchange, 200, invoke(request));
            } catch (BridgeError exception) {
                respond(exchange, exception.status, error(exception.code, exception.getMessage()));
            } catch (JsonParseException | IllegalArgumentException exception) {
                respond(exchange, 400, error("invalid_request", "Invalid bridge request"));
            } catch (RuntimeException exception) {
                LOGGER.error("Unexpected Minecraft Automation MCP bridge error", exception);
                respond(exchange, 500, error("internal_error", "Unexpected bridge error"));
            }
        }
    }

    private JsonObject invoke(JsonObject request) {
        String operation = stringField(request, "operation");
        Permission permission = CheatPermissions.requiredFor(operation);
        requireCheatPermission(permission);

        MinecraftServer server = activeWorld;
        if (server == null) {
            if ("get_game_status".equals(operation)) {
                return gameStatus(null);
            }
            throw new BridgeError(409, "no_world", "Enter a singleplayer world first");
        }

        return switch (operation) {
            case "get_game_status" -> onServerThread(server, () -> gameStatus(server));
            case "find_spruce_and_me" -> {
                BlockLocation site = location(request);
                int radius = request.has("radius") ? coordinate(request, "radius") : 24;
                yield onServerThread(server, () -> GiantSpruceOperations.find(site.level(server), site.position, radius));
            }
            case "inspect_giant_spruce", "harvest_giant_spruce_to_me" -> {
                requireAe2();
                BlockLocation site = location(request);
                BlockLocation drive = location(objectField(request, "drive"));
                String expected = operation.equals("inspect_giant_spruce") ? null : stringField(request, "expectedPlan");
                yield onServerThread(server, () -> GiantSpruceOperations.run(site.level(server), site.position,
                        drive.level(server), drive.position, expected));
            }
            case "list_ftb_quest_chapters", "list_ftb_quests", "get_ftb_quest" -> {
                if (!ModList.get().isLoaded("ftbquests")) {
                    throw new BridgeError(409, "ftb_quests_unavailable", "FTB Quests is not loaded");
                }
                yield onServerThread(server, () -> FtbQuestOperations.read(server, operation, request));
            }
            case "start_tree_farm" -> {
                BlockLocation site = location(request);
                BlockPos supply = coordinates(objectField(request, "supply"));
                BlockPos output = coordinates(objectField(request, "output"));
                String tree = stringField(request, "tree");
                yield onServerThread(server, () -> treeFarm.start(site.level(server),
                        site.position, supply, output, tree));
            }
            case "get_tree_farm_status" -> onServerThread(server, treeFarm::status);
            case "stop_tree_farm" -> onServerThread(server, treeFarm::stop);
            case "verify_tree_farm" -> {
                TestTarget target = readTestTarget(TARGET_FILE);
                yield onCheatServerThread(server, permission, () -> {
                    if (!target.worldFolder.equals(worldFolder(server))) {
                        throw new BridgeError(403, "test_target_denied", "This world is outside the configured test target");
                    }
                    return treeFarm.verify(loadedLevel(server, target.dimension, target.position), target.position);
                });
            }
            case "get_block" -> {
                BlockLocation site = location(request);
                yield onServerThread(server, () -> blockInfo(site.level(server), site.position));
            }
            case "get_container" -> {
                BlockLocation site = location(request);
                yield onServerThread(server, () ->
                        ChestOperations.contents(site.level(server), site.position));
            }
            case "transfer_item" -> {
                BlockLocation source = location(objectField(request, "source"));
                BlockLocation target = location(objectField(request, "target"));
                int sourceSlot = coordinate(request, "sourceSlot");
                int amount = coordinate(request, "amount");
                String expectedStackToken = stringField(request, "expectedStackToken");
                yield onServerThread(server, () -> {
                    JsonObject result = ChestOperations.transfer(
                            source.level(server), source.position, sourceSlot,
                            target.level(server), target.position, amount, expectedStackToken);
                    LOGGER.info("Minecraft Automation MCP transferred {} {} from {} {} slot {} to {} {}", amount,
                            result.get("item").getAsString(), source.dimension, source.position,
                            sourceSlot, target.dimension, target.position);
                    return result;
                });
            }
            case "get_tank" -> {
                BlockLocation site = location(request);
                yield onServerThread(server, () ->
                        TankOperations.contents(site.level(server), site.position));
            }
            case "transfer_fluid" -> {
                BlockLocation source = location(objectField(request, "source"));
                BlockLocation target = location(objectField(request, "target"));
                int amount = coordinate(request, "amount");
                String expectedFluidToken = stringField(request, "expectedFluidToken");
                yield onServerThread(server, () -> {
                    JsonObject result = TankOperations.transfer(
                            source.level(server), source.position,
                            target.level(server), target.position, amount, expectedFluidToken);
                    LOGGER.info("Minecraft Automation MCP transferred {} mB of {} from {} {} to {} {}", amount,
                            result.get("fluid").getAsString(), source.dimension, source.position,
                            target.dimension, target.position);
                    return result;
                });
            }
            case "get_workbench_recipe", "find_workbench_recipes" -> {
                boolean find = "find_workbench_recipes".equals(operation);
                ResourceLocation id = resourceId(request, find ? "item" : "recipe");
                yield onServerThread(server, () -> find
                        ? MeWorkbenchOperations.findByOutput(server.overworld(), id)
                        : MeWorkbenchOperations.describe(server.overworld(), id));
            }
            case "encode_me_crafting_pattern", "encode_me_processing_pattern" -> {
                requireAe2();
                BlockLocation site = location(request);
                BlockPos provider = position(objectField(request, "provider"));
                int slot = coordinate(request, "slot");
                boolean processing = "encode_me_processing_pattern".equals(operation);
                ResourceLocation recipe = processing ? null : resourceId(request, "recipe");
                JsonArray ingredients = processing ? processingArray(request, "inputs") : craftingGrid(request);
                JsonArray outputs = processing ? processingArray(request, "outputs") : null;
                yield onServerThread(server, () -> {
                    ServerLevel level = site.level(server);
                    loadedLevel(server, site.dimension, provider);
                    return processing
                            ? MeCraftingOperations.installProcessingPattern(level, site.position, provider, slot, ingredients, outputs)
                            : MeCraftingOperations.installPattern(level, site.position, provider, slot, recipe, ingredients);
                });
            }
            case "get_me_craftable", "order_me_craft" -> {
                requireAe2();
                BlockLocation site = location(request);
                ResourceLocation item = resourceId(request, "item");
                if ("get_me_craftable".equals(operation)) {
                    yield onServerThread(server, () ->
                            MeCraftingOperations.craftables(site.level(server), site.position, item));
                }
                UUID id = UUID.fromString(stringField(request, "orderId"));
                String variant = request.has("variant") ? stringField(request, "variant") : null;
                int amount = coordinate(request, "amount");
                yield onServerThread(server, () ->
                        meCrafting.order(site.level(server), site.position, id, item, variant, amount));
            }
            case "get_me_craft_order", "cancel_me_craft_order" -> {
                requireAe2();
                UUID id = UUID.fromString(stringField(request, "orderId"));
                yield onServerThread(server, () -> meCrafting.status(id, "cancel_me_craft_order".equals(operation)));
            }
            case "craft_from_me" -> {
                requireAe2();
                BlockLocation site = location(request);
                ResourceLocation recipe = resourceId(request, "recipe");
                JsonArray grid = craftingGrid(request);
                yield onServerThread(server, () -> {
                    JsonObject result = MeWorkbenchOperations.craft(
                            site.level(server), site.position, recipe, grid);
                    LOGGER.info("Minecraft Automation MCP ran workbench recipe {} at ME {} {}: {}", recipe, site.dimension, site.position, result);
                    return result;
                });
            }
            case "prepare_me_workbench_test" -> {
                requireAe2();
                TestTarget fixture = readTestTarget(ME_WORKBENCH_FIXTURE_FILE);
                String kind = stringField(request, "stock");
                if (!"flint".equals(kind) && !"gt_tools".equals(kind)) {
                    throw new IllegalArgumentException("Invalid workbench test stock");
                }
                yield onCheatServerThread(server, permission, () -> {
                    ServerLevel level = fixtureLevel(server, fixture, "the workbench test");
                    return MeWorkbenchOperations.prepareTestStock(
                            level, fixture.position, "flint".equals(kind));
                });
            }
            case "get_me_item", "get_me_fluid" -> {
                requireAe2();
                boolean item = "get_me_item".equals(operation);
                BlockLocation site = location(request);
                String field = item ? "item" : "fluid";
                ResourceLocation id = resourceId(request, field);
                yield onServerThread(server, () -> MeOperations.contents(site.level(server),
                        site.position, id, item ? AEItemKey.class : AEFluidKey.class, field));
            }
            case "transfer_me_item" -> {
                requireAe2();
                BlockLocation chest = location(objectField(request, "chest"));
                BlockLocation drive = location(objectField(request, "drive"));
                ResourceLocation item = resourceId(request, "item");
                String direction = stringField(request, "direction");
                boolean toMe = transferDirection(direction, "me", "Invalid ME transfer direction");
                int sourceSlot = toMe ? coordinate(request, "sourceSlot") : -1;
                int amount = coordinate(request, "amount");
                String token = stringField(request, "expectedSourceToken");
                yield onServerThread(server, () -> {
                    JsonObject result = MeOperations.transfer(
                            chest.level(server), chest.position,
                            drive.level(server), drive.position,
                            toMe, item, amount, sourceSlot, token);
                    LOGGER.info("Minecraft Automation MCP moved {} {} {} between chest {} {} and ME drive {} {}", amount, item,
                            direction, chest.dimension, chest.position, drive.dimension, drive.position);
                    return result;
                });
            }
            case "transfer_me_fluid" -> {
                requireAe2();
                BlockLocation tank = location(objectField(request, "tank"));
                BlockLocation drive = location(objectField(request, "drive"));
                ResourceLocation fluid = resourceId(request, "fluid");
                String direction = stringField(request, "direction");
                boolean toMe = transferDirection(direction, "me", "Invalid ME transfer direction");
                int amount = coordinate(request, "amount");
                String token = stringField(request, "expectedSourceToken");
                yield onServerThread(server, () -> {
                    JsonObject result = MeFluidOperations.transfer(
                            tank.level(server), tank.position,
                            drive.level(server), drive.position,
                            toMe, fluid, amount, token);
                    LOGGER.info("Minecraft Automation MCP moved {} mB of {} {} between tank {} {} and ME drive {} {}", amount, fluid,
                            direction, tank.dimension, tank.position, drive.dimension, drive.position);
                    return result;
                });
            }
            case "get_gt_machine" -> {
                requireGt();
                BlockLocation site = location(request);
                yield onServerThread(server, () -> GtMachineOperations.inspect(site.level(server), site.position));
            }
            case "configure_gt_machine" -> {
                requireGt();
                BlockLocation site = location(request);
                Boolean working = request.has("workingEnabled") ? booleanField(request, "workingEnabled") : null;
                Integer circuit = request.has("circuit") ? coordinate(request, "circuit") : null;
                yield onServerThread(server, () -> GtMachineOperations.configure(
                        site.level(server), site.position, working, circuit));
            }
            case "transfer_gt_me_item" -> {
                requireGt();
                requireAe2();
                BlockLocation machine = location(objectField(request, "machine"));
                BlockLocation drive = location(objectField(request, "drive"));
                ResourceLocation item = resourceId(request, "item");
                String direction = stringField(request, "direction");
                boolean toMachine = transferDirection(direction, "machine", "Invalid GT and ME transfer direction");
                int slot = coordinate(request, "machineSlot");
                int amount = coordinate(request, "amount");
                String token = stringField(request, "expectedSourceToken");
                yield onServerThread(server, () -> {
                    JsonObject result = GtMachineOperations.transferMe(
                            machine.level(server), machine.position,
                            drive.level(server), drive.position, toMachine, slot, item, amount, token);
                    LOGGER.info("Minecraft Automation MCP moved {} {} {} between GT {} and ME {}", amount, item,
                            direction, machine.position, drive.position);
                    return result;
                });
            }
            case "transfer_gt_me_fluid" -> {
                requireGt();
                requireAe2();
                BlockLocation machine = location(objectField(request, "machine"));
                BlockLocation drive = location(objectField(request, "drive"));
                ResourceLocation fluid = resourceId(request, "fluid");
                String direction = stringField(request, "direction");
                boolean toMachine = transferDirection(direction, "machine", "Invalid GT and ME transfer direction");
                int tank = coordinate(request, "machineTank");
                int amount = coordinate(request, "amount");
                String token = stringField(request, "expectedSourceToken");
                yield onServerThread(server, () -> {
                    JsonObject result = GtMachineFluidOperations.transferMe(
                            machine.level(server), machine.position,
                            drive.level(server), drive.position, toMachine,
                            tank, fluid, amount, token);
                    LOGGER.info("Minecraft Automation MCP moved {} mB of {} {} between GT {} and ME {}", amount, fluid,
                            direction, machine.position, drive.position);
                    return result;
                });
            }
            case "prepare_gt_coal_test", "prepare_gt_duct_tape_test" -> {
                boolean tape = "prepare_gt_duct_tape_test".equals(operation);
                requireGt();
                requireAe2();
                yield onTestTarget(server, permission, tape ? GT_DUCT_TAPE_FIXTURE_FILE : GT_COAL_FIXTURE_FILE,
                        "this GT fixture", tape ? GtMachineFluidOperations::prepareTestDuctTape
                                : GtMachineFluidOperations::prepareTestCoal);
            }
            case "transfer_gt_machine_item" -> {
                requireGt();
                BlockLocation source = location(objectField(request, "source"));
                BlockLocation target = location(objectField(request, "target"));
                int sourceSlot = coordinate(request, "sourceSlot");
                int targetSlot = coordinate(request, "targetSlot");
                int amount = coordinate(request, "amount");
                ResourceLocation item = resourceId(request, "item");
                String token = stringField(request, "expectedSourceToken");
                yield onServerThread(server, () -> {
                    JsonObject result = GtMachineOperations.transferMachines(
                            source.level(server), source.position, sourceSlot,
                            target.level(server), target.position, targetSlot, item, amount, token);
                    LOGGER.info("Minecraft Automation MCP moved {} {} from GT {} to GT {}", amount, item, source.position, target.position);
                    return result;
                });
            }
            case "get_gt_coke_oven_layout", "get_gt_multiblock_layout" -> {
                String kind = "get_gt_coke_oven_layout".equals(operation)
                        ? "gtceu:coke_oven" : stringField(request, "structure");
                requireGt();
                yield onServerThread(server, () -> GtMultiblockOperations.layout(kind));
            }
            case "build_gt_coke_oven_from_me", "build_gt_multiblock_from_me" -> {
                String kind = "build_gt_coke_oven_from_me".equals(operation)
                        ? "gtceu:coke_oven" : stringField(request, "structure");
                requireGt();
                requireAe2();
                BlockLocation controller = location(objectField(request, "controller"));
                BlockLocation drive = location(objectField(request, "drive"));
                yield onServerThread(server, () -> {
                    JsonObject result = GtMultiblockOperations.build(
                            controller.level(server), controller.position,
                            drive.level(server), drive.position, kind);
                    LOGGER.info("Minecraft Automation MCP built {} at {} {} from ME {} {}", kind, controller.dimension,
                            controller.position, drive.dimension, drive.position);
                    return result;
                });
            }
            case "prepare_gt_recipe_inputs_test", "start_gt_recipe_power_test", "stop_gt_recipe_power_test" -> {
                requireGt();
                boolean inputs = "prepare_gt_recipe_inputs_test".equals(operation);
                if (inputs) {
                    requireAe2();
                }
                yield onTestTarget(server, permission, inputs ? GT_RECIPE_INPUTS_FIXTURE_FILE : GT_RECIPE_POWER_FIXTURE_FILE,
                        "the GT recipe test", switch (operation) {
                            case "prepare_gt_recipe_inputs_test" -> GtProductionTestFixtures::prepareInputs;
                            case "start_gt_recipe_power_test" -> gtProductionFixtures::start;
                            default -> gtProductionFixtures::stop;
                        });
            }
            case "service_gt_maintenance" -> {
                requireGt();
                requireAe2();
                BlockLocation hatch = location(objectField(request, "hatch"));
                BlockLocation drive = location(objectField(request, "drive"));
                String token = stringField(request, "expectedSourceToken");
                yield onServerThread(server, () -> {
                    JsonObject result = GtMachineOperations.serviceMaintenance(
                            hatch.level(server), hatch.position,
                            drive.level(server), drive.position, token);
                    LOGGER.info("Minecraft Automation MCP serviced GT maintenance hatch {} using {} duct tape from ME {}",
                            hatch.position, result.get("tapeUsed").getAsInt(), drive.position);
                    return result;
                });
            }
            case "prepare_gt_circuit_logs_test", "prepare_gt_hatch_charge_test" -> {
                requireGt();
                boolean logs = "prepare_gt_circuit_logs_test".equals(operation);
                if (logs) {
                    requireAe2();
                }
                yield onTestTarget(server, permission, logs ? GT_CIRCUIT_LOGS_FIXTURE_FILE : GT_HATCH_CHARGE_FIXTURE_FILE,
                        "the GT circuit test", logs ? GtMachineFluidOperations::prepareTestLogs
                                : GtMachineOperations::prepareTestHatchCharge);
            }
            case "prepare_gt_furnace_test", "prepare_gt_materials_test" -> {
                requireGt();
                boolean furnace = "prepare_gt_furnace_test".equals(operation);
                String kind = request.has("structure") ? stringField(request, "structure") : "gtceu:coke_oven";
                if (!furnace) {
                    requireAe2();
                }
                yield onTestTarget(server, permission, furnace ? GT_FURNACE_FIXTURE_FILE : GT_MATERIALS_FIXTURE_FILE,
                        "the GT fixture", furnace ? GtMachineOperations::prepareTestFurnace
                                : (level, at) -> GtMultiblockOperations.prepareTestMaterials(level, at, kind));
            }
            case "prepare_me_fluid_cell" -> {
                requireAe2();
                yield onTestTarget(server, permission, ME_FLUID_FIXTURE_FILE, "the ME fluid cell",
                        MeFluidOperations::prepareTestFluidCell);
            }
            case "prepare_me_test" -> {
                requireAe2();
                StorageFixture fixture = readStorageFixture(ME_FIXTURE_FILE);
                yield onCheatServerThread(server, permission, () -> MeOperations.prepareTestNetwork(
                        storageFixtureLevel(server, fixture, "the ME fixture"), fixture.source, fixture.target));
            }
            case "prepare_chest_test", "prepare_tank_test" -> {
                boolean tankTest = "prepare_tank_test".equals(operation);
                StorageFixture fixture = readStorageFixture(tankTest ? TANK_FIXTURE_FILE : CHEST_FIXTURE_FILE);
                yield onCheatServerThread(server, permission, () -> {
                    ServerLevel level = storageFixtureLevel(server, fixture, "the storage fixture");
                    return tankTest
                            ? TankOperations.prepareTestTanks(level, fixture.source, fixture.target)
                            : ChestOperations.prepareTestChests(level, fixture.source, fixture.target);
                });
            }
            case "set_test_block" -> {
                BlockLocation site = location(request);
                BlockState expected = testState(stringField(request, "expected"));
                BlockState replacement = testState(stringField(request, "block"));
                TestTarget target = readTestTarget(TARGET_FILE);
                yield onCheatServerThread(server, permission, () -> setTestBlock(server, target, site.dimension, site.position, expected, replacement));
            }
            default -> throw new BridgeError(404, "unknown_operation", "Unknown bridge operation");
        };
    }

    private JsonObject onTestTarget(MinecraftServer server, Permission permission, Path file, String description,
                                    BiFunction<ServerLevel, BlockPos, JsonObject> action) {
        TestTarget fixture = readTestTarget(file);
        return onCheatServerThread(server, permission,
                () -> action.apply(fixtureLevel(server, fixture, description), fixture.position));
    }

    private JsonObject onCheatServerThread(MinecraftServer server, Permission permission, Supplier<JsonObject> action) {
        return onServerThread(server, () -> {
            requireCheatPermission(permission);
            return action.get();
        });
    }

    private JsonObject onServerThread(MinecraftServer server, Supplier<JsonObject> action) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        server.execute(() -> {
            if (System.nanoTime() - deadline >= 0) {
                result.completeExceptionally(new BridgeError(504, "world_timeout", "Game server did not respond in time"));
                return;
            }
            try {
                if (activeWorld != server) {
                    throw new BridgeError(409, "world_changed", "The singleplayer world changed");
                }
                result.complete(action.get());
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        });
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new BridgeError(504, "world_timeout", "Game server did not respond in time");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BridgeError(503, "bridge_interrupted", "Bridge request was interrupted");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(exception.getCause());
        }
    }

    private JsonObject gameStatus(MinecraftServer server) {
        JsonObject status = new JsonObject();
        status.addProperty("instanceId", instanceId);
        status.addProperty("connected", true);
        status.addProperty("worldLoaded", server != null);
        status.addProperty("minecraft", SharedConstants.getCurrentVersion().getName());
        status.addProperty("forge", ForgeVersion.getVersion());
        CheatPermissions cheatPermissions = CheatPermissions.read(PERMISSIONS_FILE);
        JsonObject permissions = new JsonObject();
        permissions.addProperty("allowTestFixtures", cheatPermissions.allowTestFixtures());
        permissions.addProperty("allowTestBlockWrites", cheatPermissions.allowTestBlockWrites());
        status.add("cheatPermissions", permissions);
        if (server != null) {
            status.addProperty("world", server.getWorldData().getLevelName());
            status.addProperty("worldFolder", worldFolder(server));
            if (!server.getPlayerList().getPlayers().isEmpty()) {
                ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                status.addProperty("dimension", player.serverLevel().dimension().location().toString());
                status.add("playerPosition", positionJson(player.blockPosition()));
            }
        }
        return status;
    }

    private static String worldFolder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
    }

    private static BlockLocation location(JsonObject data) {
        return new BlockLocation(dimension(data), position(data));
    }

    private static ServerLevel loadedLevel(MinecraftServer server, ResourceLocation dimension, BlockPos position) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level == null) {
            throw new BridgeError(404, "unknown_dimension", "Dimension is unavailable: " + dimension);
        }
        if (!level.isInWorldBounds(position)) {
            throw new BridgeError(400, "out_of_bounds", "Position is outside world bounds");
        }
        if (!level.hasChunkAt(position)) {
            throw new BridgeError(409, "chunk_not_loaded", "Position is in an unloaded chunk");
        }
        return level;
    }

    private static ServerLevel fixtureLevel(MinecraftServer server, TestTarget fixture, String description) {
        requireFixtureWorld(server, fixture.worldFolder, description);
        return loadedLevel(server, fixture.dimension, fixture.position);
    }

    private static ServerLevel storageFixtureLevel(MinecraftServer server, StorageFixture fixture, String description) {
        requireFixtureWorld(server, fixture.worldFolder, description);
        ServerLevel level = loadedLevel(server, fixture.dimension, fixture.source);
        loadedLevel(server, fixture.dimension, fixture.target);
        return level;
    }

    private static void requireFixtureWorld(MinecraftServer server, String folder, String description) {
        if (!folder.equals(worldFolder(server))) {
            throw new BridgeError(403, "fixture_denied", "This world is not configured for " + description);
        }
    }

    static boolean isClearFixtureSite(ServerLevel level, BlockPos position) {
        return level.getBlockState(position).is(Blocks.AIR)
                && level.getBlockState(position.below()).isFaceSturdy(level, position.below(), Direction.UP);
    }

    private static JsonObject blockInfo(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        JsonObject output = new JsonObject();
        output.addProperty("dimension", level.dimension().location().toString());
        output.add("position", positionJson(position));
        output.addProperty("block", ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString());
        JsonObject properties = new JsonObject();
        state.getValues().forEach((property, value) -> properties.addProperty(property.getName(), value.toString()));
        output.add("properties", properties);
        output.addProperty("hasBlockEntity", state.hasBlockEntity());
        return output;
    }

    private static StorageFixture readStorageFixture(Path path) {
        try {
            JsonObject config = GSON.fromJson(Files.readString(path), JsonObject.class);
            return new StorageFixture(stringField(config, "worldFolder"), dimension(config),
                    position(objectField(config, "source")), position(objectField(config, "target")));
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            throw new BridgeError(403, "fixture_disabled", "The storage fixture is not configured");
        }
    }

    private static TestTarget readTestTarget(Path path) {
        try {
            JsonObject target = GSON.fromJson(Files.readString(path), JsonObject.class);
            return new TestTarget(stringField(target, "worldFolder"), dimension(target), position(target));
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            throw new BridgeError(403, "test_target_unavailable", "Configure the test world and one permitted coordinate first");
        }
    }

    private static JsonObject setTestBlock(MinecraftServer server, TestTarget target, ResourceLocation dimension,
                                           BlockPos position, BlockState expected, BlockState replacement) {
        if (!target.worldFolder.equals(worldFolder(server)) ||
                !target.dimension.equals(dimension) || !target.position.equals(position)) {
            throw new BridgeError(403, "test_target_denied", "World, dimension or position is outside the configured test target");
        }

        ServerLevel level = loadedLevel(server, dimension, position);
        BlockState before = level.getBlockState(position);
        if (!before.equals(expected)) {
            throw new BridgeError(409, "block_changed", "Current block did not match the expected state: " + before);
        }
        boolean changed = !before.equals(replacement);
        if (changed && !level.setBlock(position, replacement, Block.UPDATE_ALL)) {
            throw new BridgeError(409, "write_refused", "Minecraft did not apply the requested block state");
        }
        if (!level.getBlockState(position).equals(replacement)) {
            throw new BridgeError(409, "write_mismatch", "The game reports a different block state after the write");
        }
        if (changed) {
            LOGGER.info("Minecraft Automation MCP test write in {} {} at {}: {} -> {}", target.worldFolder,
                    dimension, position, before, replacement);
        }
        JsonObject result = blockInfo(level, position);
        result.addProperty("changed", changed);
        return result;
    }

    private static BlockState testState(String state) {
        return switch (state) {
            case "minecraft:air" -> Blocks.AIR.defaultBlockState();
            case "minecraft:oak_log[axis=x]" -> Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
            case "minecraft:oak_log[axis=y]" -> Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
            case "minecraft:oak_log[axis=z]" -> Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
            default -> throw new BridgeError(400, "invalid_test_block", "Only air and an explicitly oriented oak log are accepted");
        };
    }

    private static ResourceLocation dimension(JsonObject data) {
        return resourceId(data, "dimension");
    }

    private static void requireGt() {
        if (!ModList.get().isLoaded("gtceu")) {
            throw new BridgeError(409, "gt_unavailable", "GregTech CEu Modern is not loaded");
        }
    }

    private static boolean transferDirection(String direction, String endpoint, String errorMessage) {
        boolean inbound = direction.equals("to_" + endpoint);
        if (!inbound && !direction.equals("from_" + endpoint)) {
            throw new IllegalArgumentException(errorMessage);
        }
        return inbound;
    }

    private static JsonArray processingArray(JsonObject request, String field) {
        if (!request.has(field) || !request.get(field).isJsonArray()) {
            throw new IllegalArgumentException("Supply processing input and output arrays");
        }
        return request.getAsJsonArray(field);
    }

    private static JsonArray craftingGrid(JsonObject request) {
        if (!request.has("grid") || !request.get("grid").isJsonArray()) {
            throw new IllegalArgumentException("Missing crafting grid");
        }
        return request.getAsJsonArray("grid");
    }

    private static boolean booleanField(JsonObject data, String key) {
        if (!data.has(key) || !data.get(key).isJsonPrimitive() || !data.getAsJsonPrimitive(key).isBoolean()) {
            throw new IllegalArgumentException("Missing boolean: " + key);
        }
        return data.get(key).getAsBoolean();
    }

    private static void requireAe2() {
        if (!ModList.get().isLoaded("ae2")) {
            throw new BridgeError(409, "ae2_unavailable", "Applied Energistics 2 is not loaded");
        }
    }

    static ResourceLocation resourceId(JsonObject data, String key) {
        ResourceLocation id = ResourceLocation.tryParse(stringField(data, key));
        if (id == null) {
            throw new IllegalArgumentException("Invalid resource ID: " + key);
        }
        return id;
    }

    private static JsonObject objectField(JsonObject data, String key) {
        if (data == null || !data.has(key) || !data.get(key).isJsonObject()) {
            throw new IllegalArgumentException("Missing object: " + key);
        }
        return data.getAsJsonObject(key);
    }

    private static BlockPos position(JsonObject data) {
        if (!data.has("position") || !data.get("position").isJsonObject()) {
            throw new IllegalArgumentException("Missing position");
        }
        return coordinates(data.getAsJsonObject("position"));
    }

    private static BlockPos coordinates(JsonObject coordinates) {
        return new BlockPos(coordinate(coordinates, "x"), coordinate(coordinates, "y"), coordinate(coordinates, "z"));
    }

    static int coordinate(JsonObject coordinates, String axis) {
        if (!coordinates.has(axis) || !coordinates.get(axis).isJsonPrimitive() ||
                !coordinates.getAsJsonPrimitive(axis).isNumber()) {
            throw new IllegalArgumentException("Missing coordinate: " + axis);
        }
        try {
            return coordinates.get(axis).getAsBigDecimal().intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Invalid coordinate: " + axis, exception);
        }
    }

    static String stringField(JsonObject data, String key) {
        if (data == null || !data.has(key) || !data.get(key).isJsonPrimitive() ||
                !data.getAsJsonPrimitive(key).isString() || data.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException("Missing string: " + key);
        }
        return data.get(key).getAsString();
    }

    static JsonObject positionJson(BlockPos position) {
        JsonObject json = new JsonObject();
        json.addProperty("x", position.getX());
        json.addProperty("y", position.getY());
        json.addProperty("z", position.getZ());
        return json;
    }

    private static JsonObject error(String code, String message) {
        JsonObject data = new JsonObject();
        data.addProperty("code", code);
        data.addProperty("message", message);
        return data;
    }

    private static void respond(HttpExchange exchange, int status, JsonObject result) throws IOException {
        byte[] body = GSON.toJson(result).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private record BlockLocation(ResourceLocation dimension, BlockPos position) {
        ServerLevel level(MinecraftServer server) {
            return loadedLevel(server, dimension, position);
        }
    }

    private record TestTarget(String worldFolder, ResourceLocation dimension, BlockPos position) {}

    private record StorageFixture(String worldFolder, ResourceLocation dimension, BlockPos source, BlockPos target) {}

    static final class BridgeError extends RuntimeException {
        private final int status;
        private final String code;

        BridgeError(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
