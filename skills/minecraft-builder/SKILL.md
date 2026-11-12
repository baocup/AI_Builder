---
name: minecraft-builder
description: 通过 MCP 连接正在运行的 Minecraft 客户端，在游戏内建造建筑。当用户要求"在 Minecraft 里建/搭/盖某个东西""用建造手杖建个房子""修改游戏里的建筑"或提到外部建造手杖、minecraft-builder 时使用。
---

# Minecraft Builder 技能（MCP）

把本文件夹导入任意本地 Agent（WorkBuddy、DeepSeekHarness、Codex、Claude Code、Cursor，或任何支持 MCP 的客户端），通过 **MCP（Model Context Protocol）** 连接正在运行的 Minecraft，直接在游戏内建造。

**0 次模型调用**：游戏的 LLM 不会被请求。所有决策、几何与坐标都由你输出；游戏只负责几何编译、坐标校验、逐 tick 写入世界与版本归档。没有 API Key、没有 token 费用、没有模型超时，也不需要轮询任何文件。

## 两条链路，别搞混

| 物品 | 由谁驱动 |
| --- | --- |
| 建造指令（`build_commander`） | 游戏内 LLM Agent。**与本技能无关**，MCP 也读不到它的任务 |
| **外部建造手杖**（`external_build_wand`） | **你**（外部 Agent，通过 MCP）。左键选坐标，右键只看你的动作日志 |

所以流程永远是：玩家拿外部建造手杖 → 确定范围（玩家左键两次 / 在 GUI 手动输入 / 你调 `request_bounds` 自主设定）→ **一直手持** → 你调 `get_context` 读 `taskId` 与 `bounds` → 建模 → `capture` → `submit_build`。

## 连接

> 端口开在**玩家客户端**，不是服务器。几何编译、`VoxelWorld` 与拍照渲染都在客户端完成，只有最终方块通过 `C2SCommit` 分批发给服务端写入世界。所以每个客户端实例有独立端口与独立体素世界。

客户端启动时在本机开 MCP 服务：

```
POST http://127.0.0.1:8765/mcp        JSON-RPC 2.0，streamable HTTP
```

- 协议版本 `2025-06-18`；实现 `initialize` / `ping` / `tools/list` / `tools/call`
- 若配置了 `mcpToken`，请求头需带 `Authorization: Bearer <token>`
- 启动日志出现 `[建造] 本地 MCP 已监听 http://127.0.0.1:8765/mcp` 即就绪
- 端口 / 地址 / token 在 `config/builder-common.toml` 的 `[mcp]` 段（开发环境是 `run/config/`）
- 同机多开客户端时，每个实例必须配不同 `mcpPort`

各客户端配置片段：

**Claude Code**（项目根 `.mcp.json`）：

```json
{"mcpServers":{"minecraft-builder":{"type":"http","url":"http://127.0.0.1:8765/mcp"}}}
```

**Cursor**（`.cursor/mcp.json`）：与上面相同的 `mcpServers` 结构。

**Codex**（`~/.codex/config.toml`）：

```toml
[mcp_servers.minecraft_builder]
url = "http://127.0.0.1:8765/mcp"
```

其它工具只要能填「自定义 MCP server URL」就可用；带 token 时在请求头加 Bearer。

## 五条铁律

1. **所有工具都没有 `taskId` 参数。** 工具永远作用在"玩家当前手持的那根手杖"绑定的任务上。玩家放下手杖、换手持物，调用就会报 `请让玩家手持「外部建造手杖」并选好范围`。需要换任务就让玩家换手杖。
2. **选区有三种确定方式**：玩家左键两次、玩家在 GUI 里手动输入坐标、你调用 `request_bounds` 自主设定（会写回玩家的手杖并立即生效，不需要额外确认）。之后 `get_context` 返回的 `bounds` 就是你本轮能写的全部空间，超出会逐条拒绝；想调整就再调一次 `request_bounds`。
3. **坐标是绝对世界整数坐标**，与 `read_state`、`get_context` 返回的完全一致。不要用相对坐标，也不要按"建筑中心"猜。
4. **本地世界只装本轮新放的方块。** 客户端不会把世界里已有建筑读进来，所以 `read_state` 看不到你提交之前就存在的东西（第二次及以后建模时尤其要注意）。
5. **建模只在客户端，`submit_build` 才是"放进世界"。** 每轮工具回执都带 `localOnly:true` 与 `submitHint`——照片、`read_state`、体素都只在本地，世界看不到。`submit_build` 后本地世界**保留**（服务端是覆盖写入），你可以继续加建并再次提交；重复提交同一批不会出错。

## 推荐流程

0. 玩家手持「外部建造手杖」并**保持手持**。选区有三种来源：玩家左键两次、玩家在 GUI 里手动输入坐标、或你调用 `request_bounds` 自主设定（返回 `approved=true` 与生效范围；返回 `status=pending` 表示还没同步完，稍后用 `get_context` 复查）。
1. `get_context` → 拿 `taskId`、`bounds`、`volume`、`localBlocks`。`held=false` 说明玩家没拿手杖。
2. `list_materials` 查方块（可反复查、可翻页），必要时 `read_state(detail=summary)` 看本轮已有格数。
3. 建模一小段（例如地基）：`draw_shape` 搭形体 → `place_blocks` 补细节 → `remove_blocks` 开口。
4. `capture` 拍照自检：机位用绝对坐标，可一次多台，室内也能拍（见下方"拍照"章节的局限）。
5. **这一段确认没问题就立刻 `submit_build`**，先落进世界，再做下一段（墙体 / 屋顶 / 室内）。
6. 全部完成后可以再 `submit_build` 收尾；服务端按 `blocksPerTick` 分批写入并归档新版本。

## 工具

| 工具 | 说明 |
| --- | --- |
| `get_context` | **入口**，无参数：读手持手杖的 `taskId` / `bounds` / `volume` 与本轮 `localBlocks` / `localCaptures` |
| `request_bounds` | 自主设定选区：`bounds: [x1,y1,z1,x2,y2,z2]`，走与 GUI「应用坐标」相同的服务端通道 |
| `read_state` | 读本地体素，`detail=summary/full/region` |
| `list_materials` | 按关键词查方块、外观与物理特性；`limit`/`offset` 翻页 |
| `place_blocks` | 放单个 / 一批方块：`blocks: [[x,y,z,"minecraft:..."]]`，`mode=set/add` |
| `draw_shape` | `region` + `where` 数学谓词或 `shape` 快捷形状，一次生成一片 |
| `remove_blocks` | 局部删除；`region` 必须配 `where`/`shape`，或显式 `all=true` |
| `capture` | 离屏渲染拍照，任意机位，可一次多台相机 |
| `submit_build` | 把本轮方块分批发给服务端写入世界（每包 4096 格），可选 `summary` |

`tools/list` 返回的就是权威参数 schema，以它为准。

写入回执（`place_blocks` / `draw_shape` / `remove_blocks`）实际长这样：

```json
{"tool":"place_blocks","placed":1,"removed":0,
 "actualBounds":{"min":[-156,-60,525],"max":[-156,-60,525]},
 "rejected":0,"totalBlocks":1,
 "occupiedBounds":[-156,-60,525,-156,-60,525],
 "selection":[-156,-60,525,-59,25,590],
 "coordinateSystem":"绝对世界整数坐标；越界 / 非法 id 会被拒绝并回报，不会静默改动"}
```

两种坐标字段格式不同，别混：`actualBounds`（**只在写入回执里**）是 `{min:[x,y,z],max:[x,y,z]}` 对象，表示本次实际落地的范围；`occupiedBounds` 和 `selection` 是 `[x1,y1,z1,x2,y2,z2]` 数组，`read_state` 与写入回执都用这两个名字。`rejected>0` 时会多出 `rejectionSamples`（超过 10 条只显示前 10 条并附 `rejectionHint`）。**每次写完都要看 `rejected` 和 `actualBounds`**，不要凭记忆推测坐标。

单次调用有上限：写入最多 60000 格（`agentMaxBlocksPerCall`）、形状求值最多 200000 格（`agentMaxCellsPerCall`），超出部分记入 `rejected`；`read_state` 默认最多返回 20000 格，可用 `maxBlocks` 调整。大形体分多次 `draw_shape` 画。

`get_context` 回执：

```json
{"held":true,"taskId":"88d48a08-...","bounds":[0,64,0,15,80,15],"volume":4352,
 "localBlocks":0,"localCaptures":0,
 "note":"几何编译与渲染都在本地；submit_build 只把方块发给服务器"}
```

`submit_build` 回执：

```json
{"submitted":12480,"batches":4,"note":"服务端会按 blocksPerTick 分批写入世界并归档版本"}
```

## 形状：没有白名单

`draw_shape` 的 `where` 是任意数学谓词，逐体素求值，为真的格子被填充。变量：
`x y z`、`cx cy cz`、`rx ry rz`、`x0 y0 z0 x1 y1 z1`、`t`（归一化高度）、`i`、`n`、`pi e`。
运算符：`+ - * / % ^`、`== != > < >= <=`、`&& || !`、括号、逗号。
函数：`abs min max floor ceil round sqrt pow sin cos tan atan2 hypot clamp step smoothstep mod sign lerp fract dist dist2 length mix`。

`&& || !` 就是任意并集 / 交集 / 差集（CSG）。例：

```json
{"name":"draw_shape","arguments":{
  "region":[0,64,0,15,80,15],
  "block":"minecraft:stone_bricks",
  "where":"(x-cx)^2/rx^2+(y-cy)^2/ry^2+(z-cz)^2/rz^2<=1 && !(abs(x-8)<1 && z<3)"
}}
```

`shell:true` 只留外表面；`mode` 可选 `set/add/erase/replace`；也可用 `shape` + `params` 走内置快捷形状（球 / 圆柱 / 锥 / 环 / 螺旋 / 波浪 / 山墙屋顶 / 超椭圆……）。

## 拍照的局限（重要）

`capture` 在 MCP 下**只返回文本**，不把图片字节发给你。回执里有 `photos`（张数）、`cameras`（每台机位的实际坐标 / yaw / pitch / fov / `insideSolid`）、`localReport`（本地渲染统计）和 `note`，但没有图片内容——MCP 的 `content` 只有 `type:"text"`。外部链路的 `note` 会直接写"照片已落盘到本机"，不会再声称会附图。

照片确实落盘了：

```
<客户端游戏目录>/builder-captures/<完整 taskId>/v1-agent<N>-<机位id>.png
```

文件名用机位 **id** 不是机位名：自定义机位是 `ai-<拍照轮次>-<机位序号>`（如 `v1-agent1-ai-1-1.png`），预设机位是 `v1`/`top`/`eye` 等；客户端链路版本号固定 `v1`。

如果你的环境能读本地文件，可以自己去这个目录列文件、看 PNG；不能读就改用 `read_state` 核对体素，别反复等照片。

相机要点：
- 一次默认最多 8 台（`visionMaxImages`），可用 `maxCameras` 调整；不填 `cameras` 时用 `view` 预设机位（around/top/eye/all/none）
- 每台相机给绝对坐标 `x,y,z`，朝向用 `lookAt:[tx,ty,tz]`（最直观）或 `yaw/pitch`（yaw=0 朝 +Z、90 朝 -X，pitch 正值朝下）
- `fov` 水平视场角 20~110，默认 70；室内建议 85~100
- 找室内空气位置用 `read_state(detail=region)`：它只列实心方块，没列出的就是空气
- 回执里 `insideSolid:true` 表示机位卡在方块里，换个位置重拍
- 照片只渲染本次建造的方块，天空渐变与绿色棋盘格草地是占位背景，**不代表真实地形**

## 分段提交（必须遵守）

**每完成一个能独立成立的建造部分，就立刻 `submit_build` 一次，不要攒到最后一次性提交。**

- 推荐节奏：地基 → 提交 → 一层墙体与门窗 → 提交 → 二层 / 屋顶 → 提交 → 室内装修 → 提交；
- 服务端是**覆盖写入**，分多次提交不会破坏已有成果，重复提交同一批也安全；
- 攒着不提交的代价很高：工具轮用尽、上下文超限、对话中断时，本地体素直接消失，世界一格都没有——现象正是「拍照看得到、世界里没有」；
- 判断某段是否已落进世界：客户端日志会写「已提交 N 格方块给服务器」；只看到「（尚未写入世界）」就说明这一段还没提交。

## 提交与多轮

- `submit_build` 把本地 `VoxelWorld` 全部方块分批（4096 格/包）发给服务端，最后一批带 `last=true`
- 服务端收齐后按 `blocksPerTick`（默认 512）逐 tick 写入，全部写完才归档并标 `DONE`
- 提交后本地世界**保留**，`get_context` 的 `localBlocks` 仍是累计值；可以在其上加建后再提交（服务端覆盖写，重复提交安全）
- 如果怀疑没写进世界，先看服务端归档日志：正常会写「实际写入 N 格 / 收到 M 格」；只收到不写入会额外给一条 ERROR 说明原因
- 大工程建议分轮：先搭主体提交一轮，再搭内部提交一轮。每轮都从空本地世界开始，写入是覆盖式的 `setBlock`，所以第二轮只做"加"，不要假设上一轮的东西还在你手里
- 同一任务同一时刻只有一个客户端会话；多人服务器上只有任务所有者能提交

## 会「自己消失」的方块

原版物理规则会让一些方块放下后消失，看起来就像「渲染里有、世界里没有」。服务端写入时已经做了这些处理：

- **树叶**：自动把 `persistent` 锁成 `true`，不再按到原木的距离随机衰减。
- **重力方块**（沙子 / 沙砾 / 混凝土粉末 / 铁砧）：按**从下往上**的顺序写入，支撑先到位，所以不会下落；但**设计上真的悬空**的仍会被原版规则拉下来。
- **需要支撑的方块**（火把 / 藤蔓 / 草 / 花 / 雪片 / 按钮 / 红石元件）：写入不触发邻居更新，不会当场掉落；玩家之后手动更新邻居时仍可能掉落（原版行为）。
- **随机刻会变化的方块**（冰 / 雪融化、珊瑚离水死亡、耕地退化、草方块蔓延）：归档前会校验一次，变了就按原状态补写；补不回来的计入日志的「写入后消失 N 格」。

结论：**别用悬空的重力方块做设计**（`list_materials` 会标「受重力影响」）；需要悬空装饰时优先选不受影响的方块（营火代替火、锁链代替藤蔓）。

## 排错

| 现象 | 原因与处理 |
| --- | --- |
| 连不上 | 游戏没开、`mcpEnabled=false`、端口被占；看日志 `[建造] 本地 MCP 已监听 …`，端口冲突就改 `mcpPort` |
| 401 | 配了 `mcpToken` 但客户端没带 `Authorization: Bearer <token>` |
| `请让玩家手持「外部建造手杖」并选好范围` | 玩家没拿手杖 / 换手了 / 拿的是建造指令 |
| `物品还没有选区` | 还没有范围：让玩家左键两次 / 在 GUI 手动输入，或你自己调用 `request_bounds` |
| `request_bounds` 返回 `status=pending` | 服务端已收到但物品还没同步完；稍后 `get_context` 复查 `bounds`，生效前别按新范围建模 |
| `本地还没有任何方块` | `submit_build` 前没建模，先 `draw_shape` / `place_blocks` |
| 回执 `rejected` 很大 | 坐标超出 `bounds`、`y` 超出 -64~319（固定校验）、方块 id 非法；看 `rejectionSamples` 前 10 条 |
| `read_state` 看不到已有建筑 | 设计如此：客户端只装本轮新放的方块 |
| 工具超时 | 客户端卡住或不在存档里；`mcpToolTimeoutSeconds` 默认 120 秒 |
