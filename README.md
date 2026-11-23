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

- `allowTestFixtures` 控制能凭空生成物品、流体、方块或电力的测试夹具，包括树场测试。
- `allowTestBlockWrites` 控制 `set_test_block` 对指定测试坐标的直接修改。

保存配置后，下一次作弊相关请求会按新权限判断，无需重启 Minecraft。关闭 `allowTestFixtures` 会停止正在运行的测试供电。权限按实例管理，测试仍需匹配已配置的存档和坐标。调用 `get_game_status` 可通过 `cheatPermissions` 查看当前生效的权限。日常查询、搬运、供料和合成照常可用。

## Prism 后台启动

按项目约定，Prism Launcher 和 Minecraft 实例默认在后台启动，保留当前前台窗口的焦点和鼠标控制。要启用桥接模组的后台窗口设置，在实例游戏目录的 `config/` 下创建空文件 `minecraft-automation-mcp-background-start`，或将已有的后台启动标记改为此文件名，然后启动实例。模组会请求 Minecraft 窗口以最大化且不获取焦点的方式打开，并关闭失焦暂停。Windows 上还会尝试将游戏窗口切换到英文直接输入。

## 已实现功能

| 功能 | MCP 工具 |
| --- | --- |
| 发现运行中的游戏实例，查看实例 ID 和存档状态 | `list_game_instances` |
| 查询存档、玩家位置和方块状态 | `get_game_status`、`get_block` |
| 查询容器库存并转移物品 | `get_container`、`transfer_item` |
| 查询储罐状态并转移流体 | `get_tank`、`transfer_fluid` |
| 查询 ME 库存并存取物品与流体 | `get_me_item`、`transfer_me_item`、`get_me_fluid`、`transfer_me_fluid` |
| 查询、配置和维护 GTCEu 机器 | `get_gt_machine`、`configure_gt_machine`、`service_gt_maintenance` |
| 手动给机器供料、收取产物和转运物品 | `transfer_gt_me_item`、`transfer_gt_me_fluid`、`transfer_gt_machine_item` |
| 查询多方块结构的布局和材料，建造并验证成型 | `get_gt_multiblock_layout`、`build_gt_multiblock_from_me`、`get_gt_coke_oven_layout`、`build_gt_coke_oven_from_me` |
| 查找工作台配方并从 ME 取料合成 | `find_workbench_recipes`、`get_workbench_recipe`、`craft_from_me` |
| 编码并安装合成与处理样板 | `encode_me_crafting_pattern`、`encode_me_processing_pattern` |
| 查询可合成物品并管理 AE2 自动合成订单 | `get_me_craftable`、`order_me_craft`、`get_me_craft_order`、`cancel_me_craft_order` |
| 管理循环采收任务，或定位树木并单次采收补种 | `start_tree_farm`、`get_tree_farm_status`、`stop_tree_farm`、`find_spruce_and_me`、`inspect_giant_spruce`、`harvest_giant_spruce_to_me` |
| 查询 FTB 任务、进度和奖励领取状态 | `list_ftb_quest_chapters`、`list_ftb_quests`、`get_ftb_quest` |
| 验证方块写入和树木采收流程 | `set_test_block`、`verify_tree_farm` |

AE2 订单默认由 AE2 发料，机器产物经配置好的回料路径回到 ME。用 `order_me_craft` 下单后，通过 `get_me_craft_order` 查看进度。手动转运工具用于独立搬运和调试。订单等待产物时，检查机器产出和回料路径。手动送入 ME 的物品或流体也可能由等待中的 AE2 订单接收。`moved` 是实际转运量，`meAfter` 是调用结束时看到的普通库存。若只接收部分，剩余数量留在来源。

工具参数见 [mcp/server.mjs](mcp/server.mjs)。

## 开发检查

修改 MCP 工具时，同步核对 `mcp/server.mjs` 中的参数与 Forge 桥接处理逻辑。修改 `mcp/server.mjs` 后，在项目根目录运行 `node --check mcp/server.mjs`。修改 Forge 代码后，在 `forge-bridge/` 下运行 `.\gradlew.bat build`。
