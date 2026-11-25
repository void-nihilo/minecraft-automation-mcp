package minecraft.automation.mcp;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

final class MeSpruceFarmOperations {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHECK_INTERVAL_TICKS = 100;

    private Plot plot;
    private String state = "idle";
    private String problem;
    private String detail;
    private long treesHarvested;
    private long logsStored;
    private long looseDropsRecovered;
    private JsonObject lastDrops;

    JsonObject start(ServerLevel level, BlockPos root, MeOperations.Endpoint endpoint) {
        Plot requested = new Plot(level, root, endpoint);
        if (requested.equals(plot)) {
            return status();
        }
        if (plot != null) {
            throw new BridgeError(409, "spruce_farm_exists", "Stop the current ME spruce farm before starting another");
        }
        endpoint.grid();
        if (level.isEmptyBlock(root)) {
            GiantSpruceOperations.plantFromMe(level, root, endpoint);
        } else if (!GiantSpruceOperations.growing(level, root)) {
            GiantSpruceOperations.run(level, root, endpoint, null);
        }
        plot = requested;
        state = "waiting";
        treesHarvested = 0;
        logsStored = 0;
        looseDropsRecovered = 0;
        lastDrops = null;
        return status();
    }

    void tick(MinecraftServer server) {
        if (plot == null || state.equals("error") || server.getTickCount() % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        problem = null;
        detail = null;
        try {
            looseDropsRecovered += GiantSpruceOperations.recoverLooseDrops(plot.level, plot.root, plot.endpoint);
            if (!GiantSpruceOperations.growing(plot.level, plot.root)) {
                JsonObject plan = GiantSpruceOperations.run(plot.level, plot.root, plot.endpoint, null);
                JsonObject harvest = GiantSpruceOperations.run(plot.level, plot.root, plot.endpoint,
                        plan.get("plan").getAsString());
                treesHarvested++;
                logsStored += harvest.get("logs").getAsInt();
                lastDrops = harvest.getAsJsonObject("dropsStored");
            }
            state = "growing";
        } catch (BridgeError blocked) {
            state = "blocked";
            problem = blocked.code();
            detail = blocked.getMessage();
        } catch (RuntimeException failure) {
            LOGGER.error("Minecraft Automation MCP spruce farm stopped at {}", plot.root, failure);
            state = "error";
            problem = "spruce_farm_error";
            detail = "Inspect the farm and ME before restarting it";
        }
    }

    JsonObject status() {
        JsonObject result = new JsonObject();
        result.addProperty("active", plot != null && !state.equals("error"));
        result.addProperty("state", state);
        if (plot != null) {
            result.addProperty("dimension", plot.level.dimension().location().toString());
            result.add("position", MinecraftAutomationBridge.positionJson(plot.root));
            result.addProperty("driveDimension", plot.endpoint.level().dimension().location().toString());
            result.add("drive", MinecraftAutomationBridge.positionJson(plot.endpoint.position()));
            if (plot.endpoint.side() != null) {
                result.addProperty("driveSide", plot.endpoint.side().getName());
            }
            result.addProperty("treesHarvested", treesHarvested);
            result.addProperty("logsStored", logsStored);
            result.addProperty("looseDropsRecovered", looseDropsRecovered);
            if (lastDrops != null) {
                result.add("lastDropsStored", lastDrops);
            }
        }
        if (problem != null) {
            result.addProperty("code", problem);
            result.addProperty("message", detail);
        }
        return result;
    }

    JsonObject stop() {
        JsonObject result = status();
        if (plot != null) {
            result.addProperty("active", false);
            result.addProperty("state", "stopped");
        }
        plot = null;
        state = "idle";
        problem = null;
        detail = null;
        return result;
    }

    private record Plot(ServerLevel level, BlockPos root, MeOperations.Endpoint endpoint) {}
}
