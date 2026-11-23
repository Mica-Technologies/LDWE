/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.forge;

import org.apache.logging.log4j.Logger;

import com.google.common.base.Joiner;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.Tags;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldVector;
import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.event.platform.PlatformReadyEvent;
import com.sk89q.worldedit.extension.platform.Platform;
import com.sk89q.worldedit.forge.net.LeftClickAirEventMessage;
import com.sk89q.worldedit.internal.LocalWorldAdapter;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickEmpty;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.Mod.Instance;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.Event.Result;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * The Forge implementation of WorldEdit.
 */
@Mod(modid = ForgeWorldEdit.MOD_ID, name = "WorldEdit", version = Tags.VERSION, acceptableRemoteVersions = "*")
public class ForgeWorldEdit {

    public static Logger logger;
    public static final String MOD_ID = "worldedit";
    public static final String CUI_PLUGIN_CHANNEL = "WECUI";

    private ForgePermissionsProvider provider;

    @Instance(MOD_ID)
    public static ForgeWorldEdit inst;

    @SidedProxy(serverSide = "com.sk89q.worldedit.forge.CommonProxy", clientSide = "com.sk89q.worldedit.forge.ClientProxy")
    public static CommonProxy proxy;

    private ForgePlatform platform;
    private ForgeConfiguration config;
    private File workingDir;

    /**
     * The last right click handled for each online player, used to recognise
     * the events the client sends again for that same click. An entry is
     * replaced by the player's next click and removed when they log out.
     */
    private final Map<UUID, RightClick> lastRightClicks = new HashMap<>();

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        // Setup working directory
        workingDir = new File(event.getModConfigurationDirectory() + File.separator + "worldedit");
        workingDir.mkdir();

        config = new ForgeConfiguration(this);
        config.load();

        MinecraftForge.EVENT_BUS.register(ThreadSafeCache.getInstance());
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        WECUIPacketHandler.init();
        InternalPacketHandler.init();
        proxy.registerHandlers();
    }

    @EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        logger.info("WorldEdit for Forge (version " + getInternalVersion() + ") is loaded");
    }

    @EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        if (this.platform != null) {
            logger.warn("FMLServerStartingEvent occurred when FMLServerStoppingEvent hasn't");
            WorldEdit.getInstance().getPlatformManager().unregister(platform);
        }

        this.platform = new ForgePlatform(this);

        WorldEdit.getInstance().getPlatformManager().register(platform);

        if (Loader.isModLoaded("sponge")) {
            this.provider = new ForgePermissionsProvider.SpongePermissionsProvider();
        } else {
            this.provider = new ForgePermissionsProvider.VanillaPermissionsProvider(platform);
        }
    }

    @EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        WorldEdit.getInstance().getPlatformManager().unregister(platform);
        // The tick counter starts again from zero on the next server.
        lastRightClicks.clear();
    }

    @EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        WorldEdit.getInstance().getEventBus().post(new PlatformReadyEvent());
    }

    @SubscribeEvent
    public void onCommandEvent(CommandEvent event) {
        if ((event.getSender() instanceof EntityPlayerMP)) {
            if (((EntityPlayerMP) event.getSender()).world.isRemote) return;
            String[] split = new String[event.getParameters().length + 1];
            System.arraycopy(event.getParameters(), 0, split, 1, event.getParameters().length);
            split[0] = event.getCommand().getName();
            com.sk89q.worldedit.event.platform.CommandEvent weEvent =
                    new com.sk89q.worldedit.event.platform.CommandEvent(wrap((EntityPlayerMP) event.getSender()), Joiner.on(" ").join(split));
            WorldEdit.getInstance().getEventBus().post(weEvent);
        }
    }

    @SubscribeEvent
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (platform == null) {
            return;
        }

        if (!platform.isHookingEvents())
            return; // We have to be told to catch these events

        if (event.getWorld().isRemote && event instanceof LeftClickEmpty) {
            // catch LCE, pass it to server
            InternalPacketHandler.CHANNEL.sendToServer(new LeftClickAirEventMessage());
            return;
        }
        
        boolean isLeftDeny = event instanceof PlayerInteractEvent.LeftClickBlock
                && ((PlayerInteractEvent.LeftClickBlock) event)
                        .getUseItem() == Result.DENY;
        boolean isRightDeny =
                event instanceof PlayerInteractEvent.RightClickBlock
                        && ((PlayerInteractEvent.RightClickBlock) event)
                                .getUseItem() == Result.DENY;
        if (isLeftDeny || isRightDeny || event.getEntity().world.isRemote) {
            return;
        }

        // One right click makes the vanilla client try every hand in turn
        // (Minecraft.rightClickMouse): for each hand it sends a use-item-on-block
        // packet when aiming at a block, then a use-item packet when that hand
        // holds something, and it only stops early when the client itself sees
        // the click succeed, which it does not for WorldEdit tools. The server
        // turns each packet into its own Forge event, so one click arrives as
        // RightClickBlock(main), RightClickItem(main), RightClickBlock(off) and,
        // with an item in the off hand, RightClickItem(off). WorldEdit always
        // takes the tool from the main hand, so only the first of these is a
        // real click and the rest would run the same tool again.
        boolean isRightClickBlock = event instanceof PlayerInteractEvent.RightClickBlock;
        boolean isRightClickItem = event instanceof PlayerInteractEvent.RightClickItem;
        EntityPlayerMP playerMP = (EntityPlayerMP) event.getEntityPlayer();
        int tick = playerMP.server.getTickCounter();
        if (isRightClickBlock || isRightClickItem) {
            // The packets of one click are handled in the same server tick. A
            // click at air or at a block out of reach sends no use-item-on-block
            // packet, so its use-item event finds nothing pending and is handled.
            RightClick last = lastRightClicks.get(playerMP.getUniqueID());
            boolean sameTick = last != null && last.tick == tick;
            boolean offHand = event.getHand() == EnumHand.OFF_HAND;
            if (offHand || (isRightClickItem && sameTick && last.useItemPending)) {
                if (sameTick) {
                    if (isRightClickItem && !offHand) {
                        last.useItemPending = false;
                    }
                    // Cancel the repeat whenever WorldEdit took the first event,
                    // as the repeat would otherwise go on to vanilla and, for
                    // example, place the off-hand block or use the held item.
                    if (isRightClickBlock ? last.cancelled : last.rightClickHandled) {
                        event.setCanceled(true);
                    }
                }
                return;
            }
        }

        WorldEdit we = WorldEdit.getInstance();
        ForgePlayer player = wrap(playerMP);
        ForgeWorld world = getWorld(event.getEntityPlayer().world);

        if (event instanceof PlayerInteractEvent.LeftClickEmpty) {
            if (we.handleArmSwing(player)) {
                // this event cannot be canceled
                // event.setCanceled(true);
            }
        } else if (event instanceof PlayerInteractEvent.LeftClickBlock) {
            @SuppressWarnings("deprecation")
            WorldVector pos = new WorldVector(LocalWorldAdapter.adapt(world),
                    event.getPos().getX(), event.getPos().getY(), event.getPos().getZ());

            if (we.handleBlockLeftClick(player, pos)) {
                event.setCanceled(true);
            }

            if (we.handleArmSwing(player)) {
                event.setCanceled(true);
            }
        } else if (event instanceof PlayerInteractEvent.RightClickBlock) {
            @SuppressWarnings("deprecation")
            WorldVector pos = new WorldVector(LocalWorldAdapter.adapt(world),
                    event.getPos().getX(), event.getPos().getY(), event.getPos().getZ());

            boolean blockHandled = we.handleBlockRightClick(player, pos);
            if (blockHandled) {
                event.setCanceled(true);
            }

            boolean rightClickHandled = we.handleRightClick(player);
            if (rightClickHandled) {
                event.setCanceled(true);
            }

            lastRightClicks.put(playerMP.getUniqueID(),
                    new RightClick(tick, blockHandled || rightClickHandled, rightClickHandled, true));
        } else if (event instanceof PlayerInteractEvent.RightClickItem) {
            boolean rightClickHandled = we.handleRightClick(player);
            if (rightClickHandled) {
                event.setCanceled(true);
            }

            lastRightClicks.put(playerMP.getUniqueID(),
                    new RightClick(tick, rightClickHandled, rightClickHandled, false));
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        lastRightClicks.remove(event.player.getUniqueID());
    }

    /**
     * What WorldEdit did with the first event of a player's right click, so
     * the events the client sends again for that click can be answered the
     * same way without running the tool again.
     */
    private static final class RightClick {
        final int tick;
        final boolean cancelled;
        final boolean rightClickHandled;
        boolean useItemPending;

        RightClick(int tick, boolean cancelled, boolean rightClickHandled, boolean useItemPending) {
            this.tick = tick;
            this.cancelled = cancelled;
            this.rightClickHandled = rightClickHandled;
            this.useItemPending = useItemPending;
        }
    }

    public static ItemStack toForgeItemStack(BaseItemStack item) {
        ItemStack ret = new ItemStack(Item.getItemById(item.getType()), item.getAmount(), item.getData());
        for (Map.Entry<Integer, Integer> entry : item.getEnchantments().entrySet()) {
            ret.addEnchantment(net.minecraft.enchantment.Enchantment.getEnchantmentByID(entry.getKey()), entry.getValue());
        }

        return ret;
    }

    /**
     * Get the configuration.
     *
     * @return the Forge configuration
     */
    ForgeConfiguration getConfig() {
        return this.config;
    }

    /**
     * Get the WorldEdit proxy for the given player.
     *
     * @param player the player
     * @return the WorldEdit player
     */
    public ForgePlayer wrap(EntityPlayerMP player) {
        checkNotNull(player);
        return new ForgePlayer(player);
    }

    /**
     * Get the session for a player.
     *
     * @param player the player
     * @return the session
     */
    public LocalSession getSession(EntityPlayerMP player) {
        checkNotNull(player);
        return WorldEdit.getInstance().getSessionManager().get(wrap(player));
    }

    /**
     * Get the WorldEdit proxy for the given world.
     *
     * @param world the world
     * @return the WorldEdit world
     */
    public ForgeWorld getWorld(World world) {
        checkNotNull(world);
        return new ForgeWorld(world);
    }

    /**
     * Get the WorldEdit proxy for the platform.
     *
     * @return the WorldEdit platform
     */
    public Platform getPlatform() {
        return this.platform;
    }

    /**
     * Get the working directory where WorldEdit's files are stored.
     *
     * @return the working directory
     */
    public File getWorkingDir() {
        return this.workingDir;
    }

    /**
     * Get the version of the WorldEdit-for-Forge implementation.
     *
     * @return a version string
     */
    String getInternalVersion() {
        return ForgeWorldEdit.class.getAnnotation(Mod.class).version();
    }

    public void setPermissionsProvider(ForgePermissionsProvider provider) {
        this.provider = provider;
    }

    public ForgePermissionsProvider getPermissionsProvider() {
        return provider;
    }

}
