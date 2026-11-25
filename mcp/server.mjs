import { readdir, readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, isAbsolute, join } from "node:path";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const discoveryDirectory = join(homedir(), ".minecraft-automation-mcp", "instances");
const instanceIdPattern = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const stateTokenPattern = /^[0-9a-f]{64}$/;
const server = new McpServer({ name: "minecraft-automation-mcp", version: "0.1.0" });

function result(data, isError = false) {
  return {
    content: [{ type: "text", text: JSON.stringify(data) }],
    structuredContent: data,
    isError,
  };
}

function errorResult(code, message) {
  return result({ code, message }, true);
}

function disconnected(
  operation,
  message = "Start Minecraft with the Minecraft Automation MCP bridge mod installed.",
) {
  if (operation === "get_game_status") {
    return result({ connected: false, worldLoaded: false, message });
  }
  return errorResult("game_unavailable", message);
}

async function readRegistration(instanceId) {
  try {
    const { configDirectory, port, pid } = JSON.parse(
      await readFile(join(discoveryDirectory, `${instanceId}.json`), "utf8"),
    );
    if (typeof configDirectory !== "string" || !isAbsolute(configDirectory) ||
        !Number.isInteger(port) || port < 1 || port > 65535 ||
        !Number.isInteger(pid) || pid < 1 || pid > 2147483647) {
      return null;
    }
    try {
      process.kill(pid, 0);
    } catch (error) {
      if (error.code !== "EPERM" && error.code !== "EACCES") {
        return null;
      }
    }
    return { instanceId, configDirectory, port, pid };
  } catch {
    return null;
  }
}

async function registrations() {
  let files;
  try {
    files = await readdir(discoveryDirectory);
  } catch (error) {
    if (error.code === "ENOENT") {
      return [];
    }
    throw error;
  }
  const ids = files.filter((file) => file.endsWith(".json"))
    .map((file) => file.slice(0, -5)).filter((id) => instanceIdPattern.test(id)).sort();
  return (await Promise.all(ids.map(readRegistration))).filter(Boolean);
}

async function requestInstance(instance, operation, args, timeout = operation === "build_gt_multiblock_from_me" ? 30000 : 7000) {
  let token;
  try {
    token = (await readFile(join(instance.configDirectory, "minecraft-automation-mcp-bridge.token"), "utf8")).trim();
    if (!stateTokenPattern.test(token)) {
      throw new Error("Invalid bridge token");
    }
  } catch (error) {
    if (error.code === "ENOENT") {
      return disconnected(operation);
    }
    return errorResult("token_unavailable", "Could not read the local bridge token.");
  }

  try {
    const response = await fetch(`http://127.0.0.1:${instance.port}/bridge`, {
      method: "POST",
      headers: {
        "Authorization": "Bearer " + token,
        "Content-Type": "application/json",
        "X-Minecraft-Automation-MCP-Instance": instance.instanceId,
      },
      body: JSON.stringify({ ...args, operation }),
      redirect: "error",
      signal: AbortSignal.timeout(timeout),
    });
    return result(await response.json(), !response.ok);
  } catch (error) {
    if (error.cause?.code === "ECONNREFUSED") {
      return disconnected(operation, "The game bridge is offline.");
    }
    return errorResult("bridge_unavailable", "The game bridge did not return a valid response.");
  }
}

async function listGameInstances() {
  try {
    const instances = await Promise.all((await registrations()).map(async (instance) => {
      const { structuredContent } = await requestInstance(instance, "get_game_status", {}, 2000);
      return {
        connected: false,
        worldLoaded: false,
        ...structuredContent,
        instanceId: instance.instanceId,
        gameDirectory: dirname(instance.configDirectory),
        pid: instance.pid,
      };
    }));
    return result({ instances });
  } catch {
    return errorResult("discovery_unavailable", "Could not read the local bridge registrations.");
  }
}

async function callBridge(operation, { instanceId, ...args }) {
  if (instanceId !== undefined) {
    const instance = await readRegistration(instanceId);
    return instance
      ? requestInstance(instance, operation, args)
      : disconnected(operation, "The selected instance is offline. Call list_game_instances to find running instances.");
  }

  let instances;
  try {
    instances = await registrations();
  } catch {
    return errorResult("discovery_unavailable", "Could not read the local bridge registrations.");
  }
  if (instances.length === 0) {
    return disconnected(operation);
  }
  if (instances.length > 1) {
    return errorResult(
      "instance_required",
      "Multiple Minecraft instances are running. Call list_game_instances and pass the target instanceId.",
    );
  }
  return requestInstance(instances[0], operation, args);
}

function registerTool(name, config) {
  server.registerTool(name, {
    ...config,
    inputSchema: {
      ...config.inputSchema,
      instanceId: z.string().regex(instanceIdPattern).optional()
        .describe("Target instance from list_game_instances. Required when multiple instances are running."),
    },
    annotations: { ...config.annotations, openWorldHint: false },
  }, (args) => callBridge(name, args));
}

function registerReadTool(name, config) {
  registerTool(name, { ...config, annotations: { readOnlyHint: true } });
}

const stateToken = z.string().regex(stateTokenPattern);
const resourceId = z.string().min(1);
const positiveAmount = z.number().int().positive().max(2147483647);
const slotIndex = z.number().int().nonnegative();
const meDirection = z.enum(["to_me", "from_me"]);
const coordinate = z.number().int().min(-2147483648).max(2147483647);
const blockLocation = {
  dimension: resourceId,
  position: z.object({ x: coordinate, y: coordinate, z: coordinate }),
};
const blockLocationObject = z.object(blockLocation);
const blockSide = z.enum(["down", "up", "north", "south", "west", "east"]);
const sidedLocation = { ...blockLocation, side: blockSide.optional() };
const sidedLocationObject = z.object(sidedLocation);
const batchRequestId = z.string().regex(instanceIdPattern)
  .describe("Use a new UUID for each batch. Keep it and all arguments unchanged to retrieve the same receipt after a timeout.");

// 实例与世界

server.registerTool("list_game_instances", {
  description: "Discover local Minecraft instances running the Forge bridge and read their instance IDs, game directories, and world status.",
  annotations: { readOnlyHint: true, openWorldHint: false },
}, listGameInstances);

registerReadTool("get_game_status", {
  description: "Check the running Forge bridge, current singleplayer world, and active cheat permissions.",
});

registerReadTool("get_block", {
  description: "Read a block and its properties at a loaded coordinate in the given dimension.",
  inputSchema: blockLocation,
});

registerTool("set_wandering_trader_spawning", {
  description: "Set whether wandering traders can spawn in the specified loaded singleplayer save.",
  inputSchema: {
    expectedWorldFolder: z.string().min(1),
    enabled: z.boolean(),
  },
  annotations: { destructiveHint: false, idempotentHint: true },
});

registerTool("set_overworld_chunks_to_plains", {
  description: "Replace all biomes with plains in up to 64 full Overworld chunks in the specified loaded save. Chunk bounds are inclusive.",
  inputSchema: {
    expectedWorldFolder: z.string().min(1),
    minChunkX: coordinate, maxChunkX: coordinate,
    minChunkZ: coordinate, maxChunkZ: coordinate,
  },
  annotations: { destructiveHint: true, idempotentHint: true },
});

registerTool("place_block_from_me", {
  description: "Place one untagged block item from ME into loaded empty space above solid floor, with no living entity in the target. Does not place covers.",
  inputSchema: {
    ...blockLocation, drive: sidedLocationObject, expectedWorldFolder: z.string().min(1),
    item: resourceId, expectedSourceToken: stateToken,
  },
  annotations: { destructiveHint: false },
});

// 库存与搬运
const batchMachine = z.object({ ...sidedLocation, kind: z.string().optional().describe("Legacy label. Forge determines item access.") });

registerReadTool("get_container", {
  description: "Read Forge item slots and source stack tokens, plus native machine status where available. Include side for a sided handler.",
  inputSchema: sidedLocation,
});

registerTool("transfer_item", {
  description: "Move an exact amount from a Forge item slot to another handler. Use the same source side as get_container and a fresh source token.",
  inputSchema: {
    source: sidedLocationObject,
    target: sidedLocationObject,
    sourceSlot: slotIndex,
    targetSlot: slotIndex.optional(),
    amount: positiveAmount,
    expectedStackToken: stateToken,
  },
  annotations: { destructiveHint: true },
});

registerReadTool("get_tank", {
  description: "Read a block's Forge fluid handler. Returns compartments, capacities and exact fluid variant tokens. Supply side for a sided handler.",
  inputSchema: sidedLocation,
});

registerTool("transfer_fluid", {
  description: "Move the exact amount in mB between two Forge fluid handlers. Use the source fluid token from get_tank on the same side. The receiving handler chooses the compartment.",
  inputSchema: {
    source: sidedLocationObject,
    target: sidedLocationObject,
    amount: positiveAmount,
    expectedFluidToken: stateToken,
  },
  annotations: { destructiveHint: true },
});

registerReadTool("get_me_item", {
  description: "Read the count and variant tokens for one item in an active AE2 network, using an AE2 node as the location.",
  inputSchema: { ...sidedLocation, item: resourceId },
});

registerTool("transfer_me_item", {
  description: "Move items between a Forge item handler and powered ME. Use container (chest is a legacy alias), the matching side, and a fresh source token.",
  inputSchema: {
    container: sidedLocationObject.optional(),
    chest: sidedLocationObject.optional(),
    drive: sidedLocationObject,
    direction: meDirection,
    item: resourceId,
    amount: positiveAmount,
    sourceSlot: slotIndex.optional(),
    targetSlot: slotIndex.optional(),
    expectedSourceToken: stateToken,
  },
  annotations: { destructiveHint: true },
});

registerReadTool("get_me_machine_snapshot", {
  description: "Read ME items and Forge item slots for a list of machines or containers in one snapshot. Returns source tokens. Supply an AE2 node as drive when querying ME items.",
  inputSchema: {
    drive: sidedLocationObject.optional(),
    items: z.array(resourceId).min(1).max(128).optional(),
    machines: z.array(batchMachine).min(1).max(32).optional(),
  },
});

registerTool("transfer_me_machine_batch", {
  description: "Run ordered ME item transfers to or from Forge item slots at specified positions and sides. Use fresh source tokens from get_me_machine_snapshot. Returns actual moved amounts and per-step errors.",
  inputSchema: {
    drive: sidedLocationObject,
    expectedWorldFolder: z.string().min(1),
    requestId: batchRequestId,
    transfers: z.array(z.object({
      machine: batchMachine,
      direction: z.enum(["to_machine", "from_machine"]),
      slot: slotIndex,
      item: resourceId,
      amount: positiveAmount,
      expectedSourceToken: stateToken,
    })).min(1).max(32),
  },
  annotations: { destructiveHint: true, idempotentHint: true },
});

registerReadTool("get_me_fluid", {
  description: "Read a fluid's amount in an active AE2 network, located by an AE2 node, in mB.",
  inputSchema: { ...sidedLocation, fluid: resourceId },
});

registerTool("transfer_me_fluid", {
  description: "Move fluid between a Forge fluid handler and powered ME. Use the source variant token from get_tank on the same side, or get_me_fluid. AE2 may apply a deposit to a waiting order. moved reports the actual amount accepted in mB.",
  inputSchema: {
    tank: sidedLocationObject,
    drive: sidedLocationObject,
    direction: meDirection,
    fluid: resourceId,
    amount: positiveAmount,
    expectedSourceToken: stateToken,
  },
  annotations: { destructiveHint: true },
});

registerTool("fill_me_fluid_bucket", {
  description: "Fill one empty vanilla bucket from 1000 mB of an exact fluid variant in a powered ME network and store the result in ME. Read fresh tokens with get_me_item for minecraft:bucket and get_me_fluid.",
  inputSchema: {
    drive: sidedLocationObject,
    fluid: resourceId,
    expectedFluidToken: stateToken,
    expectedBucketToken: stateToken,
    expectedWorldFolder: z.string().min(1),
  },
  annotations: { destructiveHint: true },
});

registerReadTool("get_host_inventory", {
  description: "Read the singleplayer host's main inventory slots and source tokens for item transfers, including FTB rewards.",
});

registerTool("transfer_host_me_item", {
  description: "Move a matching stack between the host's specified main inventory slot and ME. Pass a fresh inventory token for to_me or ME variant token for from_me.",
  inputSchema: {
    drive: sidedLocationObject, expectedWorldFolder: z.string().min(1),
    slot: slotIndex.max(35), item: resourceId, amount: positiveAmount, direction: meDirection,
    expectedSourceToken: stateToken,
  },
  annotations: { destructiveHint: false },
});

// AE2 与工作台合成
const workbenchGrid = z.array(z.object({ item: resourceId, expectedSourceToken: stateToken }).nullable()).length(9);
const patternProviderLocation = {
  ...sidedLocation,
  provider: z.object({ position: blockLocation.position, side: blockSide.optional() }),
  slot: slotIndex,
};
const processingItem = z.object({
  item: resourceId,
  amount: positiveAmount,
  meToken: stateToken.optional(),
});
const orderId = z.string().uuid();

registerReadTool("find_workbench_recipes", {
  description: "Find actual workbench recipes registered in the loaded pack by their output item ID.",
  inputSchema: { item: resourceId },
});

registerReadTool("get_workbench_recipe", {
  description: "Read a registered workbench recipe, its output, and accepted ingredient examples.",
  inputSchema: { recipe: resourceId },
});

registerTool("craft_from_me", {
  description: "Perform one registered workbench craft with items from a powered AE2 network. Use nine row-major slots, each null or an item and its ME variant token. Result and remainders return to ME.",
  inputSchema: {
    ...sidedLocation,
    recipe: resourceId,
    grid: workbenchGrid,
  },
  annotations: { destructiveHint: true },
});

registerTool("craft_batch_from_me", {
  description: "Repeat one registered workbench recipe from ME storage without AE2 crafting automation. Use fresh variant tokens and nine row-major slots. Returns the completed crafts and stops at the first blocked craft.",
  inputSchema: {
    ...sidedLocation,
    expectedWorldFolder: z.string().min(1),
    requestId: batchRequestId,
    recipe: resourceId,
    repetitions: z.number().int().min(1).max(64),
    grid: workbenchGrid,
  },
  annotations: { destructiveHint: true, idempotentHint: true },
});

registerTool("encode_me_crafting_pattern", {
  description: "Consume one blank pattern from ME and encode a workbench recipe into an empty slot of a same-network AE2 pattern container. Give nine row-major ingredient slots. Supply an ME token for ingredients with variant data.",
  inputSchema: {
    ...patternProviderLocation,
    recipe: resourceId,
    grid: z.array(z.object({ item: resourceId, meToken: stateToken.optional() }).nullable()).length(9),
  },
  annotations: { destructiveHint: true, idempotentHint: true },
});

registerTool("encode_me_processing_pattern", {
  description: "Consume one blank pattern from ME and install an AE2 processing pattern in a same-network AE2 pattern container. Supply item inputs and expected outputs for a connected machine.",
  inputSchema: {
    ...patternProviderLocation,
    inputs: z.array(processingItem).min(1).max(9),
    outputs: z.array(processingItem).min(1).max(3),
  },
  annotations: { destructiveHint: true, idempotentHint: true },
});

registerReadTool("get_me_craftable", {
  description: "Find active AE2 crafting patterns for an item and return craftable variants and stored counts.",
  inputSchema: { ...sidedLocation, item: resourceId },
});

registerTool("order_me_craft", {
  description: "Queue an AE2 native crafting order. AE2 dispatches ingredients and receives products through the configured machine return path. After submission, poll get_me_craft_order for progress. Use a new UUID for each order and reuse it on retries within this world session.",
  inputSchema: {
    ...sidedLocation,
    item: resourceId,
    amount: positiveAmount,
    orderId,
    variant: stateToken.optional(),
  },
  annotations: { destructiveHint: true, idempotentHint: true },
});

registerReadTool("get_me_craft_order", {
  description: "Read the current-session AE2 order state, ingredient shortages, native job ID, and observed ME stock.",
  inputSchema: { orderId },
});

registerTool("cancel_me_craft_order", {
  description: "Cancel a pending or running AE2 crafting order owned by this bridge session.",
  inputSchema: { orderId },
  annotations: { destructiveHint: true, idempotentHint: true },
});

// GTCEu 机器与多方块
const multiblockParts = z.array(z.object({ offset: blockLocation.position, block: resourceId })).max(128).optional();
const multiblockPreviewIndex = z.number().int().min(0).max(2147483647).optional();

registerReadTool("find_gt_recipes", {
  description: "Find recipes in the active pack by GT item or fluid output, optionally restricted to a GT recipe type.",
  inputSchema: { item: resourceId.optional(), fluid: resourceId.optional(), recipeType: resourceId.optional() },
});

registerTool("configure_gt_machine", {
  description: "Set a GT single-block machine's working mode and optionally its ghost circuit. Use -1 to clear the circuit.",
  inputSchema: {
    ...blockLocation,
    workingEnabled: z.boolean().optional(),
    circuit: z.number().int().min(-1).optional(),
  },
  annotations: { destructiveHint: true },
});

registerTool("install_gt_cover_from_me", {
  description: "Install one GT cover item from ME on the chosen side of a block exposing GT cover access. GT checks compatibility and the side must be empty.",
  inputSchema: {
    ...blockLocation, side: blockSide, item: resourceId,
    drive: sidedLocationObject, expectedWorldFolder: z.string().min(1), expectedSourceToken: stateToken,
  },
  annotations: { destructiveHint: false },
});

registerTool("service_gt_maintenance", {
  description: "Use one real duct tape from ME to repair a GT maintenance hatch through GT's native repair API.",
  inputSchema: {
    hatch: blockLocationObject,
    drive: sidedLocationObject,
    expectedSourceToken: stateToken,
  },
  annotations: { destructiveHint: true },
});

registerReadTool("get_gt_multiblock_layout", {
  description: "Read a native GTCEu multiblock preview and count its materials. Supply exactly one registered machine or controller block ID (structure), or a placed controller location. Offsets are relative to the controller in the preview orientation. Select previewIndex if several previews are registered. Optional parts are explicit planning substitutions.",
  inputSchema: {
    structure: resourceId.optional(),
    controller: blockLocationObject.optional(),
    previewIndex: multiblockPreviewIndex,
    parts: multiblockParts,
  },
});

registerTool("build_gt_multiblock_from_me", {
  description: "Build a registered GT multiblock from ME at a loaded empty site using the same previewIndex and parts as get_gt_multiblock_layout. GT validates the resulting pattern.",
  inputSchema: {
    structure: resourceId, controller: blockLocationObject, drive: sidedLocationObject,
    previewIndex: multiblockPreviewIndex, parts: multiblockParts,
  },
  annotations: { destructiveHint: true },
});

registerReadTool("check_multiblock_blocks", {
  description: "Compare a blueprint's absolute positions with registered block IDs in the chosen dimension. Returns counts and only missing, incorrect or unavailable positions. Does not load chunks.",
  inputSchema: {
    dimension: resourceId,
    blocks: z.array(z.object({ position: blockLocation.position, block: resourceId })).min(1).max(1024),
  },
});

// FTB 任务
const ftbId = z.string().regex(/^[0-9a-fA-F]{1,16}$/);
const ftbPage = {
  offset: z.number().int().min(0).max(2147483647).optional(),
  limit: z.number().int().min(1).max(50).optional(),
};

registerReadTool("list_ftb_quest_chapters", {
  description: "List FTB Quests chapters and the current player's team progress. Paginated with a default limit of 20.",
  inputSchema: ftbPage,
});

registerReadTool("list_ftb_quests", {
  description: "List FTB quests and team progress. Optionally filter by chapter, title substring, or status. Paginated with a default limit of 20.",
  inputSchema: {
    ...ftbPage,
    chapterId: ftbId.optional(),
    query: z.string().trim().min(1).max(120).optional(),
    status: z.enum(["available", "in_progress", "completed", "locked"]).optional(),
  },
});

registerReadTool("get_ftb_quest", {
  description: "Read one FTB quest's description, dependencies, task objectives and team progress, and the current player's reward claim status.",
  inputSchema: { questId: ftbId },
});

registerTool("check_ftb_task", {
  description: "Complete a checkmark task in the specified save using FTB's normal task rules. Get taskId from get_ftb_quest. Returns updated quest details.",
  inputSchema: { expectedWorldFolder: z.string().min(1), taskId: ftbId },
  annotations: { destructiveHint: false, idempotentHint: true },
});

registerTool("submit_ftb_item_from_me", {
  description: "Submit an exact ME item stack to an unlocked resource-consuming FTB task using FTB's native item insertion.",
  inputSchema: {
    drive: sidedLocationObject,
    taskId: ftbId,
    item: resourceId,
    amount: z.number().int().min(1).max(64),
    expectedSourceToken: stateToken,
    expectedWorldFolder: z.string().min(1),
  },
  annotations: { destructiveHint: true },
});

registerTool("claim_ftb_reward", {
  description: "Claim one available FTB reward for the singleplayer host in the specified save. Get rewardId and any choiceIndex from get_ftb_quest. Returns updated quest details.",
  inputSchema: { expectedWorldFolder: z.string().min(1), rewardId: ftbId, choiceIndex: slotIndex.max(2147483647).optional() },
  annotations: { destructiveHint: false, idempotentHint: true },
});

// 树场与采收

registerTool("start_tree_farm", {
  description: "Start a repeating oak or birch farm. Supply and output use separate Forge item handlers in the planting dimension. Specify supplySide and outputSide for sided access.",
  inputSchema: {
    ...blockLocation,
    tree: z.enum(["oak", "birch"]),
    supply: blockLocation.position,
    output: blockLocation.position,
    supplySide: blockSide.optional(),
    outputSide: blockSide.optional(),
  },
  annotations: { destructiveHint: true, idempotentHint: true },
});

registerReadTool("get_tree_farm_status", {
  description: "Read the active tree farm, its progress, and any reason it cannot plant or harvest.",
});

registerTool("stop_tree_farm", {
  description: "Stop automatic tree harvesting and planting, leaving the farm's blocks and chests in place.",
  annotations: { destructiveHint: false, idempotentHint: true },
});

registerTool("start_me_spruce_farm", {
  description: "Start a repeating 2x2 giant spruce farm at the northwest sapling position. A new plot uses four dirt and four spruce saplings from ME above a solid floor. Logs and canopy drops return to ME.",
  inputSchema: { ...blockLocation, drive: sidedLocationObject, expectedWorldFolder: z.string().min(1) },
  annotations: { destructiveHint: true, idempotentHint: true },
});

registerReadTool("get_me_spruce_farm_status", {
  description: "Read the running ME spruce farm, stored drops, recovered loose drops, and any reason it is blocked.",
});

registerTool("stop_me_spruce_farm", {
  description: "Stop the repeating ME spruce farm and leave the soil and tree in place.",
  annotations: { destructiveHint: false, idempotentHint: true },
});

registerReadTool("find_spruce_and_me", {
  description: "Find giant spruce trunk bases and active AE2 network endpoints near a position in loaded chunks.",
  inputSchema: { ...blockLocation, radius: z.number().int().min(1).max(48).optional() },
});

registerReadTool("inspect_giant_spruce", {
  description: "Check the northwest corner of a giant spruce and the ME network for a harvest and 2x2 replant.",
  inputSchema: { ...blockLocation, drive: sidedLocationObject },
});

registerTool("harvest_giant_spruce_to_me", {
  description: "Harvest an inspected giant spruce into ME and replant four saplings at its 2x2 base.",
  inputSchema: { ...blockLocation, drive: sidedLocationObject, expectedPlan: stateToken },
  annotations: { destructiveHint: true },
});

registerTool("harvest_rubber_trunk_to_me", {
  description: "Harvest the natural rubber tree trunk above a soil block and store its actual loot in ME. Inspect the base before use.",
  inputSchema: { ...blockLocation, drive: sidedLocationObject, expectedWorldFolder: z.string().min(1) },
  annotations: { destructiveHint: true },
});

// 测试夹具
const testStates = z.enum([
  "minecraft:air",
  "minecraft:oak_log[axis=x]",
  "minecraft:oak_log[axis=y]",
  "minecraft:oak_log[axis=z]",
]);

registerTool("set_test_block", {
  description: "Change the configured test coordinate between air and an oriented oak log when the current state matches expected. Requires allowTestBlockWrites.",
  inputSchema: { ...blockLocation, expected: testStates, block: testStates },
  annotations: { destructiveHint: true },
});

registerTool("verify_tree_farm", {
  description: "Run one harvest and replant check using temporary blocks, then restore the site. Requires allowTestFixtures and a configured test target.",
  annotations: { destructiveHint: true },
});

await server.connect(new StdioServerTransport());
