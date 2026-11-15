package com.pockyl.diegesis.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.pockyl.diegesis.impl.client.render.SceneRenderer;

/**
 * Where the library's in-world scenes are drawn. Fabric has no world render event in 26.1; the level is built from render
 * states, so the scenes are submitted while the entities are: that phase keeps the level's pose and the frame's matrices,
 * and what it draws reaches the frame.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = "submitEntities", at = @At("TAIL"))
    private void diegesis$renderScenes(PoseStack poseStack, LevelRenderState renderState, SubmitNodeCollector collector,
            CallbackInfo callback) {
        CameraRenderState camera = renderState.cameraRenderState;
        SceneRenderer.render(poseStack, new Matrix4f(camera.viewRotationMatrix), new Matrix4f(camera.projectionMatrix),
                Minecraft.getInstance().gameRenderer.getMainCamera(), collector);
    }
}
