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
 * @param reader   从玩家读取该槽位物品的函数
 * @param canonical 该槽位的规范名（用于日志/展示，可选）
 */
public record SlotSelector(Function<Player, ItemStack> reader, String canonical) {

    /**
     * 解析 selector 字符串。
     *
     * @param selector 选择器（大小写不敏感），null/空视为默认手持格
     * @return 解析成功返回选择器，无法识别返回空
     */
    public static Optional<SlotSelector> parse(final String selector) {
        if (selector == null || selector.isEmpty()) {
            return Optional.of(fromEquipment(EquipmentSlotType.DEFAULT));
        }

        // 快捷栏数字格 1..9
        if (selector.length() == 1) {
            final char c = selector.charAt(0);
            if (c >= '1' && c <= '9') {
                final int index = c - '1'; // '1' -> 索引 0
                return Optional.of(new SlotSelector(
                        player -> player.getInventory().getItem(index), "hotbar" + c));
            }
        }

        // 装备/手持关键字
        return EquipmentSlotType.fromKeyword(selector).map(SlotSelector::fromEquipment);
    }

    private static SlotSelector fromEquipment(final EquipmentSlotType type) {
        return new SlotSelector(type::read, type.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** 读取该槽位物品，可能为 null / AIR。 */
    public ItemStack read(final Player player) {
        return reader.apply(player);
    }
}