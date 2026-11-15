package com.pockyl.diegesis.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.pockyl.diegesis.impl.client.mesh.ItemShapes;

/** Items with a shape made from their data: the one place every view of an item gets its model from, on both loaders. */
@Mixin(ItemModelResolver.class)
abstract class ItemRendererMixin {
    @Inject(method = "appendItemLayers", at = @At("HEAD"), cancellable = true)
    private void diegesis$shapedItem(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context, Level level, ItemOwner owner,
            int seed, CallbackInfo ci) {
        ClientLevel clientLevel = level instanceof ClientLevel client ? client : null;
        if (ItemShapes.fill(state, stack, (ItemModelResolver) (Object) this, context, clientLevel)) {
            ci.cancel();
        }
    }
}
