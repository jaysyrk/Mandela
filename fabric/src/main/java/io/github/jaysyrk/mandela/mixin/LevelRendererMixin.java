package io.github.jaysyrk.mandela.mixin;

import io.github.jaysyrk.mandela.Mandela;
import io.github.jaysyrk.mandela.client.MandelaRuntime;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Snapshots the visible-section set the instant the renderer finishes computing it.
 *
 * <p>Timing is the whole point of injecting here rather than anywhere convenient. Within a single
 * frame the renderer culls terrain, then extracts entities, then extracts block entities. Reading
 * the section set at the end of terrain culling means every entity tested afterwards is tested
 * against this frame's answer; reading it any earlier would silently be testing against the last
 * frame's, and the resulting one-frame lag is exactly what produces flickering mobs at chunk edges.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Inject(method = "cullTerrain", at = @At("RETURN"))
    private void mandela$captureVisibleSections(
            Camera camera, Frustum frustum, boolean captureFrustum, CallbackInfo ci) {
        MandelaRuntime runtime = Mandela.runtime();
        if (runtime == null) {
            return;
        }
        runtime.sections().refresh(((LevelRendererAccessor) this).mandela$visibleSections());
    }
}
