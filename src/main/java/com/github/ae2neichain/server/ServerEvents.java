package com.github.ae2neichain.server;

import net.minecraft.entity.player.EntityPlayerMP;

import com.github.ae2neichain.network.ChainNetwork;
import com.github.ae2neichain.network.PacketServerHello;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

public class ServerEvents {

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            ChainNetwork.sendTo(new PacketServerHello(), (EntityPlayerMP) event.player);
        }
    }
}
