package minecraft.automation.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObject;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.reward.ItemReward;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.task.FluidTask;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.util.TextUtils;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import minecraft.automation.mcp.MinecraftAutomationBridge.BridgeError;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

final class FtbQuestOperations {
    private FtbQuestOperations() {}

    static JsonObject read(MinecraftServer server, String operation, JsonObject request) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null || file.isLoading() || !FTBTeamsAPI.api().isManagerLoaded()) {
            throw new BridgeError(409, "ftb_quests_not_ready", "The FTB quest book or teams are still loading");
        }
        ServerPlayer player = server.getPlayerList().getPlayerByName(server.getSingleplayerProfile().getName());
        if (player == null) {
            throw new BridgeError(409, "ftb_player_unavailable", "The singleplayer host has not joined the world yet");
        }
        Team team = FTBTeamsAPI.api().getManager().getTeamForPlayer(player).orElseThrow(() ->
                new BridgeError(409, "ftb_team_unavailable", "The singleplayer host's FTB team is not available yet"));
        TeamData data = file.getNullableTeamData(team.getId());
        if (data == null) {
            // Keep new-team reads out of the quest file's persistent team map.
            data = new TeamData(team.getId(), file);
        }
        QuestView view = new QuestView(data, player.getUUID(), loadDefaultTranslations(file.getFolder()));
        return switch (operation) {
            case "list_ftb_quest_chapters" -> page(file.getAllChapters(), request, "chapters", view::chapter);
            case "list_ftb_quests" -> listQuests(file, view, request);
            case "get_ftb_quest" -> {
                Quest quest = file.getQuest(id(request, "questId"));
                if (quest == null) {
                    throw new BridgeError(404, "ftb_quest_not_found", "No FTB quest matches questId");
                }
                yield view.detail(quest);
            }
            default -> throw new IllegalArgumentException("Unknown FTB quest operation");
        };
    }

    private static JsonObject listQuests(ServerQuestFile file, QuestView view, JsonObject request) {
        List<Chapter> chapters = file.getAllChapters();
        if (request.has("chapterId")) {
            Chapter chapter = file.getChapter(id(request, "chapterId"));
            if (chapter == null) {
                throw new BridgeError(404, "ftb_chapter_not_found", "No FTB chapter matches chapterId");
            }
            chapters = List.of(chapter);
        }
        String query = request.has("query") ? MinecraftAutomationBridge.stringField(request, "query").strip() : "";
        String needle = query.toLowerCase(Locale.ROOT);
        String status = request.has("status") ? MinecraftAutomationBridge.stringField(request, "status") : "";
        if (query.length() > 120 || !List.of("", "available", "in_progress", "completed", "locked").contains(status)) {
            throw new IllegalArgumentException("Invalid FTB quest filter");
        }
        List<Quest> quests = chapters.stream().flatMap(chapter -> chapter.getQuests().stream())
                .filter(quest -> needle.isEmpty() || view.questText(quest.getTitle()).toLowerCase(Locale.ROOT).contains(needle))
                .filter(quest -> status.isEmpty() || status.equals(view.status(quest)))
                .toList();
        return page(quests, request, "quests", view::summary);
    }

    private static long id(JsonObject request, String key) {
        String value = MinecraftAutomationBridge.stringField(request, key);
        if (!value.matches("[0-9A-Fa-f]{1,16}")) {
            throw new IllegalArgumentException("Invalid FTB quest book ID: " + key);
        }
        return Long.parseUnsignedLong(value, 16);
    }

    private static JsonObject loadDefaultTranslations(Path questFolder) {
        Path lang = questFolder.resolve("lang");
        try {
            JsonObject manifest = readJson(lang.resolve("gtlcore-i18n.json"));
            String locale = jsonString(manifest, "default_language", "");
            if (locale.matches("[a-z]{2,3}_[a-z0-9]{2,8}")) {
                return readJson(lang.resolve(locale + ".json"));
            }
        } catch (IOException | JsonParseException ignored) {
            // The translation catalog is optional; FTB still provides the quest fields.
        }
        return new JsonObject();
    }

    private static JsonObject readJson(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > 8L * 1024 * 1024) {
            return new JsonObject();
        }
        try (var reader = Files.newBufferedReader(path)) {
            JsonElement json = JsonParser.parseReader(reader);
            return json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
        }
    }

    private static String jsonString(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : fallback;
    }

    private static <T> JsonObject page(List<T> entries, JsonObject request, String key, Function<T, JsonObject> describe) {
        int offset = request.has("offset") ? MinecraftAutomationBridge.coordinate(request, "offset") : 0;
        int limit = request.has("limit") ? MinecraftAutomationBridge.coordinate(request, "limit") : 20;
        if (offset < 0 || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("Invalid FTB quest book page");
        }
        int start = Math.min(offset, entries.size());
        int end = start + Math.min(limit, entries.size() - start);
        JsonArray items = new JsonArray();
        entries.subList(start, end).forEach(entry -> items.add(describe.apply(entry)));
        JsonObject result = new JsonObject();
        result.add(key, items);
        result.addProperty("total", entries.size());
        if (end < entries.size()) {
            result.addProperty("nextOffset", end);
        }
        return result;
    }

    private record QuestView(TeamData data, UUID playerId, JsonObject defaultTranslations) {
        private String questText(Component component) {
            String text = component.getString();
            if (!text.matches("gtlcore\\.quest\\.[a-zA-Z0-9_.-]+")) {
                return text;
            }
            String translated = Component.translatable(text).getString();
            return translated.equals(text) ? TextUtils.parseRawText(jsonString(defaultTranslations, text, text)).getString()
                    : translated;
        }

        private JsonObject chapter(Chapter chapter) {
            JsonObject result = describeIdentity(chapter);
            result.addProperty("visible", chapter.isVisible(data));
            result.addProperty("questCount", chapter.getQuests().size());
            result.addProperty("progressPercent", data.getRelativeProgress(chapter));
            return result;
        }

        private String status(Quest quest) {
            if (data.isCompleted(quest)) {
                return "completed";
            }
            if (!data.canStartTasks(quest)) {
                return "locked";
            }
            return data.isStarted(quest) ? "in_progress" : "available";
        }

        private JsonObject summary(Quest quest) {
            JsonObject result = describeIdentity(quest);
            result.addProperty("chapterId", quest.getChapter().getCodeString());
            result.addProperty("visible", quest.getChapter().isVisible(data) && quest.isVisible(data));
            result.addProperty("status", status(quest));
            result.addProperty("progressPercent", data.getRelativeProgress(quest));
            return result;
        }

        private JsonObject detail(Quest quest) {
            JsonObject result = summary(quest);
            result.addProperty("subtitle", questText(quest.getSubtitle()));
            result.addProperty("optional", quest.isOptional());
            JsonArray description = new JsonArray();
            quest.getDescription().stream().map(this::questText).forEach(description::add);
            result.add("description", description);

            JsonArray dependencies = new JsonArray();
            quest.streamDependencies().map(this::dependency).forEach(dependencies::add);
            result.add("dependencies", dependencies);
            result.addProperty("minRequiredDependencies", quest.getMinRequiredDependencies());

            JsonArray tasks = new JsonArray();
            quest.getTasks().forEach(task -> tasks.add(task(task)));
            result.add("tasks", tasks);
            JsonArray rewards = new JsonArray();
            quest.getRewards().forEach(reward -> rewards.add(reward(reward)));
            result.add("rewards", rewards);
            return result;
        }

        private JsonObject dependency(QuestObject dependency) {
            JsonObject result = describeIdentity(dependency);
            result.addProperty("completed", data.isCompleted(dependency));
            return result;
        }

        private JsonObject task(Task task) {
            JsonObject result = describeIdentity(task);
            result.addProperty("type", task.getType().getTypeId().toString());
            result.addProperty("progress", Long.toString(data.getProgress(task)));
            result.addProperty("required", Long.toString(task.getMaxProgress()));
            result.addProperty("completed", data.isCompleted(task));
            if (task instanceof ItemTask item) {
                addItem(result, item.getItemStack());
            } else if (task instanceof FluidTask fluid) {
                result.addProperty("fluid", ForgeRegistries.FLUIDS.getKey(fluid.getFluid()).toString());
                result.addProperty("unit", "mB");
            }
            return result;
        }

        private JsonObject reward(Reward reward) {
            JsonObject result = describeIdentity(reward);
            result.addProperty("type", reward.getType().getTypeId().toString());
            result.addProperty("claimStatus", data.getClaimType(playerId, reward).name().toLowerCase(Locale.ROOT));
            if (reward instanceof ItemReward item) {
                addItem(result, item.getItem());
                result.addProperty("count", item.getCount());
            }
            return result;
        }

        private JsonObject describeIdentity(QuestObjectBase object) {
            JsonObject result = new JsonObject();
            result.addProperty("id", object.getCodeString());
            result.addProperty("title", questText(object.getTitle()));
            return result;
        }

        private static void addItem(JsonObject result, ItemStack item) {
            if (!item.isEmpty()) {
                result.addProperty("item", ForgeRegistries.ITEMS.getKey(item.getItem()).toString());
            }
        }
    }
}
