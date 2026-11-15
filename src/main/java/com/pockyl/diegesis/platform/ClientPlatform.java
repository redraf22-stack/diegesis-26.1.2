package com.pockyl.diegesis.platform;

import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.resources.Identifier;

import com.pockyl.diegesis.impl.network.Packets;

/** What the client code needs from the mod loader; only loaded on clients. */
public interface ClientPlatform {
    ClientPlatform INSTANCE = Platform.load(ClientPlatform.class);

    /** Whether the server we are connected to has the library's channel. */
    boolean serverHasChannel();

    void sendToServer(Packets.ClientPacket packet);

    /** A model registered as an extra model (see {@code ModelRegistry}); empty when it is unknown. */
    QuadCollection getModel(Identifier id);
}
