# 维度神偷 / Steal in Dims

NeoForge 1.21.1 · MIT · mod id `stealindims` · v1.0.0

## English

Adds a raid-extraction game loop: enter a themed raid dungeon, grab loot, and get out before the clock runs out — or lose it all.

- **Entry point** — the **Advanced Warp Device**: right-click to open the raid select screen. The device's own dimension decides which raid dimension you are sent to.
- **Content is data-driven** — one raid "map" per dimension that has a configured theme (datapack files under `data/stealindims/raid_theme/`). Dimensions without a theme stay closed, so the content is whitelist-driven and cheap to extend.
- **Raid maps** — Overworld, Nether, End, Twilight Forest and Aether raid dimensions.
- **Difficulties** — Tier C / B / A / S, progressively unlocked; each tier sets the value threshold, death penalty and teleport tax.
- **Flow** — Ready Pad (ready-up countdown), Transfer Crate (Personal Extraction / Shared Storage), Extraction Portal (leave with your loot), Illager Statue Crank (opens the way to the boss), Boss Portal (S rank only).
- **Items & tools** — **Thief's Mark** and **Master Key**.
- **Boss** — the **Ascendant Evoker**.
- **Results** — a **Raid Report** at the end: submitted value vs. threshold, time, tax, equipment lost, and any difficulty unlocked.

## 中文

加入「突袭提取」玩法循环：进入主题突袭地牢，抢物资，在倒计时结束前撤离——否则全部失去。

- 入口：**高级折跃器**（Advanced Warp Device）。右键打开突袭选择界面；装置所在维度决定你被送往哪个突袭维度。
- 内容数据驱动：每个配置了主题的维度对应一张突袭「地图」（数据包文件位于 `data/stealindims/raid_theme/`）。未配置主题的维度保持关闭，内容以白名单驱动、易于扩展。
- 突袭地图：主世界、下界、末地、暮色森林、天境 突袭维度。
- 难度：C / B / A / S 四档，逐级解锁；每档决定价值阈值、死亡惩罚与传送税。
- 流程：准备台（Ready Pad，准备就绪倒计时）、转移箱（个人提取区 / 共享仓库）、撤离传送门、灾厄雕像摇柄（开启通往 BOSS 的路）、BOSS 传送门（仅 S 档可用）。
- 物品：**神偷印记**、**万能钥匙**。
- BOSS：**晋升的唤魔者**（Ascendant Evoker）。
- 结算：结束时生成 **突袭报告**——提交价值 / 阈值、用时、传送税、损失装备、是否解锁新难度。

## Dependencies / 依赖

- Required / 必需：NeoForge 21.1.x on Minecraft 1.21.1
- Optional / 可选：`geckolib`（模型 / 动画）

## Build / 构建

```sh
# Windows
$env:JAVA_HOME='<path-to-jdk21>'; .\gradlew.bat build --console=plain -q

# Linux / macOS
JAVA_HOME=<path-to-jdk21> ./gradlew build
```

Output jar: `build/libs/stealindims-1.0.0.jar`. 产物：`build/libs/stealindims-1.0.0.jar`。

## License / 许可

MIT. See `LICENSE`.
