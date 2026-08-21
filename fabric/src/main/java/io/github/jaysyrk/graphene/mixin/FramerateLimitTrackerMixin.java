package io.github.jaysyrk.graphene.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import io.github.jaysyrk.graphene.Graphene;
import io.github.jaysyrk.graphene.client.GrapheneRuntime;
import io.github.jaysyrk.graphene.core.idle.IdlePolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extends vanilla's idle throttling rather than replacing it.
 *
 * <p>Minecraft already caps the frame rate when the window is minimised, when a menu is open outside
 * a world, and after a long spell without input. What it does not cover is the most common case on a
 * two-monitor desk: the window is visible, the player is reading something else, and the machine is
 * rendering three hundred frames a second that nobody is looking at. Vanilla's thresholds are also
 * fixed constants, which is no use to someone on a laptop trying to make a battery last.
 *
 * <p>So this takes the lower of vanilla's cap and Graphene's. Taking the minimum rather than
 * replacing the value means this can only ever ask for less work than the game already decided to
 * do -- it cannot accidentally undo a throttle vanilla wanted.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {

    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void graphene$applyIdleCap(CallbackInfoReturnable<Integer> cir) {
        GrapheneRuntime runtime = Graphene.runtime();
        if (runtime == null) {
            return;
        }
        int ours = runtime.idleFrameCap();
        if (ours == IdlePolicy.UNCAPPED) {
            return;
        }
        cir.setReturnValue(Math.min(cir.getReturnValueI(), ours));
    }

    @Inject(method = "onInputReceived", at = @At("HEAD"))
    private void graphene$noteInput(CallbackInfo ci) {
        GrapheneRuntime runtime = Graphene.runtime();
        if (runtime != null) {
            runtime.onInput();
        }
    }
}
