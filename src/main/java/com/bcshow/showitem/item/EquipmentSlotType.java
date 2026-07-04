package com.bcshow.showitem.item;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * 装备槽位类型：把「关键字」映射到玩家身上对应槽位的物品读取逻辑。
 *
 * <p>注意 {@link #HAND} 用的是 {@code getItemInMainHand()}，它返回的正是玩家
 * <b>当前手持格</b>的物品（主手槽随快捷栏选中格移动），因此 {@code %i} 天然等价于
 * 「当前手持格」，无需额外分支。快捷栏 1-9 的按格读取见 {@link SlotSelector}。</p>
 */
public enum EquipmentSlotType {

    HAND(PlayerInventory::getItemInMainHand, "hand", "mainhand", "i", "手", "手持"),
    OFFHAND(PlayerInventory::getItemInOffHand, "offhand", "副手"),
    HEAD(PlayerInventory::getHelmet, "head", "helmet", "头"),
    CHEST(PlayerInventory::getChestplate, "chest", "chestplate", "胸"),
    LEGS(PlayerInventory::getLeggings, "legs", "leggings", "腿"),
    FEET(PlayerInventory::getBoots, "feet", "boots", "脚");

    private final Function<PlayerInventory, ItemStack> extractor;
    private final String[] keywords;

    EquipmentSlotType(final Function<PlayerInventory, ItemStack> extractor, final String... keywords) {
        this.extractor = extractor;
        this.keywords = keywords;
    }

    /** 默认槽位：当前手持格。 */
    public static final EquipmentSlotType DEFAULT = HAND;

    /**
     * 从关键字解析装备槽位类型（不含数字格）。
     *
     * @param keyword 关键字（大小写不敏感），null/空返回默认手持格
     * @return 匹配的槽位，无匹配返回空
     */
    public static Optional<EquipmentSlotType> fromKeyword(final String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return Optional.of(DEFAULT);
        }
        final String lower = keyword.toLowerCase(Locale.ROOT);
        for (final EquipmentSlotType type : values()) {
            for (final String kw : type.keywords) {
                if (kw.equals(lower)) {
                    return Optional.of(type);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 读取玩家该槽位的物品。
     *
     * @param player 玩家
     * @return 物品，可能为 null 或 AIR
     */
    public ItemStack read(final Player player) {
        return extractor.apply(player.getInventory());
    }
}