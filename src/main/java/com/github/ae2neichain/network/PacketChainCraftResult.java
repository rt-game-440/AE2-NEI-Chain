package com.github.ae2neichain.network;

import com.github.ae2neichain.AE2NeiChain;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** 服务端 → 客户端：合成请求回执。crafted 为实际合成次数，error 为空表示无错误。 */
public class PacketChainCraftResult implements IMessage {

    public int requestId;
    public int crafted;
    public String error;

    public PacketChainCraftResult() {}

    public PacketChainCraftResult(int requestId, int crafted, String error) {
        this.requestId = requestId;
        this.crafted = crafted;
        this.error = error == null ? "" : error;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        requestId = buf.readInt();
        crafted = buf.readInt();
        error = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(requestId);
        buf.writeInt(crafted);
        ByteBufUtils.writeUTF8String(buf, error);
    }

    public static class Handler implements IMessageHandler<PacketChainCraftResult, IMessage> {

        @Override
        public IMessage onMessage(PacketChainCraftResult message, MessageContext ctx) {
            AE2NeiChain.proxy.onCraftResult(message);
            return null;
        }
    }
}
