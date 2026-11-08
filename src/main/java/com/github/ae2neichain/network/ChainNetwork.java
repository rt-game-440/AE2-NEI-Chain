package com.github.ae2neichain.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.github.ae2neichain.AE2NeiChain;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public final class ChainNetwork {

    public static SimpleNetworkWrapper CHANNEL;

    private ChainNetwork() {}

    public static void init() {
        CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(AE2NeiChain.MODID);
        CHANNEL.registerMessage(PacketChainCraft.Handler.class, PacketChainCraft.class, 0, Side.SERVER);
        CHANNEL.registerMessage(PacketChainCraftResult.Handler.class, PacketChainCraftResult.class, 1, Side.CLIENT);
        CHANNEL.registerMessage(PacketServerHello.Handler.class, PacketServerHello.class, 2, Side.CLIENT);
        CHANNEL.registerMessage(PacketRunChainTest.Handler.class, PacketRunChainTest.class, 3, Side.CLIENT);
    }

    public static void sendToServer(IMessage msg) {
        CHANNEL.sendToServer(msg);
    }

    public static void sendTo(IMessage msg, EntityPlayerMP player) {
        CHANNEL.sendTo(msg, player);
    }
}
