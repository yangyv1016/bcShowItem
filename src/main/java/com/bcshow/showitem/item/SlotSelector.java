package com.bcshow.showitem.item;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.function.Function;

/**
 * 统一的槽位选择器：把「selector 字符串」解析为「从玩家读取物品的函数」。
 *
 * <p>把两类槽位差异（装备槽 vs 快捷栏数字格）收敛成同一个
 * {@code Function<Player, ItemStack>}，调用方无需关心具体来源。</p>
 *
 * <p>支持的 selector：
 * <ul>
 *   <li>{@code i / hand / 手} 等 —— 当前手持格（见 {@link EquipmentSlotType}）</li>
 *   <li>{@code offhand / head / chest / legs / feet} 及中文别名 —— 对应装备槽</li>
 *   <li>{@code 1..9} —— 快捷栏第 N 格（内部索引 0..8）</li>
 * </ul></p>
 *
 * @param reader            从玩家读取该槽位物品本体的函数（保底回退用）
 * @param windowSlotResolver 该槽位在玩家背包窗口中的槽位号解析器（供镜像回查所见即所得的物品）
 * @param canonical         该槽位的规范名（用于日志/展示，可选）
 */
public record SlotSelector(Function<Player, ItemStack> reader,
                           Function<Player, Integer> windowSlotResolver, String canonical) {

    /**
     * 解析 selector 字符串。
     *
     * @param selector 选择器（大小写不敏感），null/空视为默认手持格
     * @return 解析成功返回选择器，无法识别返回空
     */
    public static Optional<SlotSelector> parse(final String selector,
                                               final Function<Player, Integer> heldSlotProvider) {
        if (selector == null || selector.isEmpty()) {
            return Optional.of(fromEquipment(EquipmentSlotType.DEFAULT, heldSlotProvider));
        }

        // 快捷栏数字格 1..9
        if (selector.length() == 1) {
            final char c = selector.charAt(0);
            if (c >= '1' && c <= '9') {
                final int index = c - '1'; // '1' -> 索引 0
                final int windowSlot = EquipmentSlotType.HOTBAR_WINDOW_BASE + index;
                return Optional.of(new SlotSelector(
                        player -> player.getInventory().getItem(index),
                        player -> windowSlot, "hotbar" + c));
            }
        }

        // 装备/手持关键字
        return EquipmentSlotType.fromKeyword(selector)
                .map(type -> fromEquipment(type, heldSlotProvider));
    }

    /**
     * 由 selector 与「实时手持格来源」构造装备/手持选择器。
     *
     * <p>{@link EquipmentSlotType#HAND} 是<b>动态槽</b>：主手随快捷栏选中格移动。用注入的
     * {@code heldSlotProvider}（异步安全的实时手持格，见 {@code InventoryMirrorService}）
     * 现算窗口槽位与取物索引，替代由主线程延迟更新的 {@code getHeldItemSlot()}，
     * 从而消除「换格后立刻发言展示错格」的异步竞态。其余装备槽为固定槽，走静态映射。</p>
     */
    private static SlotSelector fromEquipment(final EquipmentSlotType type,
                                              final Function<Player, Integer> heldSlotProvider) {
        if (type == EquipmentSlotType.HAND) {
            return new SlotSelector(
                    player -> player.getInventory().getItem(heldSlotProvider.apply(player)),
                    player -> EquipmentSlotType.HOTBAR_WINDOW_BASE + heldSlotProvider.apply(player),
                    "hand");
        }
        return new SlotSelector(type::read, type::windowSlot,
                type.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** 读取该槽位物品本体（保底），可能为 null / AIR。 */
    public ItemStack read(final Player player) {
        return reader.apply(player);
    }

    /** 该槽位在玩家背包窗口中的槽位号（供镜像回查所见即所得的物品）。 */
    public int windowSlot(final Player player) {
        return windowSlotResolver.apply(player);
    }
}