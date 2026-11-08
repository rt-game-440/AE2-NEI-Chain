package com.github.ae2neichain.debug;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.github.ae2neichain.network.ChainNetwork;
import com.github.ae2neichain.network.PacketRunChainTest;
import com.github.ae2neichain.server.TerminalChainCrafter;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.definitions.IBlocks;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.parts.IPartHost;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AEColor;
import appeng.core.sync.GuiBridge;
import appeng.tile.storage.TileDrive;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 调试命令（仅 debug=true 时注册）：
 * <ul>
 * <li>setup：在玩家东侧搭建 控制器 + 创造能源 + ME 驱动器(64k) + 合成终端，并存入 64 原木</li>
 * <li>open：为玩家打开该合成终端</li>
 * <li>e2e：setup（若未搭建）→ 通知客户端自测 → 打开终端</li>
 * <li>report：输出 ME 中原木/木板/木棍数量与最近的合成记录</li>
 * </ul>
 */
public class DebugCommand extends CommandBase {

    private static int dim = Integer.MIN_VALUE;
    private static int cx, cy, cz; // 控制器坐标；线缆总线在 (cx-1, cy, cz)，终端朝西

    private static final List<Runnable> LATER = new ArrayList<>();
    private static int laterDelay;
    private static boolean tickRegistered;

    @Override
    public String getCommandName() {
        return "ae2neichain";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/ae2neichain <setup|open|e2e|report>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) throw new WrongUsageException(getCommandUsage(sender));
        final String sub = args[0];
        if ("report".equals(sub)) {
            if (args.length > 1 && "final".equals(args[1])) judge(sender);
            report(sender);
            return;
        }
        final EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        if ("setup".equals(sub)) {
            setup(player);
        } else if ("open".equals(sub)) {
            open(player);
        } else if ("e2e".equals(sub)) {
            if (dim != player.dimension) setup(player);
            final EntityPlayerMP p = player;
            later(80, new Runnable() {

                @Override
                public void run() {
                    report(p);
                    ChainNetwork.sendTo(new PacketRunChainTest(), p);
                    open(p);
                }
            });
        } else {
            throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    private static void say(ICommandSender sender, String msg) {
        sender.addChatMessage(new ChatComponentText("[ae2neichain] " + msg));
        DebugLog.log("CMD " + msg);
    }

    private void setup(final EntityPlayerMP player) {
        final World w = player.worldObj;
        final int px = MathHelper.floor_double(player.posX);
        final int py = MathHelper.floor_double(player.posY);
        final int pz = MathHelper.floor_double(player.posZ);
        dim = player.dimension;
        cx = px + 3;
        cy = py;
        cz = pz;

        final IBlocks blocks = AEApi.instance().definitions().blocks();
        for (int dx = -1; dx <= 2; dx++) {
            w.setBlock(cx + dx, cy - 1, cz, Blocks.stone);
            w.setBlockToAir(cx + dx, cy + 1, cz);
        }
        place(w, cx, cy, cz, blocks.controller().maybeBlock().orNull());
        place(w, cx + 1, cy, cz, blocks.energyCellCreative().maybeBlock().orNull());
        place(w, cx + 2, cy, cz, blocks.drive().maybeBlock().orNull());
        place(w, cx - 1, cy, cz, blocks.multiPart().maybeBlock().orNull());

        final TileEntity drive = w.getTileEntity(cx + 2, cy, cz);
        if (drive instanceof TileDrive) {
            final IInventory inv = ((TileDrive) drive).getInternalInventory();
            inv.setInventorySlotContents(0, AEApi.instance().definitions().items().cell64k().maybeStack(1).orNull());
        }

        final TileEntity bus = w.getTileEntity(cx - 1, cy, cz);
        if (bus instanceof IPartHost) {
            final IPartHost host = (IPartHost) bus;
            host.addPart(
                    AEApi.instance().definitions().parts().cableGlass().stack(AEColor.Transparent, 1),
                    ForgeDirection.UNKNOWN,
                    player);
            host.addPart(
                    AEApi.instance().definitions().parts().craftingTerminal().maybeStack(1).orNull(),
                    ForgeDirection.WEST,
                    player);
        }

        // 面朝终端
        player.playerNetServerHandler.setPlayerLocation(px + 0.5, py, pz + 0.5, -90f, 0f);
        say(player, "已搭建 ME 网络，控制器位于 " + cx + "," + cy + "," + cz + "；2 秒后存入测试材料");

        later(40, new Runnable() {

            @Override
            public void run() {
                final IMEMonitor<IAEItemStack> storage = storage();
                if (storage == null) {
                    say(player, "FAIL: ME 网络未就绪");
                    return;
                }
                storage.injectItems(
                        AEItemStack.create(new ItemStack(Blocks.log, 64, 0)),
                        Actionable.MODULATE,
                        new BaseActionSource());
                say(player, "已存入 64 原木");
            }
        });
    }

    private static void place(World w, int x, int y, int z, Block block) {
        if (block != null) w.setBlock(x, y, z, block);
    }

    private void open(EntityPlayerMP player) {
        final World w = player.worldObj;
        final TileEntity bus = dim == player.dimension ? w.getTileEntity(cx - 1, cy, cz) : null;
        if (bus == null) {
            say(player, "FAIL: 未找到合成终端，请先 setup");
            return;
        }
        Platform.openGUI(player, bus, ForgeDirection.WEST, GuiBridge.GUI_CRAFTING_TERMINAL);
    }

    private static IMEMonitor<IAEItemStack> storage() {
        if (dim == Integer.MIN_VALUE) return null;
        final World w = FMLCommonHandler.instance().getMinecraftServerInstance().worldServerForDimension(dim);
        final TileEntity te = w.getTileEntity(cx, cy, cz);
        if (!(te instanceof IGridHost)) return null;
        final IGridNode node = ((IGridHost) te).getGridNode(ForgeDirection.UNKNOWN);
        final IGrid grid = node == null ? null : node.getGrid();
        if (grid == null) return null;
        final IStorageGrid sg = grid.getCache(IStorageGrid.class);
        return sg == null ? null : sg.getItemInventory();
    }

    private static long count(IMEMonitor<IAEItemStack> storage, ItemStack stack) {
        final IAEItemStack found = storage.getStorageList().findPrecise(AEItemStack.create(stack));
        return found == null ? 0 : found.getStackSize();
    }

    private static void report(ICommandSender sender) {
        final IMEMonitor<IAEItemStack> storage = storage();
        if (storage == null) {
            say(sender, "REPORT FAIL: 没有可用的测试 ME 网络");
        } else {
            say(
                    sender,
                    "REPORT log=" + count(storage, new ItemStack(Blocks.log, 1, 0))
                            + " planks="
                            + count(storage, new ItemStack(Blocks.planks, 1, 0))
                            + " stick="
                            + count(storage, new ItemStack(Items.stick))
                            + " invStick="
                            + countInv(sender, new ItemStack(Items.stick))
                            + " invPlanks="
                            + countInv(sender, new ItemStack(Blocks.planks, 1, 0)));
        }
        synchronized (TerminalChainCrafter.RECENT) {
            for (String line : TerminalChainCrafter.RECENT) DebugLog.log("RECENT " + line);
            TerminalChainCrafter.RECENT.clear();
        }
    }

    private static long countInv(ICommandSender sender, ItemStack stack) {
        if (!(sender instanceof EntityPlayer)) return -1;
        long n = 0;
        for (ItemStack s : ((EntityPlayer) sender).inventory.mainInventory) {
            if (s != null && s.isItemEqual(stack)) n += s.stackSize;
        }
        return n;
    }

    /** 自测判定：最终产物 16 根木棍进了背包，中间产物木板没有留在背包，且原木确有消耗。 */
    private static void judge(ICommandSender sender) {
        final IMEMonitor<IAEItemStack> storage = storage();
        if (storage == null) {
            say(sender, "AUTOTEST RESULT FAIL 没有测试 ME 网络");
            return;
        }
        final long logs = count(storage, new ItemStack(Blocks.log, 1, 0));
        final long sticks = countInv(sender, new ItemStack(Items.stick));
        final long planks = countInv(sender, new ItemStack(Blocks.planks, 1, 0));
        // 木板是中间产物，不能进背包；木棍批量合成，请求数应很少
        int requests = 0;
        synchronized (TerminalChainCrafter.RECENT) {
            for (String line : TerminalChainCrafter.RECENT) if (line.startsWith("craft ")) requests++;
        }
        final boolean pass = sticks >= 16 && planks == 0 && logs < 64 && requests <= 4;
        say(
                sender,
                "AUTOTEST RESULT " + (pass ? "PASS" : "FAIL")
                        + " requests="
                        + requests
                        + " invStick="
                        + sticks
                        + " invPlanks="
                        + planks
                        + " log="
                        + logs);
    }

    private static void later(int ticks, Runnable task) {
        if (!tickRegistered) {
            tickRegistered = true;
            FMLCommonHandler.instance().bus().register(new Ticker());
        }
        synchronized (LATER) {
            LATER.add(task);
            laterDelay = Math.max(laterDelay, ticks);
        }
    }

    public static class Ticker {

        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            final List<Runnable> run = new ArrayList<>();
            synchronized (LATER) {
                if (LATER.isEmpty() || --laterDelay > 0) return;
                run.addAll(LATER);
                LATER.clear();
            }
            for (Runnable r : run) {
                try {
                    r.run();
                } catch (Throwable t) {
                    DebugLog.error("调试任务异常", t);
                }
            }
        }
    }
}
