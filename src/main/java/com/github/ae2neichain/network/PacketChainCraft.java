package com.github.ae2neichain.network;

import net.minecraft.nbt.NBTTagCompound;

import com.github.ae2neichain.server.TerminalChainCrafter;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 → 服务端：在当前打开的合成终端里按配方合成 times 次。
 * <p>
 * recipe 格式与 AE2 PacketNEIRecipe 相同：键 "#0".."#8" 对应 3x3 合成格，值为该格可选物品（NBTTagList）。
 */
public class PacketChainCraft implements IMessage {

    public int requestId;
    public int times;
    public NBTTagCompound recipe;
    /** 本步产物是合成链的最终产物：优先放进玩家背包（放不下再存 ME）。 */
    public boolean toPlayer;

    public PacketChainCraft() {}

    public PacketChainCraft(int requestId, int times, NBTTagCompound recipe, boolean toPlayer) {
        this.requestId = requestId;
        this.times = times;
        this.recipe = recipe;
        this.toPlayer = toPlayer;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        requestId = buf.readInt();
        times = buf.readInt();
        recipe = ByteBufUtils.readTag(buf);
        toPlayer = buf.isReadable() && buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(requestId);
        buf.writeInt(times);
        ByteBufUtils.writeTag(buf, recipe);
        buf.writeBoolean(toPlayer);
    }

    public static class Handler implements IMessageHandler<PacketChainCraft, IMessage> {

        @Override
        public IMessage onMessage(PacketChainCraft message, MessageContext ctx) {
            // 网络线程：只入队，实际合成在服务端主线程 tick 中执行
            TerminalChainCrafter.INSTANCE.enqueue(ctx.getServerHandler().playerEntity, message);
            return null;
        }
    }
}
