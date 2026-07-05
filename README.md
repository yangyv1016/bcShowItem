<div align="center">
  <img src="logo1024.jpg" alt="Astra Tale Studio" width="200">
  <p><strong>本插件由 Astra Tale Studio 使用 Claude 辅助开发</strong></p>
</div>

---

# BcShowItem — 聊天内展示背包物品（Paper 1.21）

在聊天里输入一个触发符（默认 `%i`），插件会把它替换成彩色的 `[物品名]`，鼠标悬停即可看到与游戏内完全一致的物品信息界面。为 VentureChat 多服聊天设计，跨服安全、优雅降级。

## 使用方式

在聊天框里输入触发符即可，无需任何指令：

| 输入 | 效果 |
|---|---|
| `%i` | 展示当前主手（手持）物品 |
| `%1` ~ `%9` | 展示快捷栏第 1~9 格 |
| `%i:head` / `%i:头` | 展示头盔（护甲槽） |
| `%i:chest` / `%i:胸` | 展示胸甲 |
| `%i:legs` / `%i:腿` | 展示护腿 |
| `%i:feet` / `%i:脚` | 展示鞋子 |
| `%i:offhand` / `%i:副手` | 展示副手物品 |

一句话可以塞多个触发符，例如：`看我这套 %i:head %i:chest %i 输出爆炸`。

触发符的写法可在 `config.yml` 里改前缀/后缀，覆盖不同书写习惯：

```
trigger-prefix: "%"  trigger-suffix: ""   ->  %i   %3        （前缀式，默认推荐）
trigger-prefix: "["  trigger-suffix: "]"  ->  [i]  [3]       （包裹式）
trigger-prefix: "{"  trigger-suffix: "}"  ->  {i}  {3}
```

空手打 `%i` 时的处理方式由 `empty-slot-action` 决定：

| 取值 | 行为 |
|---|---|
| `keep`（默认） | 原样保留 `%i` 文本，当普通聊天内容，最不打扰 |
| `text` | 替换为 `empty-slot-text` 占位文本（如 `[空]`） |
| `remove` | 直接删掉该触发符 |

## 使用环境

| 项目 | 要求 |
|---|---|
| 服务端 | Paper 1.21.11（api-version 1.21） |
| Java | 21+ |
| 硬依赖 | [ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/) 5.x（拦截聊天出站包） |
| 软依赖 | [VentureChat](https://www.spigotmc.org/resources/venturechat.62549/)（多服聊天，可选） |

> ⚠️ 建议**所有子服都装本插件**：接收端装了才能显示悬停界面。没装的子服不会乱码，只会显示干净的 `[物品名]` 纯文本（见下方原理）。

安装：把 `BcShowItem-<version>.jar` 丢进 `plugins/`，确保 ProtocolLib 已安装，重启即可。

## 指令与权限

| 指令 | 说明 |
|---|---|
| `/bcshowitem reload`（别名 `/bcsi`） | 重载配置 |

| 权限节点 | 默认 | 说明 |
|---|---|---|
| `bcshowitem.use` | true | 允许在聊天中使用触发符 |
| `bcshowitem.reload` | op | 允许重载配置 |

---

## 运行原理

核心难点：既要让接收端看到富交互的物品悬停，又要保证**没装插件的子服不出现乱码**。做法是把物品数据用「零宽不可见字符」随消息一起传输，配合出站聊天包的二次渲染。

### 数据流向

```
发送端玩家输入 "%i"
        │
        ▼
┌───────────────────────────────────────────────┐
│ ChatCaptureListener  (AsyncPlayerChatEvent      │
│                       priority = LOWEST)        │
│  抢在 VentureChat(HIGHEST) 之前改写 message：     │
│  "%i" ──TriggerExpander──▶ "[钻石剑]" + 零宽数据   │
└───────────────────────────────────────────────┘
        │  message 内已内嵌 token
        ▼
   VentureChat 照常格式化、转 Mojang JSON、跨服广播
   （它只把 token 当普通文本，一个 JSON 文本节点原样带走）
        │
        ▼
┌───────────────────────────────────────────────┐
│ ChatPacketInjector  (ProtocolLib 拦截            │
│                      SYSTEM_CHAT 出站包)          │
│  JSON ─▶ Adventure Component                    │
│  Component.replaceText(正则) 命中 token：         │
│    还原为彩色 [物品名] + ItemStack 悬停 hover      │
└───────────────────────────────────────────────┘
        │
        ▼
   接收端玩家看到富交互的 [物品名]（悬停显示完整物品）
```

### token 结构（零宽隐写）

token 不含任何 `§` 颜色码，保证 VentureChat 把它保留在**同一个** JSON 文本节点里：

```
\u2060  [物品名]  \u2061  «零宽编码的物品字节»  \u2062
 ▲       ▲          ▲            ▲                ▲
哨兵1   可见回退名   哨兵2    U+200B/200C/200D/FEFF   哨兵3
                          四进制编码的 ItemStack 数据
```

- **装了插件的子服**：`ChatPacketInjector` 正则命中哨兵包裹的区间，解码零宽数据还原 `ItemStack`，渲染成彩色名 + 悬停界面。
- **没装插件的子服**：哨兵与零宽字符在客户端**不可见**，玩家只看到干净的 `[物品名]` 纯文本 —— 不会出现 Base64 乱码。这是硬性设计目标。

### 为什么这样分层

| 关注点 | 归属模块 | 理由 |
|---|---|---|
| 触发符 → token 改写 | `chat/TriggerExpander`（纯函数） | 无副作用，易测试，与 Bukkit 事件解耦 |
| 事件时序抢占 | `chat/ChatCaptureListener` | LOWEST 优先级确保早于 VC 的 HIGHEST |
| token 编解码 | `token/ItemTokenCodec` + `token/ZeroWidthCodec` | 隐写格式独立演进，收发两端共用 |
| 物品 → 展示文本 | `item/ItemDisplay` | 展开回退名与渲染器共用同一套显示逻辑 |
| 出站包渲染 | `render/ChatPacketInjector` + `render/ItemHoverRenderer` | 唯一触碰 ProtocolLib 的地方 |
| 槽位解析 | `item/SlotSelector` + `item/EquipmentSlotType` | selector 字符串 → `Function<Player, ItemStack>` |

插件**不依赖任何 VentureChat 的类**，只借助其「取消原事件 + 转 JSON 广播」的既定行为；VentureChat 硬依赖 ProtocolLib，因此 ProtocolLib 必然存在。

## 从源码构建

需要 JDK 21+ 与 Maven：

```bash
mvn clean package
# 输出: target/BcShowItem-1.0.0.jar
```
