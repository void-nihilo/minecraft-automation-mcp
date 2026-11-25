package minecraft.automation.mcp;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Mod("minecraft_automation_mcp")
public final class MinecraftAutomationBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final int MAX_REQUEST_BYTES = 256 * 1024;
    private static final int BATCH_RECEIPT_LIMIT = 128;
    private static final Path CONFIG_DIRECTORY = FMLPaths.CONFIGDIR.get();
    private static final Path PERMISSIONS_FILE = CONFIG_DIRECTORY.resolve(CheatPermissions.FILE_NAME);
    private static final Path TOKEN_FILE = configFile("bridge.token");
    private static final Path TARGET_FILE = configFile("test-target.json");
    private static final Path CHEST_FIXTURE_FILE = configFile("chest-fixture.json");
    private static final Path ME_WORKBENCH_FIXTURE_FILE = configFile("me-workbench-fixture.json");

    private final MeCraftingOperations meCrafting = new MeCraftingOperations();
    private final TreeFarmOperations treeFarm = new TreeFarmOperations();
    private final MeSpruceFarmOperations meSpruceFarm = new MeSpruceFarmOperations();
    private final Map<UUID, BatchReceipt> batchReceipts = new LinkedHashMap<>();
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
            meCrafting.tick();
            treeFarm.tick(event.getServer());
            meSpruceFarm.tick(event.getServer());
        }
    }

    private void onWorldStopping(ServerStoppingEvent event) {
        if (activeWorld == event.getServer()) {
            activeWorld = null;
            meCrafting.clear();
            batchReceipts.clear();
            treeFarm.stop();
            meSpruceFarm.stop();
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
            case "set_wandering_trader_spawning" -> {
                String expectedWorld = stringField(request, "expectedWorldFolder");
                boolean enabled = booleanField(request, "enabled");
                yield onServerThread(server, () -> setWanderingTraderSpawning(server, expectedWorld, enabled));
            }
            case "set_overworld_chunks_to_plains" -> {
                String expectedWorld = stringField(request, "expectedWorldFolder");
                int minX = coordinate(request, "minChunkX");
                int maxX = coordinate(request, "maxChunkX");
                int minZ = coordinate(request, "minChunkZ");
                int maxZ = coordinate(request, "maxChunkZ");
                yield onServerThread(server, () -> setOverworldChunksToPlains(server, expectedWorld, minX, maxX, minZ, maxZ));
            }
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
                        drive.me(server), expected));
            }
            case "harvest_rubber_trunk_to_me" -> {
                requireAe2();
                BlockLocation site = location(request);
                BlockLocation drive = location(objectField(request, "drive"));
                String expectedWorld = stringField(request, "expectedWorldFolder");
                yield onServerThread(server, () -> {
                    requireExpectedWorld(server, expectedWorld);
                    return RubberTreeOperations.harvest(site.level(server), site.position, drive.me(server));
                });
            }
            case "list_ftb_quest_chapters", "list_ftb_quests", "get_ftb_quest" -> {
                if (!ModList.get().isLoaded("ftbquests")) {
                    throw new BridgeError(409, "ftb_quests_unavailable", "FTB Quests is not loaded");
                }
                yield onServerThread(server, () -> FtbQuestOperations.run(server, operation, request));
            }
            case "submit_ftb_item_from_me" -> {
                requireAe2();
                if (!ModList.get().isLoaded("ftbquests")) {
                    throw new BridgeError(409, "ftb_quests_unavailable", "FTB Quests is not loaded");
                }
                BlockLocation drive = location(objectField(request, "drive"));
                String expectedWorld = stringField(request, "expectedWorldFolder");
                yield onServerThread(server, () -> {
                    requireExpectedWorld(server, expectedWorld);
                    return FtbQuestOperations.run(server, operation, request, drive.me(server));
                });
            }
            case "check_ftb_task", "claim_ftb_reward" -> {
                if (!ModList.get().isLoaded("ftbquests")) {
                    throw new BridgeError(409, "ftb_quests_unavailable", "FTB Quests is not loaded");
                }
                String expectedWorld = stringField(request, "expectedWorldFolder");
                yield onServerThread(server, () -> {
                    requireExpectedWorld(server, expectedWorld);
                    return FtbQuestOperations.run(server, operation, request);
                });
            }
            case "start_tree_farm" -> {
                BlockLocation site = location(request);
                BlockPos supply = coordinates(objectField(request, "supply"));
                BlockPos output = coordinates(objectField(request, "output"));
                Direction supplySide = optionalSide(request, "supplySide");
                Direction outputSide = optionalSide(request, "outputSide");
                String tree = stringField(request, "tree");
                yield onServerThread(server, () -> treeFarm.start(site.level(server),
                        site.position, supply, supplySide, output, outputSide, tree));
            }
            case "get_tree_farm_status" -> onServerThread(server, treeFarm::status);
            case "stop_tree_farm" -> onServerThread(server, treeFarm::stop);
            case "start_me_spruce_farm" -> {
                requireAe2();
                BlockLocation site = location(request);
                BlockLocation drive = location(objectField(request, "drive"));
                String expectedWorld = stringField(request, "expectedWorldFolder");
                yield onServerThread(server, () -> {
                    requireExpectedWorld(server, expectedWorld);
                    return meSpruceFarm.start(site.level(server), site.position, drive.me(server));
                });
            }
            case "get_me_spruce_farm_status" -> onServerThread(server, meSpruceFarm::status);
            case "stop_me_spruce_farm" -> onServerThread(server, meSpruceFarm::stop);
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
            case "check_multiblock_blocks" -> onServerThread(server, () -> checkMultiblockBlocks(server, request));
            case "get_container" -> {
                BlockLocation site = location(request);
                yield onServerThread(server, () ->
                        ItemHandlerOperations.contents(site.level(server), site.position, site.side));
            }
            case "transfer_item" -> {
                BlockLocation source = location(objectField(request, "source"));
                BlockLocation target = location(objectField(request, "target"));
                int sourceSlot = coordinate(request, "sourceSlot");
                Integer targetSlot = request.has("targetSlot") ? coordinate(request, "targetSlot") : null;
                int amount = coordinate(request, "amount");
                String expectedStackToken = stringField(request, "expectedStackToken");
                yield onServerThread(server, () -> {
                    JsonObject result = ItemHandlerOperations.transfer(
                            source.level(server), source.position, source.side, sourceSlot,
                            target.level(server), target.position, target.side, targetSlot, amount, expectedStackToken);
                    LOGGER.info("Minecraft Automation MCP transferred {} {} from {} {} slot {} to {} {}", amount,
                            result.get("item").getAsString(), source.dimension, source.position,
                            sourceSlot, target.dimension, target.position);
                    return result;
                });
            }
            case "get_tank" -> {
                BlockLocation site = location(request);
                yield onServerThread(server, () ->
                        TankOperations.contents(site.level(server), site.position, site.side));
            }
            case "transfer_fluid" -> {
                BlockLocation source = location(objectField(request, "source"));
                BlockLocation target = location(objectField(request, "target"));
                int amount = coordinate(request, "amount");
                String expectedFluidToken = stringField(request, "expectedFluidToken");
                yield onServerThread(server, () -> {
                    JsonObject result = TankOperations.transfer(
                            source.level(server), source.position, source.side,
                            target.level(server), target.position, target.side, amount, expectedFluidToken);
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
                JsonObject providerLocation = objectField(request, "provider");
                BlockPos provider = position(providerLocation);
                Direction providerSide = optionalSide(providerLocation);
                int slot = coordinate(request, "slot");
                boolean processing = "encode_me_processing_pattern".equals(operation);
                ResourceLocation recipe = processing ? null : resourceId(request, "recipe");
                JsonArray ingredients = processing ? processingArray(request, "inputs") : craftingGrid(request);
                JsonArray outputs = processing ? processingArray(request, "outputs") : null;
                yield onServerThread(server, () -> {
                    loadedLevel(server, site.dimension, provider);
                    return processing
                            ? MeCraftingOperations.installProcessingPattern(site.me(server), provider, providerSide, slot, ingredients, outputs)
                            : MeCraftingOperations.installPattern(site.me(server), provider, providerSide, slot, recipe, ingredients);
                });
            }
            case "get_me_craftable", "order_me_craft" -> {
                requireAe2();
                BlockLocation site = location(request);
                ResourceLocation item = resourceId(request, "item");
                if ("get_me_craftable".equals(operation)) {
                    yield onServerThread(server, () ->
                            MeCraftingOperations.craftables(site.me(server), item));
                }
                UUID id = UUID.fromString(stringField(request, "orderId"));
                String variant = request.has("variant") ? stringField(request, "variant") : null;
                int amount = coordinate(request, "amount");
                yield onServerThread(server, () ->
                        meCrafting.order(site.me(server), id, item, variant, amount));
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
                            site.me(server), recipe, grid);
                    LOGGER.info("Minecraft Automation MCP ran workbench recipe {} at ME {} {}: {}", recipe, site.dimension, site.position, result);
                    return result;
                });
            }
            case "craft_batch_from_me" -> {
                requireAe2();
                BlockLocation site = location(request);
                ResourceLocation recipe = resourceId(request, "recipe");
                JsonArray grid = craftingGrid(request);
                int repetitions = coordinate(request, "repetitions");
                if (repetitions < 1 || repetitions > 64) {
                    throw new IllegalArgumentException("Choose between 1 and 64 crafts");
                }
                yield onServerThread(server, () -> runBatchWrite(server, request,
                        () -> MeWorkbenchOperations.craftBatch(site.me(server), recipe, grid, repetitions)));
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
                yield onServerThread(server, () -> MeOperations.contents(
                        site.me(server), id, item ? AEItemKey.class : AEFluidKey.class, field));
            }
            case "get_me_machine_snapshot" -> onServerThread(server, () -> meMachineSnapshot(server, request));
            case "transfer_me_machine_batch" -> {
                requireAe2();
                yield onServerThread(server, () -> runBatchWrite(server, request,
                        () -> transferMachineBatch(server, request)));
            }
            case "get_host_inventory" -> onServerThread(server, () -> HostInventoryOperations.contents(server));
            case "transfer_host_me_item" -> {
                requireAe2();
                BlockLocation drive = location(objectField(request, "drive"));
                ResourceLocation item = resourceId(request, "item");
                boolean toMe = transferDirection(stringField(request, "direction"), "me", "Invalid ME transfer direction");
                int slot = coordinate(request, "slot");
                int amount = coordinate(request, "amount");
                String token = stringField(request, "expectedSourceToken");
                String expectedWorld = stringField(request, "expectedWorldFolder");
                yield onServerThread(server, () -> {
                    requireExpectedWorld(server, expectedWorld);
                    return HostInventoryOperations.transfer(server, drive.me(server), slot, item, amount, token, toMe);
                });
            }
            case "place_block_from_me" -> {
                requireAe2();
                requireGt();
                BlockLocation site = location(request);
                BlockLocation drive = location(objectField(request, "drive"));
                ResourceLocation item = resourceId(request, "item");
                String token = stringField(request, "expectedSourceToken");
                String expectedWorld = stringField(request, "expectedWorldFolder");
                yield onServerThread(server, () -> {
                    requireExpectedWorld(server, expectedWorld);
                    return MeBlockPlacementOperations.place(site.level(server), site.position, drive.me(server), item, token);
                });
            }
            case "install_gt_cover_from_me" -> {
                requireAe2();
                requireGt();
                ResourceLocation item = resourceId(request, "item");
                Direction side = side(request);
                BlockLocation site = location(request);
                BlockLocation drive = location(objectField(request, "drive"));
                String token = stringField(request, "expectedSourceToken");
                String expectedWorld = stringField(request, "expectedWorldFolder");
                yield onServerThread(server, () -> {
                    requireExpectedWorld(server, expectedWorld);
                    return MeGtCoverOperations.install(site.level(server), site.position, side,
                            drive.me(server), item, token);
                });
            }
            case "transfer_me_item" -> {
                requireAe2();
                if (request.has("container") == request.has("chest")) {
                    throw new IllegalArgumentException("Supply one container location");
                }
                BlockLocation container = location(objectField(request, request.has("container") ? "container" : "chest"));
                BlockLocation drive = location(objectField(request, "drive"));
                ResourceLocation item = resourceId(request, "item");
                String direction = stringField(request, "direction");
                boolean toMe = transferDirection(direction, "me", "Invalid ME transfer direction");
                Integer slot = toMe ? coordinate(request, "sourceSlot")
                        : request.has("targetSlot") ? coordinate(request, "targetSlot") : null;
                int amount = coordinate(request, "amount");
                String token = stringField(request, "expectedSourceToken");
                yield onServerThread(server, () -> {
                    JsonObject result = MeOperations.transfer(
                            toMe ? ItemHandlerOperations.at(container.level(server), container.position, container.side)
                                    : ItemHandlerOperations.insertionTarget(container.level(server), container.position, container.side),
                            drive.me(server), toMe, item, amount, slot, token);
                    LOGGER.info("Minecraft Automation MCP moved {} {} {} between container {} {} and ME node {} {}", amount, item,
                            direction, container.dimension, container.position, drive.dimension, drive.position);
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
                            tank.level(server), tank.position, tank.side,
                            drive.me(server),
                            toMe, fluid, amount, token);
                    LOGGER.info("Minecraft Automation MCP moved {} mB of {} {} between tank {} {} and ME drive {} {}", amount, fluid,
                            direction, tank.dimension, tank.position, drive.dimension, drive.position);
                    return result;
                });
            }
            case "fill_me_fluid_bucket" -> {
                requireAe2();
                BlockLocation drive = location(objectField(request, "drive"));
                ResourceLocation fluid = resourceId(request, "fluid");
                String fluidToken = stringField(request, "expectedFluidToken");
                String bucketToken = stringField(request, "expectedBucketToken");
                String expectedWorld = stringField(request, "expectedWorldFolder");
                yield onServerThread(server, () -> {
                    requireExpectedWorld(server, expectedWorld);
                    return MeFluidOperations.fillBucket(drive.me(server), fluid, fluidToken, bucketToken);
                });
            }
            case "find_gt_recipes" -> {
                requireGt();
                boolean fluid = request.has("fluid");
                ResourceLocation expected = resourceId(request, fluid ? "fluid" : "item");
                ResourceLocation type = request.has("recipeType") ? resourceId(request, "recipeType") : null;
                yield onServerThread(server, () -> GtRecipeOperations.find(server, expected, fluid, type));
            }
            case "configure_gt_machine" -> {
                requireGt();
                BlockLocation site = location(request);
                Boolean working = request.has("workingEnabled") ? booleanField(request, "workingEnabled") : null;
                Integer circuit = request.has("circuit") ? coordinate(request, "circuit") : null;
                yield onServerThread(server, () -> GtMachineOperations.configure(
                        site.level(server), site.position, working, circuit));
            }
            case "get_gt_multiblock_layout" -> {
                requireGt();
                boolean byId = request.has("structure");
                if (byId == request.has("controller")) {
                    throw new BridgeError(400, "multiblock_target_required", "Supply either a structure ID or a controller location");
                }
                int previewIndex = request.has("previewIndex") ? coordinate(request, "previewIndex") : 0;
                var parts = multiblockParts(request);
                if (byId) {
                    ResourceLocation id = resourceId(request, "structure");
                    yield onServerThread(server, () -> GtMultiblockOperations.layout(id, previewIndex, parts));
                }
                BlockLocation controller = location(objectField(request, "controller"));
                yield onServerThread(server, () -> GtMultiblockOperations.layout(
                        controller.level(server), controller.position, previewIndex, parts));
            }
            case "build_gt_multiblock_from_me" -> {
                ResourceLocation kind = resourceId(request, "structure");
                int previewIndex = request.has("previewIndex") ? coordinate(request, "previewIndex") : 0;
                requireGt();
                requireAe2();
                BlockLocation controller = location(objectField(request, "controller"));
                BlockLocation drive = location(objectField(request, "drive"));
                var parts = multiblockParts(request);
                yield onServerThread(server, 25, () -> {
                    JsonObject result = GtMultiblockOperations.build(
                            controller.level(server), controller.position,
                            drive.me(server), kind, previewIndex, parts);
                    LOGGER.info("Minecraft Automation MCP built {} at {} {} from ME {} {}", kind, controller.dimension,
                            controller.position, drive.dimension, drive.position);
                    return result;
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
                            drive.me(server), token);
                    LOGGER.info("Minecraft Automation MCP serviced GT maintenance hatch {} using {} duct tape from ME {}",
                            hatch.position, result.get("tapeUsed").getAsInt(), drive.position);
                    return result;
                });
            }
            case "prepare_chest_test" -> {
                StorageFixture fixture = readStorageFixture(CHEST_FIXTURE_FILE);
                yield onCheatServerThread(server, permission, () -> ChestOperations.prepareTestChests(
                        storageFixtureLevel(server, fixture, "the chest fixture"), fixture.source, fixture.target));
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

    private JsonObject onCheatServerThread(MinecraftServer server, Permission permission, Supplier<JsonObject> action) {
        return onServerThread(server, () -> {
            requireCheatPermission(permission);
            return action.get();
        });
    }

    private static JsonObject meMachineSnapshot(MinecraftServer server, JsonObject request) {
        JsonObject result = new JsonObject();
        if (request.has("items")) {
            requireAe2();
            BlockLocation drive = location(objectField(request, "drive"));
            List<ResourceLocation> ids = new ArrayList<>();
            for (JsonElement entry : batchArray(request, "items", 128)) {
                JsonObject item = new JsonObject();
                item.add("id", entry);
                ids.add(resourceId(item, "id"));
            }
            try {
                result.add("items", MeOperations.contents(drive.me(server), ids, AEItemKey.class, "item"));
            } catch (BridgeError exception) {
                result.add("itemsError", error(exception.code(), exception.getMessage()));
            }
        }
        if (request.has("machines")) {
            JsonArray machines = new JsonArray();
            for (JsonElement entry : batchArray(request, "machines", 32)) {
                JsonObject machine = batchObject(entry);
                BlockLocation site = location(machine);
                JsonObject row = itemLocation(site, legacyMachineLabel(machine));
                try {
                    row.add("state", ItemHandlerOperations.contents(site.level(server), site.position, site.side));
                } catch (BridgeError exception) {
                    row.add("error", error(exception.code(), exception.getMessage()));
                }
                machines.add(row);
            }
            result.add("machines", machines);
        }
        if (result.size() == 0) {
            throw new IllegalArgumentException("Supply items, machines, or both");
        }
        return result;
    }

    private static JsonArray batchArray(JsonObject request, String field, int limit) {
        JsonElement value = request.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("Missing array: " + field);
        }
        JsonArray array = value.getAsJsonArray();
        if (array.isEmpty() || array.size() > limit) {
            throw new IllegalArgumentException("Invalid batch size: " + field);
        }
        return array;
    }

    private static JsonObject batchObject(JsonElement element) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Batch entries must be objects");
        }
        return element.getAsJsonObject();
    }

    private static String legacyMachineLabel(JsonObject machine) {
        return machine.has("kind") ? stringField(machine, "kind") : null;
    }

    private static JsonObject itemLocation(BlockLocation site, String label) {
        JsonObject result = new JsonObject();
        if (label != null) {
            result.addProperty("kind", label);
        }
        result.addProperty("dimension", site.dimension.toString());
        result.add("position", positionJson(site.position));
        if (site.side != null) {
            result.addProperty("side", site.side.getName());
        }
        return result;
    }

    private JsonObject runBatchWrite(MinecraftServer server, JsonObject request, Supplier<JsonObject> write) {
        requireExpectedWorld(server, stringField(request, "expectedWorldFolder"));
        UUID id = UUID.fromString(stringField(request, "requestId"));
        BatchReceipt receipt = batchReceipts.get(id);
        if (receipt != null) {
            if (!receipt.request.equals(request)) {
                throw new BridgeError(409, "batch_request_conflict", "The request ID already identifies another batch");
            }
            return receipt.result;
        }
        JsonObject result;
        try {
            result = write.get();
        } catch (BridgeError | IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.error("Minecraft Automation MCP batch {} failed with an unknown outcome", id, exception);
            result = error("batch_outcome_unknown", "A step failed unexpectedly. Verify inventory and slots before submitting more writes.");
        }
        result.addProperty("requestId", id.toString());
        if (batchReceipts.size() == BATCH_RECEIPT_LIMIT) {
            batchReceipts.remove(batchReceipts.keySet().iterator().next());
        }
        batchReceipts.put(id, new BatchReceipt(request, result));
        return result;
    }

    private static JsonObject transferMachineBatch(MinecraftServer server, JsonObject request) {
        BlockLocation drive = location(objectField(request, "drive"));
        List<MachineTransfer> transfers = new ArrayList<>();
        for (JsonElement entry : batchArray(request, "transfers", 32)) {
            transfers.add(MachineTransfer.parse(batchObject(entry)));
        }
        ServerLevel driveLevel = drive.level(server);
        var grid = drive.me(server).grid();
        var actor = MeOperations.playerAction(driveLevel);
        var storage = grid.getStorageService().getInventory();
        var initial = storage.getAvailableStacks();
        AEItemKey[] sources = new AEItemKey[transfers.size()];
        JsonObject[] sourceErrors = new JsonObject[transfers.size()];
        for (int index = 0; index < transfers.size(); index++) {
            MachineTransfer transfer = transfers.get(index);
            if (transfer.toMachine) {
                try {
                    sources[index] = MeOperations.resolveMeStack(initial, AEItemKey.class, transfer.item, transfer.token);
                } catch (BridgeError exception) {
                    sourceErrors[index] = error(exception.code(), exception.getMessage());
                }
            }
        }

        JsonArray results = new JsonArray();
        for (int index = 0; index < transfers.size(); index++) {
            MachineTransfer transfer = transfers.get(index);
            JsonObject row = itemLocation(transfer.site, transfer.label);
            row.addProperty("index", index);
            row.addProperty("direction", transfer.toMachine ? "to_machine" : "from_machine");
            row.addProperty("item", transfer.item.toString());
            row.addProperty("slot", transfer.slot);
            row.addProperty("requested", transfer.amount);
            if (sourceErrors[index] != null) {
                row.add("error", sourceErrors[index]);
            } else {
                try {
                    AEItemKey source = sources[index];
                    // A prior transfer in this batch can change the count of the same exact variant.
                    String token = source == null ? transfer.token
                            : MeOperations.meToken(source, MeOperations.count(storage, source));
                    var site = transfer.site;
                    var handler = transfer.toMachine
                            ? ItemHandlerOperations.insertionTarget(site.level(server), site.position, site.side)
                            : ItemHandlerOperations.at(site.level(server), site.position, site.side);
                    row.add("transfer", MeOperations.transfer(handler, grid, actor, !transfer.toMachine,
                            transfer.item, transfer.amount, transfer.slot, token));
                } catch (BridgeError exception) {
                    row.add("error", error(exception.code(), exception.getMessage()));
                }
            }
            results.add(row);
        }
        JsonObject result = new JsonObject();
        result.add("transfers", results);
        return result;
    }

    private record MachineTransfer(BlockLocation site, String label, boolean toMachine, int slot,
                                   ResourceLocation item, int amount, String token) {
        static MachineTransfer parse(JsonObject entry) {
            JsonObject machine = objectField(entry, "machine");
            int slot = coordinate(entry, "slot");
            int amount = coordinate(entry, "amount");
            if (slot < 0 || amount < 1) {
                throw new IllegalArgumentException("Choose a nonnegative slot and positive transfer amount");
            }
            return new MachineTransfer(location(machine), legacyMachineLabel(machine),
                    transferDirection(stringField(entry, "direction"), "machine", "Invalid batch transfer direction"),
                    slot, resourceId(entry, "item"), amount, stringField(entry, "expectedSourceToken"));
        }

    }

    private record BatchReceipt(JsonObject request, JsonObject result) {}

    private static JsonObject checkMultiblockBlocks(MinecraftServer server, JsonObject request) {
        ResourceLocation dimension = dimension(request);
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level == null) {
            throw new BridgeError(404, "unknown_dimension", "Dimension is unavailable: " + dimension);
        }
        Map<BlockPos, Block> expected = new LinkedHashMap<>();
        for (JsonElement entry : batchArray(request, "blocks", 1024)) {
            JsonObject row = batchObject(entry);
            BlockPos position = position(row);
            ResourceLocation id = resourceId(row, "block");
            if (!ForgeRegistries.BLOCKS.containsKey(id)) {
                throw new BridgeError(400, "unknown_block", "Expected block ID is not registered: " + id);
            }
            if (expected.putIfAbsent(position, ForgeRegistries.BLOCKS.getValue(id)) != null) {
                throw new BridgeError(400, "duplicate_position", "Provide each checked position only once");
            }
        }
        int matched = 0;
        JsonArray missing = new JsonArray();
        JsonArray mismatched = new JsonArray();
        JsonArray unavailable = new JsonArray();
        for (var entry : expected.entrySet()) {
            BlockPos position = entry.getKey();
            JsonObject row = new JsonObject();
            row.add("position", positionJson(position));
            row.addProperty("expected", ForgeRegistries.BLOCKS.getKey(entry.getValue()).toString());
            if (!level.isInWorldBounds(position) || !level.hasChunkAt(position)) {
                row.addProperty("reason", level.isInWorldBounds(position) ? "chunk_not_loaded" : "out_of_bounds");
                unavailable.add(row);
                continue;
            }
            BlockState actual = level.getBlockState(position);
            if (actual.is(entry.getValue())) {
                matched++;
            } else {
                row.addProperty("actual", ForgeRegistries.BLOCKS.getKey(actual.getBlock()).toString());
                (actual.isAir() ? missing : mismatched).add(row);
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("dimension", dimension.toString());
        result.addProperty("checked", expected.size() - unavailable.size());
        result.addProperty("matched", matched);
        result.add("missing", missing);
        result.add("mismatched", mismatched);
        result.add("unavailable", unavailable);
        return result;
    }

    private JsonObject onServerThread(MinecraftServer server, Supplier<JsonObject> action) {
        return onServerThread(server, 5, action);
    }

    private JsonObject onServerThread(MinecraftServer server, int completionTimeoutSeconds, Supplier<JsonObject> action) {
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
            return result.get(completionTimeoutSeconds, TimeUnit.SECONDS);
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
            status.addProperty("doTraderSpawning", server.getGameRules().getBoolean(GameRules.RULE_DO_TRADER_SPAWNING));
            if (!server.getPlayerList().getPlayers().isEmpty()) {
                ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                status.addProperty("dimension", player.serverLevel().dimension().location().toString());
                status.add("playerPosition", positionJson(player.blockPosition()));
            }
        }
        return status;
    }

    private JsonObject setWanderingTraderSpawning(MinecraftServer server, String expectedWorld, boolean enabled) {
        if (server != activeWorld || !worldFolder(server).equals(expectedWorld)) {
            throw new BridgeError(409, "world_changed", "The loaded singleplayer save does not match expectedWorldFolder");
        }
        var rule = server.getGameRules().getRule(GameRules.RULE_DO_TRADER_SPAWNING);
        if (rule.get() != enabled) {
            rule.set(enabled, server);
            server.saveEverything(true, true, false);
        }
        return gameStatus(server);
    }

    private JsonObject setOverworldChunksToPlains(MinecraftServer server, String expectedWorld,
                                                 int minX, int maxX, int minZ, int maxZ) {
        if (server != activeWorld || !worldFolder(server).equals(expectedWorld)) {
            throw new BridgeError(409, "world_changed", "The loaded singleplayer save does not match expectedWorldFolder");
        }
        if (minX < -1875000 || maxX > 1874999 || minZ < -1875000 || maxZ > 1874999
                || minX > maxX || minZ > maxZ || (long) (maxX - minX + 1) * (maxZ - minZ + 1) > 64) {
            throw new BridgeError(400, "invalid_chunk_bounds", "Select between 1 and 64 Overworld chunks");
        }
        var level = server.overworld();
        var plains = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
        var sampler = level.getChunkSource().randomState().sampler();
        List<ChunkAccess> chunks = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                chunks.add(level.getChunk(x, z));
            }
        }
        for (ChunkAccess chunk : chunks) {
            chunk.fillBiomesFromNoise((x, y, z, climate) -> plains, sampler);
            chunk.setUnsaved(true);
        }
        level.getChunkSource().chunkMap.resendBiomesForChunks(chunks);
        JsonObject result = new JsonObject();
        result.addProperty("worldFolder", expectedWorld);
        result.addProperty("biome", "minecraft:plains");
        result.addProperty("chunksUpdated", chunks.size());
        return result;
    }

    private static void requireExpectedWorld(MinecraftServer server, String expectedWorld) {
        if (!worldFolder(server).equals(expectedWorld)) {
            throw new BridgeError(409, "world_changed", "The loaded singleplayer save does not match expectedWorldFolder");
        }
    }

    private static String worldFolder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
    }

    private static BlockLocation location(JsonObject data) {
        return new BlockLocation(dimension(data), position(data), optionalSide(data));
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
        level.getBiome(position).unwrapKey().ifPresent(key -> output.addProperty("biome", key.location().toString()));
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

    private static Direction optionalSide(JsonObject data) {
        return optionalSide(data, "side");
    }

    private static Direction optionalSide(JsonObject data, String field) {
        return data.has(field) ? side(data, field) : null;
    }

    private static Direction side(JsonObject data) {
        return side(data, "side");
    }

    private static Direction side(JsonObject data, String field) {
        Direction side = Direction.byName(stringField(data, field));
        if (side == null) {
            throw new IllegalArgumentException("Invalid block side");
        }
        return side;
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

    private static Map<BlockPos, ResourceLocation> multiblockParts(JsonObject request) {
        if (!request.has("parts")) {
            return Map.of();
        }
        if (!request.get("parts").isJsonArray() || request.getAsJsonArray("parts").size() > 128) {
            throw new BridgeError(400, "invalid_parts", "Specify at most 128 part substitutions");
        }
        var parts = new LinkedHashMap<BlockPos, ResourceLocation>();
        for (var element : request.getAsJsonArray("parts")) {
            if (!element.isJsonObject()) {
                throw new BridgeError(400, "invalid_part", "Provide an offset and block for each part");
            }
            var part = element.getAsJsonObject();
            BlockPos offset = coordinates(objectField(part, "offset"));
            if (parts.putIfAbsent(offset, resourceId(part, "block")) != null) {
                throw new BridgeError(400, "duplicate_part", "Choose distinct port offsets");
            }
        }
        return parts;
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

    private record BlockLocation(ResourceLocation dimension, BlockPos position, Direction side) {
        ServerLevel level(MinecraftServer server) {
            return loadedLevel(server, dimension, position);
        }

        MeOperations.Endpoint me(MinecraftServer server) {
            return new MeOperations.Endpoint(level(server), position, side);
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
