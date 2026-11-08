package com.github.ae2neichain.network;

import com.github.ae2neichain.AE2NeiChain;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** 服务端 → 客户端（仅调试）：在下一次打开的合成终端中自动执行一次合成链自测。 */
public class PacketRunChainTest implements IMessage {

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<PacketRunChainTest, IMessage> {

        @Override
        public IMessage onMessage(PacketRunChainTest message, MessageContext ctx) {
            AE2NeiChain.proxy.onRunChainTest();
            return null;
        }
    }
}
