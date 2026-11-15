package com.pockyl.diegesis.fabric;

import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricModelManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.resources.Identifier;

import com.pockyl.diegesis.impl.network.Packets;
import com.pockyl.diegesis.platform.ClientPlatform;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FabricClientPlatform implements ClientPlatform {
    /** Extra model keys by the name the library asks for; filled while models are loaded. */
    private static final Map<Identifier, ExtraModelKey<QuadCollection>> KEYS = new ConcurrentHashMap<>();

    public static ExtraModelKey<QuadCollection> key(Identifier id) {
        return KEYS.computeIfAbsent(id, ignored -> ExtraModelKey.create(id::toString));
    }

    @Override
    public boolean serverHasChannel() {
        return Minecraft.getInstance().getConnection() != null && FabricNetwork.serverHasChannel();
    }

    @Override
    public void sendToServer(Packets.ClientPacket packet) {
        if (serverHasChannel()) {
            FabricNetwork.sendToServer(packet);
        }
    }

    @Override
    public QuadCollection getModel(Identifier id) {
        ExtraModelKey<QuadCollection> key = KEYS.get(id);
        if (key == null) {
            return QuadCollection.EMPTY;
        }
        QuadCollection model = ((FabricModelManager) Minecraft.getInstance().getModelManager()).getModel(key);
        return model != null ? model : QuadCollection.EMPTY;
    }
}
