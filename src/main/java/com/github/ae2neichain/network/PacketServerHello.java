package com.github.ae2neichain.network;

import com.github.ae2neichain.AE2NeiChain;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** 服务端 → 客户端：玩家登录时告知服务端装有本 mod。 */
public class PacketServerHello implements IMessage {

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<PacketServerHello, IMessage> {

        @Override
        public IMessage onMessage(PacketServerHello message, MessageContext ctx) {
            AE2NeiChain.proxy.onServerHello();
            return null;
        }
    }
}
