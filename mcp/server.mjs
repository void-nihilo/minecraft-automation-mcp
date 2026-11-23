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

async function requestInstance(instance, operation, args, timeout = 7000) {
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
const chestSlotIndex = slotIndex.max(26);
const meDirection = z.enum(["to_me", "from_me"]);
const machineDirection = z.enum(["to_machine", "from_machine"]);
const coordinate = z.number().int().min(-2147483648).max(2147483647);
const blockLocation = {
  dimension: resourceId,
  position: z.object({ x: coordinate, y: coordinate, z: coordinate }),
};
const blockLocationObject = z.object(blockLocation);
const testStates = z.enum([
  "minecraft:air",
  "minecraft:oak_log[axis=x]",
  "minecraft:oak_log[axis=y]",
  "minecraft:oak_log[axis=z]",
]);

server.registerTool("list_game_instances", {
  description: "Discover local Minecraft instances running the Forge bridge and read their instance IDs, game directories, and world status.",
  annotations: { readOnlyHint: true, openWorldHint: false },
}, listGameInstances);

registerReadTool("get_game_status", {
  description: "Check the running Forge bridge, current singleplayer world, and active cheat permissions.",
});

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

registerReadTool("get_block", {
  description: "Read a block and its properties at a loaded coordinate in the given dimension.",
  inputSchema: blockLocation,
});

registerTool("start_tree_farm", {
  description: "Start one repeating oak or birch farm at a dedicated planting position. Use separate single chests for saplings and logs, both in the planting dimension.",
  inputSchema: {
    ...blockLocation,
    tree: z.enum(["oak", "birch"]),
    supply: blockLocation.position,
    output: blockLocation.position,
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

registerTool("verify_tree_farm", {
  description: "Run one harvest and replant check using temporary blocks, then restore the site. Requires allowTestFixtures and a configured test target.",
  annotations: { destructiveHint: true },
});

registerReadTool("find_spruce_and_me", {
  description: "Find giant spruce trunk bases and ME Drives near a position in loaded chunks.",
  inputSchema: { ...blockLocation, radius: z.number().int().min(1).max(48).optional() },
});

registerReadTool("inspect_giant_spruce", {
  description: "Check the northwest corner of a giant spruce and the ME Drive for a harvest and 2x2 replant.",
  inputSchema: { ...blockLocation, drive: blockLocationObject },
});

registerTool("harvest_giant_spruce_to_me", {
  description: "Harvest an inspected giant spruce into ME and replant four saplings at its 2x2 base.",
  inputSchema: { ...blockLocation, drive: blockLocationObject, expectedPlan: stateToken },
  annotations: { destructiveHint: true },
});

registerTool("set_test_block", {
  description: "Change the configured test coordinate between air and an oriented oak log when the current state matches expected. Requires allowTestBlockWrites.",
  inputSchema: { ...blockLocation, expected: testStates, block: testStates },
  annotations: { destructiveHint: true },
});

registerReadTool("get_container", {
  description: "Read a single vanilla chest. Each occupied slot includes a stack token for a checked transfer.",
  inputSchema: blockLocation,
});

registerTool("transfer_item", {
  description: "Move the exact amount from one slot of a single vanilla chest into another. If there is not enough room, move nothing.",
  inputSchema: {
    source: blockLocationObject,
    target: blockLocationObject,
    sourceSlot: chestSlotIndex,
    amount: positiveAmount,
    expectedStackToken: stateToken,
  },
  annotations: { destructiveHint: true },
});

registerReadTool("get_tank", {
  description: "Read a GT bronze drum, including fluid, amount and capacity in mB. Use its fluid token for a checked transfer.",
  inputSchema: blockLocation,
});

registerTool("transfer_fluid", {
  description: "Move the exact amount in mB between two GT bronze drums. If the target cannot hold it, move nothing.",
  inputSchema: {
    source: blockLocationObject,
    target: blockLocationObject,
    amount: positiveAmount,
    expectedFluidToken: stateToken,
  },
  annotations: { destructiveHint: true },
});

registerReadTool("get_me_item", {
  description: "Read the count and variant tokens for one item in an active AE2 network, using an ME Drive as the location.",
  inputSchema: { ...blockLocation, item: resourceId },
});

registerTool("transfer_me_item", {
  description: "Manually move items between a single vanilla chest and a powered ME network. For to_me, pass the source slot and chest stack token. For from_me, pass a fresh ME variant token. AE2 may apply a deposit to a waiting order. moved reports the actual amount accepted.",
  inputSchema: {
    chest: blockLocationObject,
    drive: blockLocationObject,
    direction: meDirection,
    item: resourceId,
    amount: positiveAmount,
    sourceSlot: chestSlotIndex.optional(),
    expectedSourceToken: stateToken,
  },
  annotations: { destructiveHint: true },
});

registerReadTool("get_me_fluid", {
  description: "Read a fluid's amount in an active AE2 network from an ME Drive, in mB.",
  inputSchema: { ...blockLocation, fluid: resourceId },
});

registerTool("transfer_me_fluid", {
  description: "Manually move fluid between a GT bronze drum and powered ME. Use the source tank or ME fluid variant token. AE2 may apply a deposit to a waiting order. moved reports the actual amount accepted in mB.",
  inputSchema: {
    tank: blockLocationObject,
    drive: blockLocationObject,
    direction: meDirection,
    fluid: resourceId,
    amount: positiveAmount,
    expectedSourceToken: stateToken,
  },
  annotations: { destructiveHint: true },
});

registerReadTool("get_gt_machine", {
  description: "Read a GT machine's item and fluid slots, recipe progress, energy and voltage, or check multiblock formation.",
  inputSchema: blockLocation,
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

const gtItemTransfer = {
  item: resourceId,
  amount: z.number().int().positive().max(64),
  expectedSourceToken: stateToken,
};

registerTool("transfer_gt_me_item", {
  description: "Manually move items between a powered AE2 network and one GT item slot for standalone operation or diagnostics. Use the source token. AE2 may apply a deposit to a waiting order. moved reports the actual amount accepted. Use the configured native return path for automatic orders.",
  inputSchema: {
    ...gtItemTransfer,
    machine: blockLocationObject,
    drive: blockLocationObject,
    direction: machineDirection,
    machineSlot: slotIndex,
  },
  annotations: { destructiveHint: true },
});

registerTool("transfer_gt_machine_item", {
  description: "Move an exact stack from a GT machine output slot to the input slot of another GT machine.",
  inputSchema: {
    ...gtItemTransfer,
    source: blockLocationObject,
    target: blockLocationObject,
    sourceSlot: slotIndex,
    targetSlot: slotIndex,
  },
  annotations: { destructiveHint: true },
});

registerReadTool("get_gt_coke_oven_layout", {
  description: "Read the GT Coke Oven structure, relative to its controller, and count the required ME materials.",
});

registerTool("build_gt_coke_oven_from_me", {
  description: "Consume a GT Coke Oven's actual components from ME to build it at an empty loaded controller position, then validate the GT pattern.",
  inputSchema: { controller: blockLocationObject, drive: blockLocationObject },
  annotations: { destructiveHint: true },
});

registerTool("transfer_gt_me_fluid", {
  description: "Manually move fluid between a powered AE2 network and a supported GT machine fluid slot for standalone operation or diagnostics. AE2 may apply a deposit to a waiting order. moved reports the actual amount accepted in mB.",
  inputSchema: {
    machine: blockLocationObject,
    drive: blockLocationObject,
    direction: machineDirection,
    fluid: resourceId,
    amount: positiveAmount,
    machineTank: slotIndex,
    expectedSourceToken: stateToken,
  },
  annotations: { destructiveHint: true },
});

const gtMultiblock = z.enum([
  "gtceu:coke_oven",
  "gtceu:electric_blast_furnace",
  "gtceu:large_chemical_reactor",
  "gtceu:pyrolyse_oven",
]);

registerReadTool("get_gt_multiblock_layout", {
  description: "Read the validated GT layout and the ME material list for a supported multiblock.",
  inputSchema: { structure: gtMultiblock },
});

registerTool("build_gt_multiblock_from_me", {
  description: "Consume the actual structure materials from ME and build a supported GT multiblock at a loaded empty site.",
  inputSchema: { structure: gtMultiblock, controller: blockLocationObject, drive: blockLocationObject },
  annotations: { destructiveHint: true },
});

registerTool("service_gt_maintenance", {
  description: "Use one real duct tape from ME to repair a GT maintenance hatch through GT's native repair API.",
  inputSchema: {
    hatch: blockLocationObject,
    drive: blockLocationObject,
    expectedSourceToken: stateToken,
  },
  annotations: { destructiveHint: true },
});

registerReadTool("get_workbench_recipe", {
  description: "Read a registered workbench recipe, its output, and accepted ingredient examples.",
  inputSchema: { recipe: resourceId },
});

registerReadTool("find_workbench_recipes", {
  description: "Find actual workbench recipes registered in the loaded pack by their output item ID.",
  inputSchema: { item: resourceId },
});

registerTool("craft_from_me", {
  description: "Perform one registered workbench craft with items from a powered AE2 network. Use nine row-major slots, each null or an item and its ME variant token. Result and remainders return to ME.",
  inputSchema: {
    ...blockLocation,
    recipe: resourceId,
    grid: z.array(z.object({
      item: resourceId,
      expectedSourceToken: stateToken,
    }).nullable()).length(9),
  },
  annotations: { destructiveHint: true },
});

const orderId = z.string().uuid();
const patternProviderLocation = {
  ...blockLocation,
  provider: z.object({ position: blockLocation.position }),
  slot: slotIndex,
};

registerTool("encode_me_crafting_pattern", {
  description: "Consume one blank pattern from ME and encode a workbench recipe into an empty slot of a same-network AE2 pattern provider. Give nine row-major ingredient slots. Supply an ME token for ingredients with variant data.",
  inputSchema: {
    ...patternProviderLocation,
    recipe: resourceId,
    grid: z.array(z.object({ item: resourceId, meToken: stateToken.optional() }).nullable()).length(9),
  },
  annotations: { destructiveHint: true, idempotentHint: true },
});

registerReadTool("get_me_craftable", {
  description: "Find active AE2 crafting patterns for an item and return craftable variants and stored counts.",
  inputSchema: { ...blockLocation, item: resourceId },
});

const processingItem = z.object({
  item: resourceId,
  amount: positiveAmount,
  meToken: stateToken.optional(),
});
registerTool("encode_me_processing_pattern", {
  description: "Consume one blank pattern from ME and install an AE2 processing pattern in a same-network pattern provider or GT ME pattern buffer. Supply item inputs and expected outputs for a connected machine.",
  inputSchema: {
    ...patternProviderLocation,
    inputs: z.array(processingItem).min(1).max(9),
    outputs: z.array(processingItem).min(1).max(3),
  },
  annotations: { destructiveHint: true, idempotentHint: true },
});

registerTool("order_me_craft", {
  description: "Queue an AE2 native crafting order. AE2 dispatches ingredients and receives products through the configured machine return path. After submission, poll get_me_craft_order for progress. Use a new UUID for each order and reuse it on retries within this world session.",
  inputSchema: {
    ...blockLocation,
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

await server.connect(new StdioServerTransport());
