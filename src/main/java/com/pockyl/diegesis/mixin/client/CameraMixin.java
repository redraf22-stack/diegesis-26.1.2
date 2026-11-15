package com.pockyl.diegesis.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.diegesis.impl.client.focus.FocusController;

/**
 * Moves the camera to the station while the player works at it. In 26.1 the camera is placed by
 * {@link Camera#update(DeltaTracker)}, and its position and rotation are plain fields: they are set here after vanilla
 * and other mods placed the camera, and only while focused, so third-person and camera mods keep working the rest of the
 * time.
 */
@Mixin(value = Camera.class, priority = 2000)
abstract class CameraMixin {
    /** Blocks between the camera and the eyes before the player's body is drawn. */
    private static final double BODY_CLEARANCE = 0.75;

    /**
     * The scene's field of view while the player works at a station: 26.1 works the FOV out in the camera itself (1.21.1 had it
     * in {@code GameRenderer.getFov}), and a station's scene asks for a narrow one (a board's is 30) - without it the camera
     * stands as close as the scene says and still shows the station small, far away.
     */
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void diegesis$focusFov(float partialTick, CallbackInfoReturnable<Float> callback) {
        FocusController focus = FocusController.get();
        if (focus.isActive()) {
            callback.setReturnValue((float) focus.fov(callback.getReturnValue()));
        }
    }


    @Shadow
    private boolean detached;

    @Shadow
    private Vec3 position;

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    private float xRot;

    @Shadow
    private float yRot;

    @Shadow
    public abstract Vec3 position();

    @Shadow
    public abstract float xRot();

    @Shadow
    public abstract float yRot();

    @Inject(method = "update", at = @At("TAIL"))
    private void diegesis$focusCamera(DeltaTracker deltaTracker, CallbackInfo ci) {
        FocusController focus = FocusController.get();
        if (!focus.isActive()) {
            return;
        }
        focus.frame();
        Vec3 eye = this.position;
        FocusController.CameraPose pose = focus.cameraPose(eye, this.yRot, this.xRot);
        if (pose == null) {
            return;
        }
        setRotation(pose.yaw(), pose.pitch());
        setPosition(pose.position());
        // Detached draws the player's own body - but only once the camera is out of the head, never from inside it, and
        // never when the scene hides it.
        this.detached = !focus.options().hidePlayer()
                && (this.detached || pose.position().distanceToSqr(eye) > BODY_CLEARANCE * BODY_CLEARANCE);
    }
}
