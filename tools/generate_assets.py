# -*- coding: utf-8 -*-
"""
从原版 Minecraft 客户端 jar 中读取方块贴图，生成「方块外观索引数据库」以及本 Mod 的物品贴图。

用法：
    python tools/generate_assets.py [path/to/minecraft_1.21.1_client.jar]

产物：
    src/main/resources/assets/builder/block_index.json
    src/main/resources/assets/builder/textures/item/build_commander.png

只依赖 Python 标准库（自带一个最小 PNG 解码器，支持调色板 / 1,2,4,8 位深 / PNG 滤波器）。
"""
from __future__ import annotations

import collections
import json
import math
import re
import struct
import sys
import zipfile
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src" / "main" / "resources" / "assets" / "builder"
DEFAULT_JAR = Path.home() / ".gradle" / "caches" / "neoformruntime" / "artifacts" / "minecraft_1.21.1_client.jar"

# ---------------------------------------------------------------------------
# 最小 PNG 解码器
# ---------------------------------------------------------------------------


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def decode_png(data: bytes):
    """返回 [(r, g, b, a), ...]（按行扫描顺序），不支持隔行扫描时返回空列表。"""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        return []
    pos = 8
    idat = bytearray()
    plte, trns = None, None
    width = height = bit_depth = color_type = interlace = 0
    while pos + 8 <= len(data):
        length = struct.unpack_from(">I", data, pos)[0]
        kind = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, bit_depth, color_type, _comp, _filt, interlace = struct.unpack(">IIBBBBB", chunk)
        elif kind == b"PLTE":
            plte = [tuple(chunk[i:i + 3]) for i in range(0, len(chunk), 3)]
        elif kind == b"tRNS":
            trns = chunk
        elif kind == b"IDAT":
            idat += chunk
        elif kind == b"IEND":
            break
    if interlace or width <= 0 or height <= 0 or not idat:
        return []
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color_type)
    if channels is None or bit_depth not in (1, 2, 4, 8):
        return []
    raw = zlib.decompress(bytes(idat))
    stride = (width * channels * bit_depth + 7) // 8
    pixel_bytes = max(1, (channels * bit_depth + 7) // 8)
    rows = []
    prev = bytearray(stride)
    cursor = 0
    for _ in range(height):
        if cursor >= len(raw):
            break
        filt = raw[cursor]
        cursor += 1
        line = bytearray(raw[cursor:cursor + stride])
        cursor += stride
        if len(line) < stride:
            break
        if filt:
            for x in range(stride):
                a = line[x - pixel_bytes] if x >= pixel_bytes else 0
                b = prev[x]
                c = prev[x - pixel_bytes] if x >= pixel_bytes else 0
                if filt == 1:
                    delta = a
                elif filt == 2:
                    delta = b
                elif filt == 3:
                    delta = (a + b) // 2
                elif filt == 4:
                    delta = _paeth(a, b, c)
                else:
                    delta = 0
                line[x] = (line[x] + delta) & 0xFF
        rows.append(line)
        prev = line

    max_sample = (1 << bit_depth) - 1
    per_byte = 8 // bit_depth if bit_depth < 8 else 1
    pixels = []
    for row in rows:
        for x in range(width):
            if bit_depth == 8:
                s = tuple(row[x * channels:(x + 1) * channels])
            else:
                byte = row[x // per_byte]
                shift = 8 - bit_depth - (x % per_byte) * bit_depth
                s = tuple((byte >> shift) & max_sample for _ in range(channels))
            if len(s) != channels:
                continue
            if color_type == 0:
                g = s[0] * 255 // max_sample
                alpha = 0 if (trns and len(trns) >= 2 and s[0] == struct.unpack(">H", trns[:2])[0]) else 255
                pixels.append((g, g, g, alpha))
            elif color_type == 2:
                r, g, b = (v * 255 // max_sample for v in s)
                alpha = 0 if (trns and len(trns) >= 6 and s == struct.unpack(">HHH", trns[:6])) else 255
                pixels.append((r, g, b, alpha))
            elif color_type == 3:
                idx = s[0]
                if plte is None or idx >= len(plte):
                    continue
                r, g, b = plte[idx]
                alpha = trns[idx] if (trns and idx < len(trns)) else 255
                pixels.append((r, g, b, alpha))
            elif color_type == 4:
                g = s[0] * 255 // max_sample
                pixels.append((g, g, g, s[1] * 255 // max_sample))
            else:
                pixels.append(tuple(v * 255 // max_sample for v in s))
    return pixels


def write_png(path: Path, width: int, height: int, pixels) -> None:
    """pixels: 可迭代的 (r,g,b,a)，按行扫描顺序。写出 8 位 RGBA PNG。"""
    raw = bytearray()
    px = list(pixels)
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw.extend(px[y * width + x])

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


# ---------------------------------------------------------------------------
# 贴图 -> 外观特征
# ---------------------------------------------------------------------------

COLOR_NAMES = [
    ("黑色", (24, 24, 28)), ("深灰", (68, 68, 72)), ("灰色", (128, 128, 130)),
    ("浅灰", (186, 188, 190)), ("白色", (238, 238, 238)),
    ("红色", (176, 46, 40)), ("暗红", (110, 32, 32)), ("橙色", (226, 132, 42)),
    ("黄色", (232, 208, 64)), ("米黄", (214, 198, 152)), ("棕色", (124, 82, 48)),
    ("浅棕", (176, 138, 92)), ("黄绿", (140, 196, 58)), ("绿色", (68, 140, 58)),
    ("深绿", (40, 88, 44)), ("青色", (58, 176, 176)), ("浅蓝", (122, 178, 224)),
    ("蓝色", (48, 96, 190)), ("深蓝", (36, 48, 106)), ("紫色", (132, 74, 176)),
    ("品红", (190, 76, 168)), ("粉色", (232, 152, 176)), ("金色", (216, 176, 72)),
    ("青绿", (62, 158, 128)),
]


def nearest_color(r: int, g: int, b: int) -> str:
    best, dist = "灰色", 1 << 30
    for name, (cr, cg, cb) in COLOR_NAMES:
        d = (r - cr) ** 2 * 3 + (g - cg) ** 2 * 5 + (b - cb) ** 2 * 2
        if d < dist:
            best, dist = name, d
    return best


def hex_color(rgb) -> str:
    return "#%02x%02x%02x" % tuple(int(v) & 0xFF for v in rgb)


# 英文 token -> 中文，用来生成可读名称
TOKENS = {
    "air": "空气",
    "stone": "石头",
    "cobblestone": "圆石",
    "mossy": "苔藓",
    "smooth": "平滑",
    "bricks": "砖块",
    "brick": "砖",
    "slab": "台阶",
    "stairs": "楼梯",
    "wall": "墙",
    "fence": "栅栏",
    "gate": "栅栏门",
    "door": "门",
    "trapdoor": "活板门",
    "planks": "木板",
    "log": "原木",
    "wood": "木",
    "stripped": "去皮",
    "hyphae": "菌柄",
    "stem": "菌柄",
    "block": "块",
    "of": "",
    "the": "",
    "and": "",
    "glass": "玻璃",
    "pane": "板",
    "wool": "羊毛",
    "concrete": "混凝土",
    "powder": "粉末",
    "terracotta": "陶瓦",
    "glazed": "带釉",
    "sand": "砂",
    "sandstone": "砂岩",
    "gravel": "砂砾",
    "dirt": "泥土",
    "grass": "草方块",
    "podzol": "灰化土",
    "mycelium": "菌丝",
    "farmland": "耕地",
    "path": "径",
    "oak": "橡木",
    "spruce": "云杉木",
    "birch": "白桦木",
    "jungle": "丛林木",
    "acacia": "金合欢木",
    "dark": "深色",
    "mangrove": "红树林木",
    "cherry": "樱花木",
    "bamboo": "竹",
    "crimson": "绯红木",
    "warped": "诡异木",
    "pale": "苍白",
    "quartz": "石英",
    "purpur": "紫珀",
    "prismarine": "海晶石",
    "sea": "海晶",
    "lantern": "灯",
    "deepslate": "深板岩",
    "tuff": "凝灰岩",
    "calcite": "方解石",
    "andesite": "安山岩",
    "diorite": "闪长岩",
    "granite": "花岗岩",
    "basalt": "玄武岩",
    "blackstone": "黑石",
    "nether": "下界",
    "end": "末地",
    "obsidian": "黑曜石",
    "glowstone": "荧石",
    "torch": "火把",
    "campfire": "营火",
    "fire": "火",
    "iron": "铁",
    "gold": "金",
    "copper": "铜",
    "diamond": "钻石",
    "emerald": "绿宝石",
    "lapis": "青金石",
    "redstone": "红石",
    "leaves": "树叶",
    "sapling": "树苗",
    "flower": "花",
    "vine": "藤蔓",
    "moss": "苔藓",
    "water": "水",
    "lava": "岩浆",
    "snow": "雪",
    "ice": "冰",
    "clay": "黏土",
    "mud": "泥",
    "packed": "浮",
    "white": "白色",
    "orange": "橙色",
    "magenta": "品红色",
    "light": "浅色",
    "blue": "蓝色",
    "yellow": "黄色",
    "lime": "黄绿色",
    "pink": "粉红色",
    "gray": "灰色",
    "grey": "灰色",
    "cyan": "青色",
    "purple": "紫色",
    "brown": "棕色",
    "green": "绿色",
    "red": "红色",
    "black": "黑色",
    "chiseled": "錾制",
    "cut": "切制",
    "polished": "磨制",
    "cracked": "裂纹",
    "weathered": "锈蚀",
    "oxidized": "氧化",
    "tinted": "染色",
    "infested": "虫蚀",
    "ladder": "梯子",
    "chain": "锁链",
    "anvil": "铁砧",
    "chest": "箱子",
    "barrel": "木桶",
    "crafting": "合成",
    "table": "台",
    "furnace": "熔炉",
    "cauldron": "炼药锅",
    "cake": "蛋糕",
    "bed": "床",
    "banner": "旗帜",
    "candle": "蜡烛",
    "sign": "告示牌",
    "hanging": "悬挂",
    "button": "按钮",
    "pressure": "压力",
    "plate": "板",
    "lever": "拉杆",
    "observer": "侦测器",
    "dispenser": "发射器",
    "dropper": "投掷器",
    "hopper": "漏斗",
    "piston": "活塞",
    "sticky": "粘性",
    "note": "音符",
    "jukebox": "唱片机",
    "beacon": "信标",
    "spawner": "刷怪笼",
    "portal": "传送门",
    "frame": "框架",
    "bookshelf": "书架",
    "lectern": "讲台",
    "loom": "织布机",
    "smithing": "锻造",
    "grindstone": "砂轮",
    "composter": "堆肥桶",
    "beehive": "蜂箱",
    "bee": "蜂",
    "honey": "蜂蜜",
    "hay": "干草",
    "bone": "骨",
    "melon": "西瓜",
    "pumpkin": "南瓜",
    "carved": "雕刻",
    "jack": "南瓜灯",
    "soul": "灵魂",
    "amethyst": "紫水晶",
    "budding": "母岩",
    "cluster": "晶簇",
    "bud": "芽",
    "sculk": "幽匿",
    "sensor": "感测体",
    "shrieker": "尖啸体",
    "catalyst": "催化体",
    "vein": "脉络",
    "sniffer": "嗅探兽",
    "torchflower": "火把花",
    "turtle": "海龟",
    "egg": "蛋",
    "dragon": "龙",
    "head": "头颅",
    "skull": "头颅",
    "coral": "珊瑚",
    "dead": "失活",
    "seagrass": "海草",
    "kelp": "海带",
    "lily": "睡莲",
    "pad": "叶",
    "sugar": "甘蔗",
    "cane": "蔗",
    "cactus": "仙人掌",
    "mushroom": "蘑菇",
    "wart": "疣",
    "roots": "根系",
    "fungus": "真菌",
    "nylium": "菌岩",
    "shroomlight": "菌光体",
    "weeping": "垂泪",
    "twisting": "扭曲",
    "crying": "哭泣",
    "gilded": "镶金",
    "respawn": "重生",
    "anchor": "锚",
    "lodestone": "磁石",
    "bell": "钟",
    "conduit": "潮涌核心",
    "dripstone": "滴水石",
    "pointed": "钟乳石",
    "azalea": "杜鹃",
    "flowering": "盛开的",
    "spore": "孢子",
    "blossom": "花",
    "big": "大型",
    "dripleaf": "滴水叶",
    "small": "小型",
    "cave": "洞穴",
    "frosted": "霜",
    "bubble": "气泡",
    "column": "柱",
    "void": "虚空",
    "structure": "结构",
    "barrier": "屏障",
    "command": "命令",
    "repeating": "循环",
    "moving": "移动的",
    "petrified": "石化",
    "double": "双层",
    "tall": "高",
    "allium": "绒球葱",
    "azure": "蓝花",
    "bluet": "蓝花美耳草",
    "cornflower": "矢车菊",
    "dandelion": "蒲公英",
    "poppy": "虞美人",
    "orchid": "兰花",
    "rose": "玫瑰",
    "sunflower": "向日葵",
    "lilac": "丁香",
    "peony": "牡丹",
    "bush": "灌木",
    "fern": "蕨",
    "shrub": "灌木",
    "sweet": "甜",
    "berry": "浆果",
    "wheat": "小麦",
    "potato": "马铃薯",
    "carrot": "胡萝卜",
    "beetroot": "甜菜",
    "netherite": "下界合金",
    "ancient": "远古",
    "debris": "残骸",
    "heavy": "重型",
    "core": "核心",
    "trial": "试炼",
    "vault": "宝库",
    "crafter": "合成器",
    "grate": "格栅",
    "bulb": "灯泡",
}

CATEGORY_RULES = [
    (("glass", "pane"), "玻璃", "窗户与幕墙、采光立面"),
    (("wool", "carpet", "bed", "candle"), "软质织物", "室内软装、色块点缀"),
    (("concrete", "terracotta", "glazed"), "现代饰面", "现代外墙、平整色块、装饰面"),
    (("planks", "log", "wood", "hyphae", "stem", "bamboo", "stripped", "bookshelf", "barrel"), "木材", "梁柱、地板、屋顶、门窗框"),
    (("leaves", "sapling", "flower", "grass", "fern", "vine", "moss", "bush", "azalea", "coral", "kelp", "seagrass", "lily", "cactus", "sugar", "mushroom", "roots", "fungus", "nylium"), "植物", "绿化、庭院、景观"),
    (("lantern", "glowstone", "torch", "shroomlight", "sea_lantern", "campfire", "candle", "lamp", "bulb", "beacon", "ochre_froglight", "froglight"), "光源", "夜间照明、氛围点缀"),
    (("stone", "cobblestone", "deepslate", "tuff", "andesite", "diorite", "granite", "basalt", "blackstone", "bricks", "sandstone", "prismarine", "quartz", "purpur", "calcite", "obsidian", "nether", "end_stone", "dripstone", "amethyst", "sculk", "mud", "clay", "snow", "ice", "dirt", "gravel", "sand"), "石材", "墙体、地基、路面、承重结构"),
    (("iron", "gold", "copper", "netherite", "diamond", "emerald", "lapis", "redstone", "chain", "anvil", "hopper", "piston", "observer", "dispenser", "dropper"), "金属机械", "机械感装饰、工业风细节"),
    (("door", "trapdoor", "gate"), "门窗", "出入口、隔断"),
    (("stairs", "slab", "wall", "fence"), "构件", "收边、屋顶、台阶、栏杆"),
]

TECH_KEYWORDS = (
    "air", "water", "lava", "bubble_column", "fire", "portal", "end_gateway",
    "structure_void", "barrier", "light", "command_block", "moving_piston",
    "piston_head", "potted_", "_head", "_skull", "wall_banner", "wall_sign",
    "wall_torch", "wall_fan", "attached_", "tripwire", "redstone_wire",
    "nether_sprouts", "cave_vines", "weeping_vines", "twisting_vines",
    "sugar_cane", "chorus_plant", "chorus_flower", "kelp_plant", "bamboo_sapling",
    "frosted_ice", "player_head", "spawner", "trial_spawner", "vault",
    "bedrock", "end_portal_frame", "reinforced_deepslate", "respawn_anchor",
)


NAME_OVERRIDES = {
    "sea_lantern": "海晶灯", "glowstone": "荧石", "jack_o_lantern": "南瓜灯",
    "cherry_leaves": "樱花树叶", "oak_leaves": "橡木树叶", "dark_oak_leaves": "深色橡木树叶",
    "stone_bricks": "石砖", "mossy_stone_bricks": "苔石砖", "cracked_stone_bricks": "裂纹石砖",
    "chiseled_stone_bricks": "錾制石砖", "smooth_stone": "平滑石头", "stone_slab": "石头台阶",
    "oak_stairs": "橡木楼梯", "spruce_stairs": "云杉木楼梯", "stone_brick_stairs": "石砖楼梯",
    "oak_door": "橡木门", "spruce_door": "云杉木门", "iron_door": "铁门",
    "oak_trapdoor": "橡木活板门", "oak_fence": "橡木栅栏", "oak_fence_gate": "橡木栅栏门",
    "cobblestone_wall": "圆石墙", "mossy_cobblestone_wall": "苔石墙",
    "glass_pane": "玻璃板", "white_stained_glass": "白色玻璃", "light_blue_stained_glass": "浅蓝色玻璃",
    "quartz_block": "石英块", "smooth_quartz": "平滑石英块", "quartz_stairs": "石英楼梯",
    "prismarine_bricks": "海晶石砖", "dark_prismarine": "暗海晶石", "prismarine": "海晶石",
    "deepslate_tiles": "深板岩瓦", "deepslate_bricks": "深板岩砖", "polished_deepslate": "磨制深板岩",
    "tuff_bricks": "凝灰岩砖", "mud_bricks": "泥砖", "nether_bricks": "下界砖块",
    "red_nether_bricks": "红色下界砖块", "bricks": "红砖块", "terracotta": "陶瓦",
    "white_concrete": "白色混凝土", "light_gray_concrete": "浅灰色混凝土",
    "black_concrete": "黑色混凝土", "gray_concrete": "灰色混凝土",
    "white_wool": "白色羊毛", "red_wool": "红色羊毛", "blue_wool": "蓝色羊毛",
    "bookshelf": "书架", "crafting_table": "合成台", "furnace": "熔炉",
    "stonecutter": "切石机", "barrel": "木桶", "lantern": "灯笼", "soul_lantern": "灵魂灯笼",
    "campfire": "营火", "torch": "火把", "soul_torch": "灵魂火把",
    "grass_block": "草方块", "dirt_path": "土径", "farmland": "耕地",
    "iron_block": "铁块", "gold_block": "金块", "diamond_block": "钻石块",
    "copper_block": "铜块", "cut_copper": "切制铜块", "oxidized_copper": "氧化铜块",
    "oak_log": "橡木原木", "spruce_log": "云杉木原木", "dark_oak_log": "深色橡木原木",
    "birch_log": "白桦木原木", "stripped_oak_log": "去皮橡木原木",
    "water": "水", "lava": "岩浆", "air": "空气", "bedrock": "基岩",
}


GRAVITY_NOTE = ("受重力影响：正下方没有实心方块时会掉落成实体，可能堆积或消失；"
                "建造时必须保证每一格正下方都有支撑，禁止悬空使用")
SUPPORT_NOTE = ("需要依附/支撑：下方或侧面缺少支撑时会被破坏；不要悬空放置")


def physics_of(block_id: str) -> tuple[str, list[str]]:
    """返回 (物理说明, 附加标签)。用于沙子、沙砾、混凝土粉末等会掉落的方块。"""
    key = block_id.split(":")[-1]
    # sandstone / soul_sand 名字里带 sand，但不受重力影响
    if "sandstone" in key or "soul_sand" in key or "powder_snow" in key:
        return "", []
    falling = (
        key in {"sand", "red_sand", "gravel", "dragon_egg", "anvil", "chipped_anvil", "damaged_anvil"}
        or key.startswith("suspicious_sand") or key.startswith("suspicious_gravel")
        or key.endswith("concrete_powder")
        or key.endswith("_gravel")
    )
    if falling:
        return GRAVITY_NOTE, ["会掉落", "需支撑", "重力方块"]
    if key == "scaffolding" or key.startswith("pointed_dripstone") or key.endswith("_carpet"):
        return SUPPORT_NOTE, ["需支撑"]
    return "", []


def chinese_name(block_id: str) -> str:
    key = block_id.split(":")[-1]
    if key in NAME_OVERRIDES:
        return NAME_OVERRIDES[key]
    parts = key.split("_")
    out = []
    for p in parts:
        out.append(TOKENS.get(p, p))
    name = "".join(x for x in out if x)
    # 常见组合修正
    name = name.replace("浅色蓝色", "浅蓝色").replace("浅色灰色", "浅灰色")
    name = name.replace("深色橡木", "深色橡木")
    return name or block_id


def categorize(block_id: str):
    for keys, cat, usage in CATEGORY_RULES:
        if any(k in block_id for k in keys):
            return cat, usage
    return "杂项", "装饰与功能构件"


def describe(block_id: str, stats: dict | None) -> dict:
    cat, usage = categorize(block_id)
    name = chinese_name(block_id)
    if stats is None or stats["opaque"] == 0:
        traits = ["无实体贴图"]
        color = "透明"
        desc = f"{name}：无可见实体贴图（空气/流体/技术方块），不用于建造实体。"
        physics, extra_tags = physics_of(block_id)
        return {
            "id": block_id, "name": name, "category": cat, "desc": desc,
            "hex": "#00000000", "palette": [], "tags": [cat, "非实体", "不可用"] + extra_tags,
            "physics": physics, "buildable": False,
        }

    r, g, b = stats["mean"]
    color = nearest_color(r, g, b)
    alpha_ratio = stats["transparent"] / stats["total"]
    lum = stats["lum"]
    var = stats["std"]
    edge = stats["edge"]

    tags = [cat, color, usage]
    visual = []
    if alpha_ratio > 0.45:
        visual.append("大量镂空/半透明像素")
        tags += ["半透明", "镂空"]
    elif alpha_ratio > 0.08:
        visual.append("局部镂空")
        tags += ["局部镂空"]
    else:
        tags.append("不透明")

    if var < 12:
        visual.append("表面平整均匀、几乎无杂色")
        tags += ["平整", "细腻"]
    elif var < 32:
        visual.append("带有细微的颗粒与色差")
        tags += ["细颗粒"]
    else:
        visual.append("纹理对比强烈、有明显花纹")
        tags += ["粗纹理", "高对比"]

    if edge > 0.42:
        visual.append("细碎图案密集")
        tags.append("花纹")
    if lum > 190:
        visual.append("整体明亮")
        tags.append("明亮")
    elif lum < 70:
        visual.append("整体暗沉")
        tags.append("暗沉")
    else:
        visual.append("明度适中")

    top_colors = stats["palette"]
    color_hint = ("主色 " + color + "(" + hex_color((r, g, b)) + ")")
    physics, physics_tags = physics_of(block_id)
    desc = f"{name}：{color_hint}，{cat}质感，{'、'.join(visual)}；适合{usage}。"
    if physics:
        desc += physics + "。"
        tags += physics_tags

    # 透明度只描述外观，不影响可建造性：玻璃、树叶、冰同样是常用建材。
    buildable = not any(k in block_id for k in TECH_KEYWORDS)
    tags += ["半透明" if alpha_ratio > 0.45 else "实体", "可用" if buildable else "不可用"]
    return {
        "id": block_id, "name": name, "category": cat, "desc": desc,
        "hex": hex_color((r, g, b)), "palette": top_colors,
        "tags": list(dict.fromkeys(tags)), "physics": physics, "buildable": buildable,
    }


def texture_stats(pixels) -> dict | None:
    if not pixels:
        return None
    total = len(pixels)
    opaque = [(r, g, b) for r, g, b, a in pixels if a >= 48]
    if not opaque:
        return {"mean": (0, 0, 0), "opaque": 0, "transparent": total, "total": total,
                "lum": 0, "std": 0, "edge": 0, "palette": []}
    n = len(opaque)
    mean = tuple(sum(p[i] for p in opaque) / n for i in range(3))
    lums = [0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2] for p in opaque]
    avg_l = sum(lums) / n
    std = math.sqrt(sum((v - avg_l) ** 2 for v in lums) / n)
    quant = collections.Counter((p[0] // 32, p[1] // 32, p[2] // 32) for p in opaque)
    palette = []
    for (qr, qg, qb), _cnt in quant.most_common(4):
        palette.append(hex_color((qr * 32 + 16, qg * 32 + 16, qb * 32 + 16)))
    # 水平相邻像素差异（只统计一行中的相邻像素，近似纹理细节密度）
    row_width = _row_width(total)
    edges = 0
    compared = 0
    if row_width and row_width > 1:
        for i in range(total):
            if i % row_width == 0:
                continue
            p, q = pixels[i - 1], pixels[i]
            compared += 1
            if abs(p[0] - q[0]) + abs(p[1] - q[1]) + abs(p[2] - q[2]) > 90:
                edges += 1
    return {
        "mean": tuple(int(v) for v in mean), "opaque": n, "transparent": total - n,
        "total": total, "lum": avg_l, "std": std,
        "edge": edges / compared if compared else 0, "palette": palette,
    }


def _row_width(total: int) -> int:
    for w in (16, 32, 64, 128):
        if total % w == 0:
            return w
    return 0


# ---------------------------------------------------------------------------
# 客户端 jar -> 方块 -> 贴图
# ---------------------------------------------------------------------------


def load_json(zf: zipfile.ZipFile, path: str):
    try:
        return json.loads(zf.read(path).decode("utf-8"))
    except (KeyError, UnicodeDecodeError, json.JSONDecodeError):
        return None


def model_textures(zf: zipfile.ZipFile, model_name: str, depth: int = 0) -> dict:
    if depth > 6 or not model_name:
        return {}
    path = "assets/minecraft/models/" + model_name.split(":")[-1] + ".json"
    data = load_json(zf, path)
    if not data:
        return {}
    result = {}
    parent = data.get("parent")
    if parent:
        result.update(model_textures(zf, parent, depth + 1))
    for key, value in (data.get("textures") or {}).items():
        if isinstance(value, str):
            result[key] = value
    return result


def resolve_texture_refs(textures: dict) -> list[str]:
    resolved = {}
    for _ in range(4):
        for key, value in textures.items():
            if not isinstance(value, str) or not value.startswith("#"):
                resolved[key] = value
            else:
                target = value[1:]
                if target in textures and not str(textures[target]).startswith("#"):
                    resolved[key] = textures[target]
    out = []
    priority = ("all", "top", "side", "end", "north", "south", "east", "west",
                "bottom", "texture", "layer0", "cross", "particle", "front", "back")
    ordered = [k for k in priority if k in resolved] + [k for k in resolved if k not in priority]
    for key in ordered:
        value = resolved.get(key)
        if not isinstance(value, str) or value.startswith("#"):
            continue
        path = value.split(":")[-1]
        if path.startswith("block/") or path.startswith("item/"):
            out.append("assets/minecraft/textures/" + path + ".png")
    return list(dict.fromkeys(out))[:6]


def collect_block_models(zf: zipfile.ZipFile, blockstate_name: str) -> list[str]:
    data = load_json(zf, "assets/minecraft/blockstates/" + blockstate_name + ".json")
    if not data:
        return []
    models = []

    def take(node):
        if isinstance(node, dict):
            if isinstance(node.get("model"), str):
                models.append(node["model"])
        elif isinstance(node, list):
            for sub in node:
                take(sub)

    for variant in (data.get("variants") or {}).values():
        take(variant)
    for part in (data.get("multipart") or []):
        take(part.get("apply"))
    # 补充同名物品模型（部分方块只在物品模型里给出贴图）
    models.append("item/" + blockstate_name)
    return list(dict.fromkeys(models))


def build_index(zf: zipfile.ZipFile) -> dict:
    names = sorted(
        n[len("assets/minecraft/blockstates/"):-len(".json")]
        for n in zf.namelist()
        if n.startswith("assets/minecraft/blockstates/") and n.endswith(".json")
    )
    # 没有 blockstate 的少量方块，也补进索引
    names += [x for x in (
        "air", "cave_air", "void_air", "water", "lava", "bubble_column",
        "fire", "soul_fire", "nether_portal", "end_portal", "end_gateway",
        "structure_void", "barrier", "light", "moving_piston", "piston_head",
    ) if x not in names]

    blocks = []
    with_textures = 0
    for name in names:
        block_id = "minecraft:" + name
        all_pixels = []
        for model in collect_block_models(zf, name):
            textures = model_textures(zf, model)
            for tex_path in resolve_texture_refs(textures):
                try:
                    all_pixels.extend(decode_png(zf.read(tex_path)))
                except (KeyError, zlib.error, ValueError):
                    pass
            if len(all_pixels) >= 256:
                break
        if all_pixels:
            with_textures += 1
        blocks.append(describe(block_id, texture_stats(all_pixels) if all_pixels else None))
    return {
        "version": 1,
        "source": "minecraft 1.21.1 client jar (assets/minecraft/textures/block/*.png)",
        "note": "外观描述由贴图逐像素统计（主色/明度/方差/透明度/细节密度）结合方块语义自动生成",
        "count": len(blocks),
        "textured": with_textures,
        "blocks": blocks,
    }


# ---------------------------------------------------------------------------
# 物品贴图（16x16 像素画）
# ---------------------------------------------------------------------------


def item_icon(kind: str):
    px = [(0, 0, 0, 0)] * 256

    def setp(x, y, c):
        if 0 <= x < 16 and 0 <= y < 16:
            px[y * 16 + x] = c

    def rect(x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                setp(x, y, c)

    shadow = (28, 32, 44, 255)
    edge = (58, 68, 92, 255)
    body = (196, 148, 64, 255)
    bright = (255, 226, 142, 255)
    rect(2, 1, 13, 14, shadow)
    rect(3, 2, 12, 13, edge)
    rect(4, 3, 11, 12, (36, 28, 20, 255))
    # 卷轴/图纸 + 文字行，表示指令、计划与相机
    rect(5, 4, 10, 5, body)
    for y in (7, 9):
        rect(5, y, 10, y, (222, 196, 128, 255))
    rect(5, 11, 8, 11, bright)
    rect(6, 2, 9, 2, bright)
    return px


def main() -> None:
    jar = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_JAR
    if not jar.is_file():
        print("找不到客户端 jar：", jar)
        print("用法： python tools/generate_assets.py <minecraft_1.21.1_client.jar>")
        sys.exit(1)
    OUT.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(jar) as zf:
        index = build_index(zf)
    (OUT / "block_index.json").write_text(
        json.dumps(index, ensure_ascii=False, indent=1), encoding="utf-8")
    print("方块索引：%d 个方块，其中 %d 个读取到了真实贴图" % (index["count"], index["textured"]))
    print("输出：", OUT / "block_index.json")

    write_png(OUT / "textures" / "item" / "build_commander.png", 16, 16,
              item_icon("build_commander"))
    print("物品贴图已生成")


if __name__ == "__main__":
    main()
