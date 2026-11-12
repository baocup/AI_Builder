package baocup.builder.memory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import baocup.builder.BuilderMod;
import baocup.builder.agent.CrossCheckResult;
import baocup.builder.agent.Placement;
import baocup.builder.agent.SceneFact;
import baocup.builder.agent.SceneSemantic;
import baocup.builder.util.Json;

/** 磁盘备份层：任务 JSON、任务索引、完整方块图、结构化事实层/语义层/互校结果、玩家选区。 */
public final class MemoryStore {
    private final Path root;
    private final Path tasks;
    private final Path blocksDir;
    private final Path factsDir;
    private final Path semanticsDir;
    private final Path crossChecksDir;
    private final Path capturesDir;
    private final Path selectionFile;

    public MemoryStore(Path gameDir) {
        this.root = gameDir.resolve("config/builder/memory");
        this.tasks = root.resolve("tasks");
        this.blocksDir = root.resolve("blocks");
        this.factsDir = root.resolve("facts");
        this.semanticsDir = root.resolve("semantics");
        this.crossChecksDir = root.resolve("crosschecks");
        this.capturesDir = root.resolve("captures");
        this.selectionFile = root.resolve("selections.json");
    }

    public Path root() {
        return root;
    }

    /** 某个任务的照片目录：config/builder/memory/captures/&lt;taskId&gt;/ */
    public Path captureDir(String taskId) {
        return capturesDir.resolve(taskId);
    }

    public void init() {
        try {
            Files.createDirectories(tasks);
            Files.createDirectories(blocksDir);
            Files.createDirectories(factsDir);
            Files.createDirectories(semanticsDir);
            Files.createDirectories(crossChecksDir);
            Files.createDirectories(capturesDir);
        } catch (IOException e) {
            BuilderMod.LOGGER.error("[建造] 无法创建记忆目录 {}", root, e);
        }
    }

    public Map<String, BuildMemory> loadAll() {
        Map<String, BuildMemory> out = new LinkedHashMap<>();
        if (!Files.isDirectory(tasks)) {
            return out;
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(tasks)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
        } catch (IOException e) {
            BuilderMod.LOGGER.error("[建造] 扫描记忆文件失败", e);
            return out;
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path file : files) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                BuildMemory memory = Json.PRETTY.fromJson(text, BuildMemory.class);
                if (memory != null && memory.taskId != null && !memory.taskId.isBlank()) {
                    ensureCollections(memory);
                    // 三层结构化记忆各自独立文件，旧存档缺文件时保持 null（由后续建造补建）
                    memory.fact = loadFact(memory.taskId);
                    memory.semantic = loadSemantic(memory.taskId);
                    memory.crossCheck = loadCrossCheck(memory.taskId);
                    memory.dirty = false;
                    out.put(memory.taskId, memory);
                }
            } catch (Exception e) {
                BuilderMod.LOGGER.warn("[建造] 跳过损坏的记忆文件 {}：{}", file, e.getMessage());
            }
        }
        return out;
    }

    private static void ensureCollections(BuildMemory m) {
        if (m.instructions == null) m.instructions = new ArrayList<>();
        if (m.chat == null) m.chat = new ArrayList<>();
        if (m.logs == null) m.logs = new ArrayList<>();
        if (m.versions == null) m.versions = new ArrayList<>();
        if (m.zones == null) m.zones = new ArrayList<>();
        if (m.materials == null) m.materials = new LinkedHashMap<>();
        if (m.entities == null) m.entities = new ArrayList<>();
        if (m.captures == null) m.captures = new ArrayList<>();
        if (m.visualIssues == null) m.visualIssues = new ArrayList<>();
        if (m.cameraMarkers == null) m.cameraMarkers = new ArrayList<>();
        if (m.visualReport == null) m.visualReport = "";
        for (Placement placement : m.entities) {
            placement.normalize();
        }
        if (m.status == null) m.status = "IDLE";
        if (m.stage == null) m.stage = "待命";
        if (m.userBrief == null) m.userBrief = "";
        if (m.linkMode == null || m.linkMode.isBlank()) m.linkMode = "internal";
        if (m.notes == null) m.notes = "";
        if (m.currentPlanJson == null) m.currentPlanJson = "";
        if (m.planDocument == null) m.planDocument = "";
        if (m.dimension == null) m.dimension = "minecraft:overworld";
    }

    public void save(BuildMemory memory) {
        ensureCollections(memory);
        writeJson(tasks.resolve(memory.taskId + ".json"), Json.PRETTY.toJson(memory));
        saveFact(memory);
        saveSemantic(memory);
        saveCrossCheck(memory);
        writeIndex();
        memory.dirty = false;
    }

    public void saveFact(BuildMemory memory) {
        if (memory.fact != null) {
            memory.fact.ensureCollections();
            writeJson(factsDir.resolve(memory.taskId + ".json"), Json.PRETTY.toJson(memory.fact));
        }
    }

    public SceneFact loadFact(String taskId) {
        SceneFact fact = readPojo(factsDir.resolve(taskId + ".json"), SceneFact.class);
        if (fact != null) {
            fact.ensureCollections();
            fact.rebuildIndex();
        }
        return fact;
    }

    public void saveSemantic(BuildMemory memory) {
        if (memory.semantic != null) {
            memory.semantic.ensureCollections();
            writeJson(semanticsDir.resolve(memory.taskId + ".json"), Json.PRETTY.toJson(memory.semantic));
        }
    }

    public SceneSemantic loadSemantic(String taskId) {
        SceneSemantic semantic = readPojo(semanticsDir.resolve(taskId + ".json"), SceneSemantic.class);
        if (semantic != null) {
            semantic.ensureCollections();
        }
        return semantic;
    }

    public void saveCrossCheck(BuildMemory memory) {
        if (memory.crossCheck != null) {
            memory.crossCheck.ensureCollections();
            writeJson(crossChecksDir.resolve(memory.taskId + ".json"),
                    Json.PRETTY.toJson(memory.crossCheck));
        }
    }

    public CrossCheckResult loadCrossCheck(String taskId) {
        CrossCheckResult result = readPojo(crossChecksDir.resolve(taskId + ".json"), CrossCheckResult.class);
        if (result != null) {
            result.ensureCollections();
        }
        return result;
    }

    private static <T> T readPojo(Path file, Class<T> type) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Json.PRETTY.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            BuilderMod.LOGGER.warn("[建造] 跳过损坏的结构化记忆 {}：{}", file, e.getMessage());
            return null;
        }
    }

    private void writeIndex() {
        JsonObject index = new JsonObject();
        index.addProperty("version", 1);
        index.addProperty("updatedAt", System.currentTimeMillis());
        com.google.gson.JsonArray list = new com.google.gson.JsonArray();
        try (var stream = Files.list(tasks)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(file -> {
                try {
                    BuildMemory m = Json.PRETTY.fromJson(Files.readString(file, StandardCharsets.UTF_8), BuildMemory.class);
                    if (m == null) return;
                    JsonObject o = new JsonObject();
                    o.addProperty("taskId", m.taskId);
                    o.addProperty("owner", m.ownerName);
                    o.addProperty("dimension", m.dimension);
                    o.addProperty("version", m.version);
                    o.addProperty("updatedAt", m.updatedAt);
                    o.addProperty("brief", m.userBrief);
                    o.addProperty("file", file.getFileName().toString());
                    list.add(o);
                } catch (Exception ignored) {
                    // 单个损坏文件不影响索引
                }
            });
        } catch (IOException e) {
            BuilderMod.LOGGER.warn("[建造] 重建记忆索引失败：{}", e.getMessage());
        }
        index.add("tasks", list);
        writeJson(root.resolve("index.json"), Json.PRETTY.toJson(index));
    }

    public void savePlacements(String taskId, int version, List<Placement> placements) {
        Path file = blocksDir.resolve(taskId + "-v" + version + ".json.gz");
        try (Writer writer = new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
            Json.GSON.toJson(placements, writer);
        } catch (IOException e) {
            BuilderMod.LOGGER.warn("[建造] 保存方块图失败：{}", e.getMessage());
        }
    }

    public List<Placement> loadPlacements(String taskId, int version) {
        Path file = blocksDir.resolve(taskId + "-v" + version + ".json.gz");
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            List<Placement> result = Json.GSON.fromJson(reader, new TypeToken<List<Placement>>() {}.getType());
            return result == null ? new ArrayList<>() : result;
        } catch (Exception e) {
            BuilderMod.LOGGER.warn("[建造] 读取方块图失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public String loadSelections() {
        try {
            return Files.isRegularFile(selectionFile) ? Files.readString(selectionFile, StandardCharsets.UTF_8) : "{}";
        } catch (IOException e) {
            return "{}";
        }
    }

    public void saveSelections(String json) {
        writeJson(selectionFile, json);
    }

    private void writeJson(Path target, String json) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            BuilderMod.LOGGER.error("[建造] 写入 {} 失败", target, e);
        }
    }
}
