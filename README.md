# Minecraft Automation MCP

游戏自动化仅通过 MCP 工具调用本机 Forge 桥接模组，直接读写正在运行的 Minecraft 单人世界状态。角色移动、视角和交互由玩家自行控制。

支持 AE2 自动合成、GTCEu 机器操作、物品和流体转运，以及世界状态和 FTB 任务进度查询。

## 项目结构

- `mcp/`：Node.js MCP 服务，通过标准输入输出与客户端通信。
- `forge-bridge/`：Forge 桥接模组，在游戏服务端线程处理请求。

## 安装与连接

运行 MCP 服务需要 Node.js 20 或更新版本。从源码构建桥接模组时，Gradle 工具链使用 JDK 17。使用 AE2、GTCEu 或 FTB Quests 功能时，需要在实例中安装对应模组。当前使用的是 GTCEu 1.4.4。

1. 在项目根目录运行以下 PowerShell 命令。

   ```powershell
   npm ci --prefix .\mcp
   Push-Location .\forge-bridge
   .\gradlew.bat build
   Pop-Location
   ```

2. 将 `forge-bridge/build/libs/minecraft-automation-mcp-bridge-0.1.0.jar` 放入目标 Forge 实例的 `mods/`，用任意启动器启动并进入单人存档。升级时先移除旧版桥接 JAR。
3. 将以下 MCP 配置添加到客户端。以 Codex 的 `config.toml` 为例，更新已有桥接条目，并将脚本路径改为本机的绝对路径。

   ```toml
   [mcp_servers.minecraft-automation-mcp]
   command = "node"
   args = ["D:/path/to/minecraft-automation-mcp/mcp/server.mjs"]
   ```

## 选择实例与操作存档

MCP 服务自动发现同一台机器、同一系统用户下运行桥接模组的游戏实例。只有一个实例时，可以省略 `instanceId`。多个实例同时运行时，先用 `list_game_instances` 查找目标实例，并在每次游戏操作中传入 `instanceId`。游戏重启后重新查询实例 ID。

1. 开始游戏任务时调用 `get_game_status`，确认 `connected` 和 `worldLoaded` 均为 `true`，并核对 `worldFolder`。实例列表为空时，检查桥接 JAR 是否放在目标实例的 `mods/` 下，以及 Minecraft 是否已启动。`worldLoaded` 为 `false` 时，先进入目标单人存档。
2. 操作方块、容器或机器前，核对维度、坐标和目标状态。转移需要来源状态校验值时，先通过对应查询工具读取最新值。
3. 写入后通过 MCP 读回验证。写入超时也先查询实际状态，再决定是否重试。

实例注册信息和鉴权令牌由桥接模组在本机管理。鉴权令牌应留在本机。

## AI 作弊权限

桥接模组启动时会在实例内创建缺失的 `config/minecraft-automation-mcp-permissions.properties`，两项测试权限默认关闭。

```properties
allowTestFixtures=false
allowTestBlockWrites=false
```

- `allowTestFixtures` 控制箱子、工作台备料和树场测试夹具。这些测试可能生成物品或临时方块。
- `allowTestBlockWrites` 控制 `set_test_block` 对指定测试坐标的直接修改。

保存配置后，下一次作弊相关请求会按新权限判断，无需重启 Minecraft。权限按实例管理，测试仍需匹配已配置的存档和坐标。调用 `get_game_status` 可通过 `cheatPermissions` 查看当前生效的权限。

## Prism 后台启动

按项目约定，Prism Launcher 和 Minecraft 实例默认在后台启动，保留当前前台窗口的焦点和鼠标控制。要启用桥接模组的后台窗口设置，在实例游戏目录的 `config/` 下创建空文件 `minecraft-automation-mcp-background-start`，或将已有的后台启动标记改为此文件名，然后启动实例。Windows 上，Minecraft 以无边框方式填满显示器工作区。后台自动进入单人存档时，游戏继续运行，鼠标捕获等到第一次点击游戏画面才启用。

## MCP 工具

工具参数见 [mcp/server.mjs](mcp/server.mjs)。

### 实例与世界

| 任务 | MCP 工具 |
| --- | --- |
| 发现运行中的实例，查询存档和玩家状态 | `list_game_instances`、`get_game_status` |
| 读取方块状态 | `get_block` |
| 设置流浪商人生成，或将指定主世界区块的生物群系改为平原 | `set_wandering_trader_spawning`、`set_overworld_chunks_to_plains` |
| 从 ME 取用方块并放置到指定位置 | `place_block_from_me` |

### 库存与搬运

| 任务 | MCP 工具 |
| --- | --- |
| 查询容器或机器的物品槽，在 Forge 容器之间搬运物品 | `get_container`、`transfer_item` |
| 查询 Forge 流体容器并转移流体 | `get_tank`、`transfer_fluid` |
| 查询 ME 物品，在 ME 与 Forge 容器之间搬运物品 | `get_me_item`、`transfer_me_item` |
| 批量查询 ME 库存与机器，或给指定机器收料和补料 | `get_me_machine_snapshot`、`transfer_me_machine_batch` |
| 查询 ME 流体、转移流体或用 ME 内的空桶装液 | `get_me_fluid`、`transfer_me_fluid`、`fill_me_fluid_bucket` |
| 查询主背包，在主背包和 ME 之间搬运物品 | `get_host_inventory`、`transfer_host_me_item` |

`get_me_machine_snapshot` 一次最多查 128 种 ME 物品和 32 个 Forge 物品端点。批量搬料按给定顺序执行最多 32 步。机器和容器只要开放 Forge 物品接口，就用同一套工具。查询和转运使用同一侧的 `side` 与槽号，机器自己的进出规则仍会生效。`transfer_me_item` 使用 `container`，旧参数 `chest` 仍可用。

单台设备用 `get_container`。已有的原生状态（例如 GT 多方块成型状态、熔炉燃烧状态、AE2 压印进度）放在 `machine` 字段。给特定输入槽补料时可指定 `targetSlot`。流体收发用 `get_tank`、`transfer_fluid` 或 `transfer_me_fluid`。缺水保护仅适用于低压蒸汽固体燃料锅炉（`gtceu:lp_steam_solid_boiler`），单次和批量转运都会在写入前检查。

批量写入需指定存档目录名和新的请求 ID。写入超时后先查库存和槽位，再用原 ID 和相同参数取回回执。回执只保留在当前存档会话中，最多 128 个。

ME 工具通过 AE2 节点定位网络。已有 `drive` 参数可填写线缆或其他暴露有效节点的方块坐标。通过顶层坐标查询 ME 的工具也可直接填写该节点。若同一坐标的不同面暴露不同网络，需指定 `side`。节点与网络仍需在线并供电。

`get_tank` 返回每个可见流体槽的容量和内容，以及 `fluids` 中按流体种类和数据汇总的来源校验值。容器只在特定面开放流体接口时，在查询和转运中使用同一个 `side`。具体从哪个槽取出或填入由容器的 Forge 流体接口决定。

### AE2 与工作台合成

| 任务 | MCP 工具 |
| --- | --- |
| 查找工作台配方，从 ME 取料单次或批量合成 | `find_workbench_recipes`、`get_workbench_recipe`、`craft_from_me`、`craft_batch_from_me` |
| 编码并安装合成与处理样板 | `encode_me_crafting_pattern`、`encode_me_processing_pattern` |
| 查询可合成物品，提交、查询或取消 AE2 自动合成订单 | `get_me_craftable`、`order_me_craft`、`get_me_craft_order`、`cancel_me_craft_order` |

AE2 订单默认由 AE2 发料，机器产物经配置好的回料路径回到 ME。用 `order_me_craft` 下单后，通过 `get_me_craft_order` 查看进度。手动转运工具用于独立搬运和调试。订单等待产物时，检查机器产出和回料路径。手动送入 ME 的物品或流体也可能由等待中的 AE2 订单接收。`moved` 是实际转运量，`meAfter` 是调用结束时看到的普通库存。若只接收部分，剩余数量留在来源。

批量工作台合成最多重复同一配方 64 次。批量调用的请求 ID 和超时处理见上方库存与搬运。

两种样板写入工具共用 AE2 样板容器接口，目标必须处于同一网络。给线缆上的样板供应器写入时可用 `provider.side` 选择部件。

### GTCEu 机器与多方块

| 任务 | MCP 工具 |
| --- | --- |
| 按物品或流体产物查找 GT 配方 | `find_gt_recipes` |
| 配置机器、安装盖板和维修维护仓 | `configure_gt_machine`、`install_gt_cover_from_me`、`service_gt_maintenance` |
| 读取多方块图纸和材料、按图纸建造并检查现场方块 | `get_gt_multiblock_layout`、`build_gt_multiblock_from_me`、`check_multiblock_blocks` |

`get_gt_multiblock_layout` 接受 GT 机器注册 ID、控制器方块 ID 或已放置控制器的坐标。工具读取 GTCEu 的结构预览，返回材料清单、相对坐标和完整方块 ID。读取和建造可以使用同一个结构 ID、从 0 开始的 `previewIndex` 和 `parts`，用料与指定预览一致。建造点需要空置且已加载，所需方块需要有可从 ME 扣除的物品。放置后由 GTCEu 校验结构。

`check_multiblock_blocks` 每次按一个维度检查最多 1,024 个绝对坐标，返回匹配数量、缺块、错块和不可读取的位置。使用相对布局核对现场时，先按控制器朝向换算坐标，再分批传入。

`install_gt_cover_from_me` 使用盖板物品 ID、ME 来源校验值和安装面，按 GTCEu 的兼容规则检查和安装。安装面需要空置。

### FTB 任务

| 任务 | MCP 工具 |
| --- | --- |
| 查询章节、任务进度和奖励领取状态 | `list_ftb_quest_chapters`、`list_ftb_quests`、`get_ftb_quest` |
| 勾选确认任务、从 ME 提交物品或领取奖励 | `check_ftb_task`、`submit_ftb_item_from_me`、`claim_ftb_reward` |

### 树场与采收

| 任务 | MCP 工具 |
| --- | --- |
| 管理使用 Forge 物品容器供料和回收的橡木或桦木树场 | `start_tree_farm`、`get_tree_farm_status`、`stop_tree_farm` |
| 管理从 ME 供料并将产物送回 ME 的 2×2 云杉树场 | `start_me_spruce_farm`、`get_me_spruce_farm_status`、`stop_me_spruce_farm` |
| 定位并检查巨型云杉，单次采收并补种 | `find_spruce_and_me`、`inspect_giant_spruce`、`harvest_giant_spruce_to_me` |
| 采收天然橡胶树主干，将掉落物送入 ME | `harvest_rubber_trunk_to_me` |

树场的树苗供料和原木输出均可使用 Forge 物品容器，分侧访问时可指定 `supplySide` 和 `outputSide`。

### 测试夹具

| 任务 | MCP 工具 |
| --- | --- |
| 验证指定测试坐标的方块写入或树场采收流程 | `set_test_block`、`verify_tree_farm` |

仅在明确的测试任务中使用，权限与测试目标见上方 AI 作弊权限。

## 开发检查

修改 MCP 工具时，同步核对 `mcp/server.mjs` 中的参数与 Forge 桥接处理逻辑。修改 `mcp/server.mjs` 后，在项目根目录运行 `node --check mcp/server.mjs`。修改 Forge 代码后，在 `forge-bridge/` 下运行 `.\gradlew.bat build`。
