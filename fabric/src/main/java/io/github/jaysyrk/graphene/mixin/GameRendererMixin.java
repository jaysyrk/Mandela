package io.github.jaysyrk.graphene.mixin;

import io.github.jaysyrk.graphene.Graphene;
import io.github.jaysyrk.graphene.client.GrapheneRuntime;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets the render pass, which is what every other decision is timed against.
 *
 * <p>Timing here rather than around the whole game loop is deliberate. The loop also contains the
 * frame limiter's sleep and, with vsync on, the wait for the display. Including those would mean a
 * capped machine always measures exactly at its cap: the governor would see no headroom, and quality
 * could fall but never be given back. What is measured here is the work, which is the only part
 * turning settings down can change.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void graphene$beginFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        GrapheneRuntime runtime = Graphene.runtime();
        if (runtime != null) {
            runtime.beginFrame();
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void graphene$endFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        GrapheneRuntime runtime = Graphene.runtime();
        if (runtime != null) {
            runtime.endFrame();
        }
    }
}
