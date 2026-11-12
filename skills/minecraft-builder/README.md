# Minecraft Builder 技能（MCP，0 次模型调用）

给本地 Agent 用的完整说明在 [`SKILL.md`](SKILL.md)。这里只讲怎么连、怎么确认它在工作。

## 它是什么

游戏内置了一个 **MCP（Model Context Protocol）服务端**，跑在**玩家客户端**上。任意支持 MCP 的本地 Agent（WorkBuddy / DeepSeekHarness / Codex / Claude Code / Cursor）连上 `http://127.0.0.1:8765/mcp`，就能通过 `tools/call` 直接读状态、建模、拍照、提交。

**整条链路不调用任何 LLM API**：没有 API Key、没有 token 费用、没有模型超时。决策与几何全部由外部 Agent 输出，客户端只做几何编译、坐标校验、离屏渲染，最后把方块通过 `C2SCommit` 分批提交给服务端写入世界。

游戏里有两条完全分开的链路：**建造指令**给游戏内 LLM Agent 用（与本技能无关）；**外部建造手杖**（`external_build_wand`）给外部 Agent 用，左键选坐标、右键只看外部动作日志，不消耗任何游戏内模型 token。

## 快速开始

1. 启动游戏（单人世界或多人服务器均可），手持**外部建造手杖**左键两次选好范围，并**一直保持手持**。
2. 确认客户端日志有 `[建造] 本地 MCP 已监听 http://127.0.0.1:8765/mcp`。
3. 在 Agent 里配置 MCP server：

   ```json
   {"mcpServers":{"minecraft-builder":{"type":"http","url":"http://127.0.0.1:8765/mcp"}}}
   ```

4. 让 Agent 先调 `get_context`，确认 `taskId` 与 `bounds`。
5. 它建模（`draw_shape` / `place_blocks` / `remove_blocks`）、`capture` 自检，循环修正。
6. 最后 `submit_build`，服务端按 `blocksPerTick` 分批写入并归档新版本。

## 目录

| 路径 | 内容 |
| --- | --- |
| `SKILL.md` | 给外部 Agent 的完整技能说明（连接方式、五条铁律、工具、形状表达式、拍照局限） |
| `examples/01_handshake.json` | MCP 握手与 `tools/list` 的请求示例 |
| `examples/02_build_house.json` | 一轮完整建造的批量 `tools/call` 示例 |
| `README.md` | 本文件 |

参数 schema 不在本地文件里：`tools/list` 返回的就是权威定义，与代码里的 `AgentTools` + `ClientBuildHost` 同步。

## 工具一览

共 9 个：`get_context`、`request_bounds`、`read_state`、`list_materials`、`place_blocks`、`draw_shape`、`remove_blocks`、`capture`、`submit_build`。

**没有任何工具接受 `taskId`**：它们永远作用在"玩家当前手持的那根手杖"上。早期服务端版的那套工具已全部移除，只剩这 9 个。

## 配置

`config/builder-common.toml`（开发环境 `run/config/builder-common.toml`）：

```toml
[mcp]
mcpEnabled   = true        # 总开关
mcpHost      = "127.0.0.1" # 只对本机 Agent 开放
mcpPort      = 8765        # 同机多开客户端时每个实例必须不同
mcpToken     = ""          # 留空不校验；填了客户端需带 Authorization: Bearer <token>
mcpToolTimeoutSeconds = 120  # 单个工具调用最长等待

[build]
blocksPerTick = 512        # 服务端每 tick 写入上限，提交后按这个速度落地
```

同段的 `mcpBoundsTimeoutSeconds` / `mcpBoundsWaitSeconds` 是早期服务端坐标授权链路的遗留项，客户端链路已不再使用，保持默认即可。

## 选区（重点）

- **选区完全由玩家用手杖决定**：左键两次设定起终点，`get_context` 读到的 `bounds` 就是 Agent 能写的全部空间。
- **选区三种确定方式**：玩家左键两次、玩家在 GUI 手动输入坐标、或 Agent 调用 `request_bounds` 自主设定（会写回手杖并立即生效，不需要额外确认）。
- 写入超出 `bounds`、`y` 越界或方块 id 非法的条目会被逐条拒绝，回执里给出 `rejected` 计数与前 10 条样例，不会静默改世界。
- 外部手杖的选区没有体积上限；`maxSelectionVolume`（默认 10 亿格）只约束游戏内 Agent 链路。

## 多 Agent 并行

- MCP 端口开在客户端，**每个客户端实例一套独立端口与独立体素世界**。想在多块地上同时建，就多开客户端并各配不同 `mcpPort`。
- 单个客户端同一时刻只服务"当前手持手杖"对应的那一个任务；换任务靠换手杖。
- 多人服务器上只有任务所有者能提交方块，非所有者提交会被拒绝并记日志。

## 注意

- **建模只在客户端**：`draw_shape` / `place_blocks` / `capture` 都只改本地体素，每轮回执带 `localOnly:true`，必须调用 `submit_build` 才会写进世界。
- `submit_build` 之后本地世界**保留**，服务端是覆盖写入，重复提交同一批安全，也可以继续加建后再提交。
- `capture` 在 MCP 下只回文本（机位、`localReport`、张数），不返回图片字节；PNG 落盘在 `<客户端目录>/builder-captures/<taskId 前 8 位>/`，环境能读本地文件时可自行查看。
- 照片只渲染本次建造的方块；天空渐变与绿色棋盘格草地是占位背景，不代表真实地形。
- 客户端宿主每 tick 处理 MCP 请求队列，游戏卡住时工具调用会超时（默认 120 秒）。
