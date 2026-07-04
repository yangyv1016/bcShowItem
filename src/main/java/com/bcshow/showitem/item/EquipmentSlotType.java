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

    // 第 2 个参数为玩家背包「窗口槽位号」，供镜像服务按槽位回查客户端实际收到的物品。
    // 布局：5-8=护甲(头胸腿脚)，36-44=快捷栏，45=副手。HAND 是动态槽（见 windowSlot）用 -1 占位。
    HAND(PlayerInventory::getItemInMainHand, -1, "hand", "mainhand", "i", "手", "手持"),
    OFFHAND(PlayerInventory::getItemInOffHand, 45, "offhand", "副手"),
    HEAD(PlayerInventory::getHelmet, 5, "head", "helmet", "头"),
    CHEST(PlayerInventory::getChestplate, 6, "chest", "chestplate", "胸"),
    LEGS(PlayerInventory::getLeggings, 7, "legs", "leggings", "腿"),
    FEET(PlayerInventory::getBoots, 8, "feet", "boots", "脚");

    /** 快捷栏第一格在玩家背包窗口中的槽位号；快捷栏第 N 格 = HOTBAR_WINDOW_BASE + (N-1)。 */
    public static final int HOTBAR_WINDOW_BASE = 36;

    private final Function<PlayerInventory, ItemStack> extractor;
    private final int windowSlot;
    private final String[] keywords;

    EquipmentSlotType(final Function<PlayerInventory, ItemStack> extractor, final int windowSlot,
                      final String... keywords) {
        this.extractor = extractor;
        this.windowSlot = windowSlot;
        this.keywords = keywords;
    }

    /**
     * 该槽位在玩家背包窗口中的槽位号（供镜像回查）。
     *
     * <p>{@link #HAND} 是动态槽：主手随快捷栏选中格移动，窗口槽位 = {@code 36 + 当前选中格}，
     * 故需玩家上下文实时计算；其余为固定槽位。</p>
     */
    public int windowSlot(final Player player) {
        if (this == HAND) {
            return HOTBAR_WINDOW_BASE + player.getInventory().getHeldItemSlot();
        }
        return windowSlot;
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