package baocup.builder.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import baocup.builder.util.Bounds;

/**
 * 从「完整方块图 + 累计 zone」确定性抽取结构化事实层。
 * 每阶段结束调用一次，复杂度 O(方块数 × zone 数)，不随对话长度增长，也不消耗 token。
 */
public final class FactExtractor {
    private static final int LEVEL_HEIGHT = 5;

    private FactExtractor() {
    }

    public static SceneFact extract(int version, Bounds bounds,
                                    List<Placement> placements, BuildPlan cumulative,
                                    List<Placement> entities) {
        SceneFact fact = new SceneFact();
        fact.version = version;
        fact.generatedAt = System.currentTimeMillis();
        fact.bounds = bounds == null ? new Bounds(0, 0, 0, 0, 0, 0) : bounds.copy();
        fact.ensureCollections();

        // 1) 材质用量与楼层（一次遍历）
        Map<String, Integer> materialCounts = new LinkedHashMap<>();
        Map<Integer, Map<String, Integer>> levelMaterials = new HashMap<>();
        Map<Integer, int[]> levelRanges = new HashMap<>();
        Map<Integer, Integer> levelBlocks = new HashMap<>();
        int baseY = fact.bounds.minY();
        Map<Long, Placement> byKey = new HashMap<>();
        for (Placement placement : placements) {
            placement.normalize();
            if ("minecraft:air".equals(placement.block)) {
                continue;
            }
            byKey.put(placement.key(), placement);
            materialCounts.merge(placement.block, 1, Integer::sum);
            int level = Math.max(0, (placement.y - baseY) / LEVEL_HEIGHT);
            levelMaterials.computeIfAbsent(level, k -> new LinkedHashMap<>())
                    .merge(placement.block, 1, Integer::sum);
            levelBlocks.merge(level, 1, Integer::sum);
            int[] range = levelRanges.computeIfAbsent(level,
                    k -> new int[] { placement.y, placement.y });
            range[0] = Math.min(range[0], placement.y);
            range[1] = Math.max(range[1], placement.y);
        }
        fact.materials.putAll(materialCounts);
        List<Integer> sortedLevels = new ArrayList<>(levelBlocks.keySet());
        sortedLevels.sort(Integer::compareTo);
        for (int index = 0; index < sortedLevels.size(); index++) {
            int level = sortedLevels.get(index);
            SceneFact.Level node = new SceneFact.Level();
            node.index = index;
            node.blocks = levelBlocks.getOrDefault(level, 0);
            int[] range = levelRanges.get(level);
            node.yMin = range == null ? baseY : range[0];
            node.yMax = range == null ? baseY : range[1];
            node.dominantMaterial = dominant(levelMaterials.get(level));
            fact.levels.add(node);
        }

        // 2) 区域：以地板 zone 为房间，统计地板与边界墙体
        List<BuildPlan.Zone> floors = new ArrayList<>();
        for (BuildPlan.Zone zone : cumulative.zones) {
            if ("floor".equals(zone.shape) || "ceiling".equals(zone.shape)) {
                floors.add(zone);
            }
        }
        int roomIndex = 0;
        for (BuildPlan.Zone floor : floors) {
            SceneFact.Room room = new SceneFact.Room();
            room.id = "room" + (++roomIndex);
            room.box = floor.box.clone();
            for (Placement placement : placements) {
                if ("minecraft:air".equals(placement.block)
                        || !inside(placement.x, placement.y, placement.z, floor.box)) {
                    continue;
                }
                if (placement.y == floor.box[1]) {
                    room.floorCount++;
                } else if (onBoundary(placement.x, placement.z, floor.box)) {
                    room.wallCount++;
                }
            }
            fact.rooms.add(room);
        }

        // 3) 构件：每个累计 zone 一个节点，带来源 id、状态、朝向与实际格数
        int featureIndex = 0;
        for (BuildPlan.Zone zone : cumulative.zones) {
            SceneFact.Feature feature = new SceneFact.Feature();
            feature.id = "f" + (++featureIndex);
            feature.kind = kindOf(zone);
            feature.name = zone.name.isEmpty() ? zone.id : zone.name;
            feature.material = zone.material == null ? "" : zone.material;
            feature.sourceZone = zone.id;
            feature.box = zone.box.clone();
            Placement anchor = byKey.get(pack(zone.box[0], zone.box[1], zone.box[2]));
            feature.state = anchor == null ? "" : anchor.state;
            String hint = zone.facingHint();
            feature.facing = OrientationResolver.horizontalFacing(hint,
                    (zone.box[0] + zone.box[3]) / 2, (zone.box[2] + zone.box[5]) / 2, fact.bounds);
            int count = 0;
            for (Placement placement : placements) {
                if (!"minecraft:air".equals(placement.block)
                        && inside(placement.x, placement.y, placement.z, zone.box)) {
                    count++;
                }
            }
            feature.blocks = count;
            fact.features.add(feature);
        }

        // 4) 实体作为独立构件节点
        if (entities != null) {
            for (Placement entity : entities) {
                SceneFact.Feature feature = new SceneFact.Feature();
                feature.id = "f" + (++featureIndex);
                feature.kind = "实体";
                feature.name = "实体 " + NbtEntityOps.entityId(entity).substring(0, 8);
                feature.material = entity.entity;
                feature.box = new int[] { entity.x, entity.y, entity.z,
                        entity.x, entity.y, entity.z };
                feature.blocks = 1;
                fact.features.add(feature);
            }
        }

        fact.buildSummary();
        fact.rebuildIndex();
        return fact;
    }

    private static String kindOf(BuildPlan.Zone zone) {
        String material = zone.material == null ? "" : zone.material;
        if (material.startsWith("entity:")) {
            return "实体";
        }
        int bracket = material.indexOf('[');
        String base = (bracket >= 0 ? material.substring(0, bracket) : material).trim();
        String category = BlockComponents.categoryOf(base);
        if (!"方块".equals(category)) {
            return category;
        }
        return switch (zone.shape) {
            case "clear" -> "开口";
            case "floor", "ceiling" -> "地板";
            case "walls", "walls_open", "hollow", "frame" -> "墙体";
            case "roof_gable", "pyramid" -> "屋顶";
            case "stairs" -> "楼梯";
            case "pillar" -> "柱";
            default -> "结构";
        };
    }

    private static String dominant(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "";
        }
        String best = "";
        int max = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > max) {
                max = entry.getValue();
                best = entry.getKey();
            }
        }
        return best.replace("minecraft:", "");
    }

    private static boolean inside(int x, int y, int z, int[] box) {
        return x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4]
                && z >= box[2] && z <= box[5];
    }

    private static boolean onBoundary(int x, int z, int[] box) {
        return x == box[0] || x == box[3] || z == box[2] || z == box[5];
    }

    private static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFF) << 38) | (((long) z & 0x3FFFFFF) << 12)
                | ((long) (y + 64) & 0xFFF);
    }
}
