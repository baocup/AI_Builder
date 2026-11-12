package baocup.builder.agent;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import baocup.builder.util.Json;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * NBT 与实体的独立写入通道。实体不进方块图、不参与 diff，用由 groupId + 描述派生的稳定 UUID 做幂等。
 *
 * 1.21.1 里 BlockEntity.loadAdditional/saveAdditional 是 protected，
 * 外部包必须用公开的 loadCustomOnly；getBlockEntity 只在服务端主线程返回非 null。
 */
public final class NbtEntityOps {
    /** 允许写入 BlockEntity 的标签白名单，拒绝任意 NBT 注入。 */
    private static final Set<String> NBT_KEYS = Set.of(
            "CustomName", "Text1", "Text2", "Text3", "Text4", "GlowingText",
            "Base", "Color", "FrontText", "BackText", "Rotation", "Patterns", "Items");

    private NbtEntityOps() {
    }

    /** 由构件组坐标与实体描述派生的稳定标识，重启/重复执行都会得到同一个值。 */
    public static String entityId(Placement placement) {
        String seed = (placement.groupId == null || placement.groupId.isBlank()
                ? placement.x + ":" + placement.y + ":" + placement.z : placement.groupId)
                + "|" + (placement.entity == null ? "" : placement.entity);
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 给刚放下的方块写白名单 NBT（箱子名、告示牌文本、旗帜底色等）。 */
    public static boolean applyBlockEntity(ServerLevel level, BlockPos pos, String nbtJson) {
        if (nbtJson == null || nbtJson.isBlank()) {
            return false;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return false;
        }
        JsonObject source;
        try {
            source = Json.GSON.fromJson(nbtJson, JsonObject.class);
        } catch (Exception e) {
            return false;
        }
        if (source == null || !source.isJsonObject()) {
            return false;
        }
        CompoundTag tag = new CompoundTag();
        for (java.util.Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (!NBT_KEYS.contains(entry.getKey()) || !entry.getValue().isJsonPrimitive()) {
                continue;
            }
            JsonElement value = entry.getValue();
            try {
                if (value.getAsJsonPrimitive().isBoolean()) {
                    tag.putBoolean(entry.getKey(), value.getAsBoolean());
                } else if (value.getAsJsonPrimitive().isNumber()) {
                    tag.putInt(entry.getKey(), value.getAsInt());
                } else {
                    tag.putString(entry.getKey(), value.getAsString());
                }
            } catch (Exception ignored) {
                // 单个非法值不影响其它标签
            }
        }
        if (tag.isEmpty()) {
            return false;
        }
        blockEntity.loadCustomOnly(tag, level.registryAccess());
        blockEntity.setChanged();
        return true;
    }

    /** 幂等放置实体：先按稳定 UUID 移除旧的，再创建并加入世界。 */
    public static boolean applyEntity(ServerLevel level, Placement placement) {
        JsonObject description;
        try {
            description = Json.GSON.fromJson(placement.entity, JsonObject.class);
        } catch (Exception e) {
            return false;
        }
        if (description == null || !description.isJsonObject()) {
            return false;
        }
        String typeId = Json.str(description, "type", "");
        ResourceLocation key = ResourceLocation.tryParse(typeId);
        if (key == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(key)) {
            return false;
        }
        String uuidText = entityId(placement);
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidText);
        } catch (Exception e) {
            return false;
        }
        Entity existing = level.getEntity(uuid);
        if (existing != null) {
            existing.discard();
        }

        String facing = Json.str(description, "facing", "north");
        Direction direction = Direction.byName(facing);
        if (direction == null || direction.getAxis().isVertical()) {
            direction = Direction.NORTH;
        }
        BlockPos pos = new BlockPos(placement.x, placement.y, placement.z);
        Entity entity;
        try {
            if ("minecraft:item_frame".equals(typeId) || "minecraft:glow_item_frame".equals(typeId)) {
                ItemFrame frame = new ItemFrame(level, pos, direction);
                String itemId = Json.str(description, "item", "");
                ResourceLocation itemKey = ResourceLocation.tryParse(itemId);
                if (itemKey != null && BuiltInRegistries.ITEM.containsKey(itemKey)) {
                    frame.setItem(new ItemStack(BuiltInRegistries.ITEM.get(itemKey)));
                }
                frame.setRotation(clampRotation(Json.integer(description, "rotation", 0)));
                entity = frame;
            } else if ("minecraft:armor_stand".equals(typeId)) {
                ArmorStand stand = new ArmorStand(level,
                        placement.x + 0.5, placement.y, placement.z + 0.5);
                stand.setYRot(yawOf(direction));
                stand.setInvisible(Json.bool(description, "invisible", false));
                stand.setShowArms(Json.bool(description, "showArms", false));
                stand.setNoBasePlate(Json.bool(description, "noBasePlate", false));
                entity = stand;
            } else {
                Entity created = BuiltInRegistries.ENTITY_TYPE.get(key).create(level);
                if (created == null) {
                    return false;
                }
                created.setPos(placement.x + 0.5, placement.y, placement.z + 0.5);
                created.setYRot(yawOf(direction));
                entity = created;
            }
        } catch (Exception e) {
            return false;
        }
        entity.setUUID(uuid);
        return level.addFreshEntity(entity);
    }

    /** 差量删除通道：移除上一版存在、这一版不再存在的实体。 */
    public static boolean removeEntity(ServerLevel level, String uuidText) {
        if (uuidText == null || uuidText.isBlank()) {
            return false;
        }
        try {
            Entity existing = level.getEntity(UUID.fromString(uuidText));
            if (existing == null) {
                return false;
            }
            existing.discard();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int clampRotation(int rotation) {
        return Math.max(0, Math.min(7, rotation));
    }

    private static float yawOf(Direction direction) {
        return switch (direction) {
            case SOUTH -> 0.0F;
            case WEST -> 90.0F;
            case NORTH -> 180.0F;
            case EAST -> -90.0F;
            default -> 0.0F;
        };
    }
}
