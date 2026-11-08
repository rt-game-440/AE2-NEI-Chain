# AE2 NEI Chain（ae2neichain）

GTNH 2.7+ 的 NEI 书签「合成链自动合成」原本只支持工作台类 GUI。本附属 mod 让 **AE2 ME 合成终端** 和 **无线合成终端（WCT）** 也能使用：

- 材料从 **ME 网络**（其次是背包）提取；
- **中间产物存回 ME 网络**，下一步直接取用；**最终产物**（书签里要的物品）**放进背包**，背包满了再存 ME；
- 合成链结束后在**聊天栏输出汇总**：每种产物合成了多少、去了哪里，失败的步骤标红并写明原因（材料不足、没有权限等）；
- 合成链计算、合成树、可合成面板会把 ME 库存算作「已有物品」。

用法与工作台相同：在书签组上切到 crafting 模式，打开 ME 合成终端，按 `Shift + 合成键`（`Ctrl + Shift + 合成键` 表示计入已有库存）。

> 客户端和服务端都需要安装（单人存档天然满足）。服务端没装时客户端自动不接管，行为与原版一致。

## 原理

| 问题 | 处理 |
|---|---|
| NEE/AE2 的终端 overlay handler 没实现 `transferRecipe/canCraft/craft`，NEI 合成链直接跳过 | 打开终端时把原 handler 包装成 `ChainAwareTerminalOverlayHandler`，保留原有填格/缺料显示，补上这三个方法 |
| `AutoCraftingManager.getInventoryItems` 只读 GUI 槽位，看不到 ME | Mixin 在返回值里追加终端 ME 视图（`GuiMEMonitorable.repo`） |
| 产物需要回存 ME | 自定义包 `PacketChainCraft`：服务端主线程填格（同 AE2 NEI 填格逻辑）→ 调 AE2 `SlotCraftingTerm.craftItem` 批量合成 → 产物 `poweredInsert` 回 ME → 残留材料回 ME，tick 末尾回执 |

## 构建与部署

```bash
pwsh scripts/build.ps1
```

```bash
pwsh scripts/deploy.ps1 -Debug
```

编译期依赖直接引用测试实例 mods 目录里的 jar（`gradle.properties` 的 `gtnhInstance`），保证与实际运行版本一致。

## 自动验收

```bash
pwsh scripts/autotest.ps1
```

流程：构建 → 部署（`autoTest=true`）→ 启动实例 → 自动新建创造超平坦世界 `ae2neichain-autotest` → `/ae2neichain e2e`（搭控制器+创造能源+驱动器+合成终端，存入 64 原木，打开终端）→ 用 NEI 合成链 API 执行「原木 → 木板 → 16 木棍」→ `report final` 判定 → 退出游戏。退出码 0 表示 PASS。全程无需鼠标键盘操作。

日志：`<实例>/.minecraft/logs/ae2neichain/yyyy-MM-dd HH-mm-ss.log`（仅 `debug=true` 时写入）。

调试命令（`debug=true` 时注册，需 OP）：`/ae2neichain setup | open | e2e | report [final]`。

## 配置 `config/ae2neichain.cfg`

| 键 | 默认 | 说明 |
|---|---|---|
| `debug` | false | 调试日志与调试命令 |
| `autoTest` | false | 启动即自动验收并退出（仅 autotest 脚本临时开启） |
| `maxCraftsPerRequest` | 64 | 单次请求最大合成次数 |
| `replyTimeoutMillis` | 5000 | 客户端等待回执超时 |

## 已测试环境

GTNH 2.9.0（NEI 2.8.171-GTNH、AE2 rv3-beta-1095-GTNH、NotEnoughEnergistics 1.7.50、WirelessCraftingTerminal 1.12.17）。无线合成终端与 ME 合成终端走同一代码路径，但尚未做游戏内端到端测试。

## 许可证

[MIT](LICENSE)
