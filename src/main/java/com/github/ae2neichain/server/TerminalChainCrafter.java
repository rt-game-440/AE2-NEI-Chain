package com.github.ae2neichain.server;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.oredict.OreDictionary;

import com.github.ae2neichain.ModConfig;
import com.github.ae2neichain.debug.DebugLog;
import com.github.ae2neichain.network.ChainNetwork;
import com.github.ae2neichain.network.PacketChainCraft;
import com.github.ae2neichain.network.PacketChainCraftResult;

import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.container.ContainerNull;
import appeng.container.slot.SlotCraftingTerm;
import appeng.container.slot.SlotPatternTerm;
import appeng.helpers.IContainerCraftingPacket;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 服务端：在玩家打开的 AE2 合成终端（含 WCT）中执行合成链的一步。
 * <p>
 * 网络线程只入队；ServerTick START 阶段在主线程合成，END 阶段（AE2 已在世界 tick 中把库存变化同步给客户端之后）再发回执，
 * 保证客户端收到回执时 ME 库存视图已经更新。
 */
public final class TerminalChainCrafter {

    public static final TerminalChainCrafter INSTANCE = new TerminalChainCrafter();

    /** 最近的合成记录，供调试 report 命令使用。 */
    public static final Deque<String> RECENT = new ArrayDeque<>();

    private static Method craftItemMethod;

    private final ConcurrentLinkedQueue<Job> queue = new ConcurrentLinkedQueue<>();
    private final List<Job> replies = new ArrayList<>();

    private TerminalChainCrafter() {}

    private static final class Job {

        final EntityPlayerMP player;
        final PacketChainCraft msg;
        int crafted;
        int toPlayer;
        String error = "";

        Job(EntityPlayerMP player, PacketChainCraft msg) {
            this.player = player;
            this.msg = msg;
        }
    }

    public void enqueue(EntityPlayerMP player, PacketChainCraft msg) {
        if (player != null && msg != null) queue.add(new Job(player, msg));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            Job job;
            while ((job = queue.poll()) != null) {
                final long start = System.nanoTime();
                try {
                    process(job);
                } catch (CraftException e) {
                    job.error = e.getMessage();
                } catch (Throwable t) {
                    job.error = "internal: " + t;
                    DebugLog.error("合成链请求处理异常", t);
                }
                final String line = String.format(
                        "craft req=%d player=%s times=%d crafted=%d toPlayer=%d error=%s cost=%.1fms",
                        job.msg.requestId,
                        job.player.getCommandSenderName(),
                        job.msg.times,
                        job.crafted,
                        job.toPlayer,
                        job.error.isEmpty() ? "-" : job.error,
                        (System.nanoTime() - start) / 1e6);
                DebugLog.log(line);
                synchronized (RECENT) {
                    RECENT.addLast(line);
                    while (RECENT.size() > 50) RECENT.removeFirst();
                }
                replies.add(job);
            }
        } else {
            for (Job job : replies) {
                ChainNetwork.sendTo(new PacketChainCraftResult(job.msg.requestId, job.crafted, job.error, job.toPlayer), job.player);
            }
            replies.clear();
        }
    }

    private static final class CraftException extends Exception {

        CraftException(String message) {
            super(message);
        }
    }

    private void process(Job job) throws Exception {
        final EntityPlayerMP player = job.player;
        final PacketChainCraft msg = job.msg;
        final Container con = player.openContainer;
        if (!(con instanceof IContainerCraftingPacket)) throw new CraftException("not a crafting terminal");
        final IContainerCraftingPacket cct = (IContainerCraftingPacket) con;
        if (!cct.useRealItems()) throw new CraftException("terminal does not use real items");

        final SlotCraftingTerm output = findOutputSlot(con);
        if (output == null) throw new CraftException("no crafting output slot");

        final IGridNode node = cct.getNetworkNode();
        final IGrid grid = node == null ? null : node.getGrid();
        if (grid == null) throw new CraftException("no grid");

        final IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
        final IEnergyGrid energy = grid.getCache(IEnergyGrid.class);
        final ISecurityGrid security = grid.getCache(ISecurityGrid.class);
        if (storageGrid == null || energy == null) throw new CraftException("grid not ready");
        if (security != null && (!security.hasPermission(player, SecurityPermissions.EXTRACT)
                || !security.hasPermission(player, SecurityPermissions.INJECT))) {
            throw new CraftException("no permission");
        }

        final IMEMonitor<IAEItemStack> storage = storageGrid.getItemInventory();
        final BaseActionSource src = cct.getActionSource();
        final IInventory matrix = cct.getInventoryByName("crafting");
        if (matrix == null || matrix.getSizeInventory() < 9) throw new CraftException("no crafting matrix");

        final int times = Math.max(1, Math.min(msg.times, ModConfig.maxCraftsPerRequest));
        final ItemStack[][] perms = parseRecipe(msg.recipe);

        // 1. 清空合成格（存回 ME）
        returnMatrix(player, matrix, storage, energy, src);
        con.onCraftMatrixChanged(matrix);

        // 2. 用每格第一个候选确定配方
        final InventoryCrafting testInv = new InventoryCrafting(new ContainerNull(), 3, 3);
        for (int x = 0; x < 9; x++) {
            if (perms[x] != null) testInv.setInventorySlotContents(x, perms[x][0]);
        }
        final IRecipe recipe = Platform.findMatchingRecipe(testInv, player.worldObj);
        if (recipe == null) throw new CraftException("no matching recipe");
        final ItemStack expected = recipe.getCraftingResult(testInv);
        if (expected == null) throw new CraftException("recipe has no result");

        // 3. 从 ME（其次背包）填格
        final IItemList<IAEItemStack> all = storage.getStorageList();
        for (int x = 0; x < 9; x++) {
            if (perms[x] == null) continue;
            ItemStack got = Platform.extractItemsByRecipe(
                    energy,
                    src,
                    storage,
                    player.worldObj,
                    recipe,
                    expected,
                    testInv,
                    perms[x][0],
                    x,
                    all,
                    Actionable.MODULATE,
                    null);
            if (got == null) got = extractExact(perms[x], storage, energy, src);
            if (got == null) got = extractFromPlayer(player, perms[x]);
            if (got == null) {
                returnMatrix(player, matrix, storage, energy, src);
                con.onCraftMatrixChanged(matrix);
                con.detectAndSendChanges();
                throw new CraftException("missing ingredients");
            }
            matrix.setInventorySlotContents(x, got);
        }
        con.onCraftMatrixChanged(matrix);

        final ItemStack result = output.getStack();
        if (result == null || !Platform.isSameItemPrecise(result, expected)) {
            returnMatrix(player, matrix, storage, energy, src);
            con.onCraftMatrixChanged(matrix);
            con.detectAndSendChanges();
            throw new CraftException("result mismatch");
        }

        // 4. 复用 AE2 终端自身的合成逻辑（会自动从 ME 补料、处理容器物品）。
        // 与 AE2 Shift 合成一样外层循环：craftItem 每次补料后可能提前返回。
        final int perCraft = Math.max(1, result.stackSize);
        int craftedTimes = 0;
        while (craftedTimes < times) {
            final ItemStack current = output.getStack();
            if (current == null || !Platform.isSameItemPrecise(current, expected)) break;
            final ItemStack request = result.copy();
            request.stackSize = perCraft * (times - craftedTimes);
            final ItemStack crafted = (ItemStack) craftItem().invoke(output, player, request, storage, all);
            if (crafted == null || crafted.stackSize <= 0) break;
            craftedTimes += Math.max(1, crafted.stackSize / perCraft);
            job.crafted = craftedTimes;
            if (msg.toPlayer) job.toPlayer += giveToPlayer(player, crafted);
            if (crafted.stackSize > 0) storeOrGive(player, crafted, storage, energy, src);
        }

        // 5. 合成格残留存回 ME
        returnMatrix(player, matrix, storage, energy, src);
        con.onCraftMatrixChanged(matrix);
        con.detectAndSendChanges();
    }

    /** 尽量放进玩家背包，返回放入数量；stack.stackSize 变为剩余数量。 */
    private static int giveToPlayer(EntityPlayerMP player, ItemStack stack) {
        final int before = stack.stackSize;
        player.inventory.addItemStackToInventory(stack);
        return before - Math.max(0, stack.stackSize);
    }

    private static SlotCraftingTerm findOutputSlot(Container con) {
        for (Object o : con.inventorySlots) {
            if (o instanceof SlotCraftingTerm && !(o instanceof SlotPatternTerm)) return (SlotCraftingTerm) o;
        }
        return null;
    }

    private static Method craftItem() throws NoSuchMethodException {
        if (craftItemMethod == null) {
            Method m = SlotCraftingTerm.class.getDeclaredMethod(
                    "craftItem",
                    net.minecraft.entity.player.EntityPlayer.class,
                    ItemStack.class,
                    IMEMonitor.class,
                    IItemList.class);
            m.setAccessible(true);
            craftItemMethod = m;
        }
        return craftItemMethod;
    }

    static ItemStack[][] parseRecipe(NBTTagCompound tag) {
        final ItemStack[][] perms = new ItemStack[9][];
        if (tag == null) return perms;
        for (int x = 0; x < 9; x++) {
            final NBTTagList list = tag.getTagList("#" + x, 10);
            if (list.tagCount() == 0) continue;
            final List<ItemStack> stacks = new ArrayList<>();
            for (int i = 0; i < list.tagCount(); i++) {
                final ItemStack is = ItemStack.loadItemStackFromNBT(list.getCompoundTagAt(i));
                if (is != null) {
                    is.stackSize = 1;
                    stacks.add(is);
                }
            }
            if (!stacks.isEmpty()) perms[x] = stacks.toArray(new ItemStack[0]);
        }
        return perms;
    }

    private static ItemStack extractExact(ItemStack[] candidates, IMEMonitor<IAEItemStack> storage,
            IEnergyGrid energy, BaseActionSource src) {
        for (ItemStack candidate : candidates) {
            final IAEItemStack req = AEItemStack.create(candidate);
            if (req == null) continue;
            req.setStackSize(1);
            final IAEItemStack out = Platform.poweredExtraction(energy, storage, req, src);
            if (out != null) return out.getItemStack();
        }
        return null;
    }

    private static ItemStack extractFromPlayer(EntityPlayerMP player, ItemStack[] candidates) {
        final ItemStack[] inv = player.inventory.mainInventory;
        for (ItemStack candidate : candidates) {
            for (int i = 0; i < inv.length; i++) {
                final ItemStack s = inv[i];
                if (s != null && OreDictionary.itemMatches(candidate, s, false)
                        && ItemStack.areItemStackTagsEqual(candidate, s)) {
                    return player.inventory.decrStackSize(i, 1);
                }
            }
        }
        return null;
    }

    private static void returnMatrix(EntityPlayerMP player, IInventory matrix, IMEMonitor<IAEItemStack> storage,
            IEnergyGrid energy, BaseActionSource src) {
        for (int x = 0; x < matrix.getSizeInventory(); x++) {
            final ItemStack s = matrix.getStackInSlot(x);
            if (s != null) {
                matrix.setInventorySlotContents(x, null);
                storeOrGive(player, s, storage, energy, src);
            }
        }
    }

    /** 优先存回 ME，存不下放背包，再不行掉落在玩家脚下。 */
    private static void storeOrGive(EntityPlayerMP player, ItemStack stack, IMEMonitor<IAEItemStack> storage,
            IEnergyGrid energy, BaseActionSource src) {
        ItemStack left = stack;
        final IAEItemStack ae = AEItemStack.create(stack);
        if (ae != null) {
            final IAEItemStack notInserted = Platform.poweredInsert(energy, storage, ae, src);
            left = notInserted == null ? null : notInserted.getItemStack();
        }
        if (left != null && left.stackSize > 0 && !player.inventory.addItemStackToInventory(left)
                && left.stackSize > 0) {
            player.dropPlayerItemWithRandomChoice(left, false);
        }
    }
}
