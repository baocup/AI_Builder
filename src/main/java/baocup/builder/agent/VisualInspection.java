package baocup.builder.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import baocup.builder.BuilderMod;
import baocup.builder.memory.BuildMemory;
import baocup.builder.util.Bounds;

/** 视觉检查编排：把建筑周围若干台独立相机拍成高清 PNG，并生成一份本地统计报告。 */
public final class VisualInspection {
    /** 1024×640 的照片逐像素光线投射较重，用小线程池并发渲染不同机位，报告顺序仍按机位顺序输出。 */
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), runnable -> {
                Thread thread = new Thread(runnable, "builder-visual-raytrace");
                thread.setDaemon(true);
                return thread;
            });

    private VisualInspection() {
    }

    public static final class Batch {
        public final List<BuildMemory.CaptureLine> lines = new ArrayList<>();
        public final List<byte[]> images = new ArrayList<>();
        public String localReport = "";
    }

    public static Batch capture(String taskId, int version, Bounds bounds,
                                List<Placement> placements, List<CameraPose> poses,
                                Path outputDir, int width, int height) {
        return capture(taskId, version, bounds, placements, poses, outputDir, width, height,
                "v" + version);
    }

    /** filePrefix 区分同一版本内不同阶段的中途检查照片，避免同名覆盖。 */
    public static Batch capture(String taskId, int version, Bounds bounds,
                                List<Placement> placements, List<CameraPose> poses,
                                Path outputDir, int width, int height, String filePrefix) {
        Batch batch = new Batch();
        Map<Long, Placement> voxels = new LinkedHashMap<>();
        for (Placement placement : placements) {
            placement.normalize();
            if (!"minecraft:air".equals(placement.block)) {
                voxels.put(placement.key(), placement);
            }
        }
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            BuilderMod.LOGGER.warn("[建造] 无法创建照片目录 {}：{}", outputDir, e.getMessage());
            return batch;
        }

        List<VisualRenderer.Frame> frames = renderAll(poses, voxels, bounds, width, height);
        StringBuilder report = new StringBuilder();
        report.append("视觉检查 v").append(version).append("：").append(poses.size())
                .append(" 台独立虚拟相机，").append(width).append("×").append(height)
                .append(" 高清离屏渲染（含方块边缘细节，非玩家截图）\n");
        report.append("渲染边界：画面仅包含本次建造生成的建筑方块；天空渐变与绿色棋盘格草地为占位背景，"
                + "真实地形 / 植被 / 相邻建筑 / 实体 / 光照均未渲染，不得据此判断周边环境。\n");
        for (int i = 0; i < poses.size(); i++) {
            CameraPose pose = poses.get(i);
            VisualRenderer.Frame frame = i < frames.size() ? frames.get(i) : null;
            if (frame == null) {
                BuilderMod.LOGGER.warn("[建造] 相机 {} 渲染失败", pose.id);
                report.append("· ").append(pose.name).append(" 渲染失败\n");
                continue;
            }
            try {
                String safe = pose.id.replaceAll("[^a-zA-Z0-9_-]", "_");
                Path file = outputDir.resolve(filePrefix + "-" + safe + ".png");
                Files.write(file, frame.png);

                BuildMemory.CaptureLine line = new BuildMemory.CaptureLine();
                line.id = pose.id;
                line.name = pose.name;
                line.path = file.toString();
                line.width = frame.width;
                line.height = frame.height;
                line.blockPixels = frame.blockPixels;
                line.skyPixels = frame.skyPixels;
                line.pose = pose.describe();
                line.ts = System.currentTimeMillis();
                line.materials = topMaterials(frame.materials);
                batch.lines.add(line);
                batch.images.add(frame.png);

                int total = Math.max(1, frame.blockPixels + frame.skyPixels);
                report.append("· ").append(pose.name).append(" [").append(pose.id).append("] ")
                        .append(pose.describe()).append("\n  建筑覆盖 ")
                        .append(frame.blockPixels * 100 / total).append("%，画面命中材质 ")
                        .append(line.materials).append("，文件 ").append(file.getFileName()).append('\n');
            } catch (Exception e) {
                BuilderMod.LOGGER.warn("[建造] 相机 {} 照片写盘失败：{}", pose.id, e.toString());
                report.append("· ").append(pose.name).append(" 写盘失败：").append(e.getMessage()).append('\n');
            }
        }
        batch.localReport = report.toString();
        return batch;
    }

    /**
     * 从一组照片里等间隔挑出最多 max 张，首尾都保留：
     * 这样环绕视角、俯视、人眼高度与 AI 补拍视角不会全被截断掉。
     */
    public static List<byte[]> pickImages(List<byte[]> images, int max) {
        if (images == null || images.isEmpty() || max <= 0) {
            return new ArrayList<>();
        }
        if (images.size() <= max) {
            return new ArrayList<>(images);
        }
        if (max == 1) {
            return new ArrayList<>(List.of(images.get(images.size() - 1)));
        }
        List<byte[]> picked = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            int index = (int) ((long) i * (images.size() - 1) / (max - 1));
            picked.add(images.get(index));
        }
        return picked;
    }

    /** 并发渲染各机位；返回列表与 poses 一一对应，失败的机位为 null。 */
    public static List<VisualRenderer.Frame> renderAll(List<CameraPose> poses,
                                                        Map<Long, Placement> voxels,
                                                        Bounds bounds, int width, int height) {
        if (poses.isEmpty()) {
            return Collections.emptyList();
        }
        List<Callable<VisualRenderer.Frame>> tasks = new ArrayList<>(poses.size());
        for (CameraPose pose : poses) {
            tasks.add(() -> {
                try {
                    return VisualRenderer.render(pose, voxels, bounds, width, height);
                } catch (Exception e) {
                    BuilderMod.LOGGER.warn("[建造] 相机 {} 渲染异常：{}", pose.id, e.toString());
                    return null;
                }
            });
        }
        try {
            List<Future<VisualRenderer.Frame>> futures = WORKERS.invokeAll(tasks);
            List<VisualRenderer.Frame> frames = new ArrayList<>(futures.size());
            for (int i = 0; i < futures.size(); i++) {
                try {
                    frames.add(futures.get(i).get());
                } catch (ExecutionException e) {
                    BuilderMod.LOGGER.warn("[建造] 相机 {} 渲染任务异常：{}",
                            poses.get(i).id, e.toString());
                    frames.add(null);
                }
            }
            return frames;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ArrayList<>(Collections.nCopies(poses.size(), null));
        }
    }

    private static String topMaterials(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Integer> entry : entries) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(entry.getKey().replace("minecraft:", "")).append('×').append(entry.getValue());
        }
        return sb.toString();
    }
}
