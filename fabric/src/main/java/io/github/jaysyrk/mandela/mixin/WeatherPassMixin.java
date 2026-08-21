package io.github.jaysyrk.mandela.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import io.github.jaysyrk.mandela.Mandela;
import io.github.jaysyrk.mandela.client.MandelaRuntime;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the weather pass outright when quality has fallen far enough.
 *
 * <p>Heavy rain is a wall of alpha-blended quads across the whole screen, which is precisely the
 * shape of work that hurts on a weak GPU, and it is also the effect a player is least likely to
 * notice losing while fighting something in a storm.
 *
 * <p>Declining to add the pass at all, rather than trying to draw a fraction of the drops, is what
 * keeps this safe: the frame simply has no weather in it. Editing the weather state in place would
 * risk leaving last frame's droplets frozen on screen, which is worse than either extreme.
 */
@Mixin(LevelRenderer.class)
public abstract class WeatherPassMixin {

    /** Below this density the pass is skipped entirely. */
    private static final double SKIP_BELOW = 0.4;

    @Inject(method = "addWeatherPass", at = @At("HEAD"), cancellable = true)
    private void mandela$skipWeather(
            FrameGraphBuilder builder, GpuBufferSlice fog, CallbackInfo ci) {
        MandelaRuntime runtime = Mandela.active();
        if (runtime != null && runtime.settings().weatherDensity() < SKIP_BELOW) {
            ci.cancel();
        }
    }
}
