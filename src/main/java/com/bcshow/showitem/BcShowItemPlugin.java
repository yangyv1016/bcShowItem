package com.bcshow.showitem;

import com.bcshow.showitem.cache.ItemCacheService;
import com.bcshow.showitem.chat.ChatCaptureListener;
import com.bcshow.showitem.config.PluginConfig;
import com.bcshow.showitem.render.ChatPacketInjector;
import com.bcshow.showitem.render.InventoryMirrorService;
import com.bcshow.showitem.render.ItemHoverRenderer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 插件主类：装配「捕获端」与「还原端」两条链路，并持有可热重载的配置快照。
 *
 * <pre>
 *   捕获端： ChatCaptureListener(LOWEST)  ── %i → token 写入消息
 *   还原端： ChatPacketInjector(ProtocolLib) ── 出站包 token → 物品 hover
 *   配置：   AtomicReference&lt;PluginConfig&gt;  两条链路只读、reload 时整体换新
 * </pre>
 */
public final class BcShowItemPlugin extends JavaPlugin {

    private final AtomicReference<PluginConfig> config = new AtomicReference<>();
    private ChatPacketInjector injector;
    private ItemCacheService cacheService;
    private InventoryMirrorService mirrorService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config.set(PluginConfig.from(getConfig()));

        if (getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib 未安装，物品悬停无法渲染，插件禁用。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 跨服物品缓存：走 Velocity 内置的 bungee 兼容 Forward 通道，代理端无需插件。
        cacheService = new ItemCacheService(this, config::get);
        cacheService.register();

        // 背包镜像：捕获「客户端实际收到的物品」（含附魔插件发包时注入的显示层），
        // 让 %i 所见即所得，避免附魔显示成英文/自定义附魔丢失。
        mirrorService = InventoryMirrorService.register(this);

        getServer().getPluginManager().registerEvents(
                new ChatCaptureListener(config::get, cacheService, mirrorService), this);

        final ItemHoverRenderer renderer = new ItemHoverRenderer(config::get, cacheService);
        injector = ChatPacketInjector.register(this, renderer);

        if (getServer().getPluginManager().getPlugin("VentureChat") == null) {
            getLogger().warning("未检测到 VentureChat：捕获链路仍生效，但富文本渲染依赖聊天走 ProtocolLib 系统包。");
        }
        getLogger().info("BcShowItem 已启用，触发示例: " + triggerExample(config.get()));
    }

    @Override
    public void onDisable() {
        if (injector != null) {
            injector.unregister();
            injector = null;
        }
        if (cacheService != null) {
            cacheService.unregister();
            cacheService = null;
        }
        if (mirrorService != null) {
            mirrorService.unregister();
            mirrorService = null;
        }
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("bcshowitem.reload")) {
                sender.sendMessage("§c你没有权限执行此操作。");
                return true;
            }
            reloadConfig();
            config.set(PluginConfig.from(getConfig()));
            sender.sendMessage("§aBcShowItem 配置已重载，触发示例: " + triggerExample(config.get()));
            return true;
        }
        sender.sendMessage("§e用法: /" + label + " reload");
        return true;
    }

    /** 依据当前前缀/后缀拼一个可读的触发示例，如 {@code %i} 或 {@code [i]}。 */
    private static String triggerExample(final PluginConfig cfg) {
        return cfg.triggerPrefix() + "i" + cfg.triggerSuffix();
    }
}