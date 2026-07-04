package com.bcshow.showitem.render;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 背包镜像服务：捕获「服务端最终发给玩家客户端的物品」，也就是玩家在游戏里眼睛
 * 真正看到的那份——已经过所有插件（尤其附魔插件）在发包时动态注入的显示层。
 *
 * <p><b>为什么需要它</b>：{@code player.getInventory().getItem()} 只返回物品<b>本体 NBT</b>，
 * 不含附魔/自定义插件在「打包发给客户端」那一刻注入的显示 lore。若 {@code %i} 从背包直接取物，
 * 接收端拿到的是原始 NBT，附魔按客户端语言渲染成英文、自定义附魔直接丢失，与游戏内不一致。</p>
 *
 * <p><b>原理</b>：以 {@link ListenerPriority#MONITOR}（最低优先级，确保晚于附魔插件）拦截
 * 发给玩家的两类出站包，把注入后的物品按窗口槽位镜像下来：
 * <pre>
 *   WINDOW_ITEMS(containerId=0)  整窗刷新 → 覆盖整份镜像
 *   SET_SLOT(containerId=0/-2)   单槽更新 → 更新单个镜像槽
 * </pre>
 * 玩家在聊天框打字时容器 GUI 必然关闭，当前窗口即玩家自身背包（窗口 0），因此只需镜像窗口 0。
 * 玩家退出时清理其镜像。</p>
 *
 * <p>取物时 {@code %i} 优先查镜像（所见即所得），未命中回退 {@code getInventory().getItem()}，
 * 因此该服务是纯增强、可缺省，不改变无附魔插件时的行为。</p>
 */
public final class InventoryMirrorService extends PacketAdapter implements Listener {

    /** 玩家自身背包窗口容器 id。 */
    private static final int PLAYER_WINDOW = 0;
    /** 部分版本用 -2 表示「直接设置玩家背包槽位」（无视当前打开的窗口）。 */
    private static final int PLAYER_WINDOW_ALT = -2;
    /** 玩家背包窗口槽位总数（0..45：合成/护甲/主背包/快捷栏/副手）。 */
    private static final int WINDOW_SLOTS = 46;

    private final Map<UUID, ItemStack[]> mirror = new ConcurrentHashMap<>();

    private InventoryMirrorService(final Plugin plugin) {
        super(plugin, ListenerPriority.MONITOR,
                PacketType.Play.Server.WINDOW_ITEMS,
                PacketType.Play.Server.SET_SLOT);
    }

    /** 注册 ProtocolLib 拦截与 Bukkit 退出清理。 */
    public static InventoryMirrorService register(final Plugin plugin) {
        final InventoryMirrorService svc = new InventoryMirrorService(plugin);
        ProtocolLibrary.getProtocolManager().addPacketListener(svc);
        plugin.getServer().getPluginManager().registerEvents(svc, plugin);
        return svc;
    }

    /** 注销拦截与清理。 */
    public void unregister() {
        final ProtocolManager pm = ProtocolLibrary.getProtocolManager();
        if (pm != null) {
            pm.removePacketListener(this);
        }
        HandlerList.unregisterAll(this);
        mirror.clear();
    }

    /**
     * 查询某窗口槽位「玩家实际看到的物品」。
     *
     * @param player     玩家
     * @param windowSlot 玩家背包窗口槽位号（见 {@code SlotSelector} 的映射，如快捷栏 = 36..44）
     * @return 镜像命中且非空返回该物品；未命中返回 null（调用方回退 getInventory().getItem()）
     */
    public ItemStack mirrored(final Player player, final int windowSlot) {
        final ItemStack[] slots = mirror.get(player.getUniqueId());
        if (slots == null || windowSlot < 0 || windowSlot >= slots.length) {
            return null;
        }
        return slots[windowSlot];
    }

    @Override
    public void onPacketSending(final PacketEvent event) {
        try {
            if (!(event.getPlayer() instanceof Player receiver)) {
                return;
            }
            if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
                captureWindowItems(receiver, event.getPacket());
            } else {
                captureSetSlot(receiver, event.getPacket());
            }
        } catch (final Exception ignored) {
            // 镜像是纯增强，任何解析异常都静默跳过，取物会自动回退到 getItem()
        }
    }

    /** 整窗刷新：仅认玩家自身背包窗口（id=0），把整份物品列表覆盖进镜像。 */
    private void captureWindowItems(final Player player, final PacketContainer packet) {
        final int windowId = packet.getIntegers().readSafely(0);
        if (windowId != PLAYER_WINDOW) {
            return;
        }
        final List<ItemStack> items = packet.getItemListModifier().readSafely(0);
        if (items == null) {
            return;
        }
        final ItemStack[] slots = new ItemStack[WINDOW_SLOTS];
        for (int i = 0; i < items.size() && i < WINDOW_SLOTS; i++) {
            slots[i] = items.get(i);
        }
        mirror.put(player.getUniqueId(), slots);
    }

    /** 单槽更新：仅认玩家自身背包窗口（id=0 或 -2），更新对应镜像槽。 */
    private void captureSetSlot(final Player player, final PacketContainer packet) {
        final int windowId = packet.getIntegers().readSafely(0);
        if (windowId != PLAYER_WINDOW && windowId != PLAYER_WINDOW_ALT) {
            return;
        }
        // SET_SLOT 槽位字段索引：不同版本可能在 getIntegers 的第 2 或第 3 个（含 stateId）
        final Integer slot = readSetSlotIndex(packet);
        if (slot == null || slot < 0 || slot >= WINDOW_SLOTS) {
            return;
        }
        final ItemStack item = packet.getItemModifier().readSafely(0);
        final ItemStack[] slots = mirror.computeIfAbsent(
                player.getUniqueId(), k -> new ItemStack[WINDOW_SLOTS]);
        slots[slot] = item;
    }

    /**
     * 读取 SET_SLOT 的槽位号。1.17.1+ 该包结构为 (windowId, stateId, slot)，槽位在整型字段索引 2；
     * 更老版本为 (windowId, slot)，槽位在索引 1。取最后一个整型字段最稳。
     */
    private Integer readSetSlotIndex(final PacketContainer packet) {
        final int size = packet.getIntegers().size();
        if (size <= 1) {
            return null;
        }
        return packet.getIntegers().readSafely(size - 1);
    }

    /** 玩家退出：清理其镜像，避免内存泄漏。 */
    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        mirror.remove(event.getPlayer().getUniqueId());
    }
}