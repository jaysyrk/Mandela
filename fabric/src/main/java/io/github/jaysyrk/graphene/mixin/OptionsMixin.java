package io.github.jaysyrk.graphene.mixin;

import io.github.jaysyrk.graphene.Graphene;
import io.github.jaysyrk.graphene.client.GrapheneRuntime;
import io.github.jaysyrk.graphene.core.quality.QualitySettings;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the quality settings that the game reads back out of its own options.
 *
 * <p>Overriding the getters rather than writing to the player's options is a deliberate choice. The
 * settings screen keeps showing what the player chose, nothing is written to options.txt, and
 * uninstalling the mod leaves their configuration exactly as they left it. A mod that "helpfully"
 * edits render distance and then crashes has silently changed a setting the player never agreed to
 * and will not think to check.
 *
 * <p>Render distance is also floored: whatever the governor asks for, this never trims below
 * {@link #MINIMUM_RENDER_DISTANCE} chunks, because past that the world stops being playable rather
 * than merely looking worse.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {

    /** No amount of frame-rate trouble justifies going below this. */
    private static final int MINIMUM_RENDER_DISTANCE = 4;

    @Inject(method = "getEffectiveRenderDistance", at = @At("RETURN"), cancellable = true)
    private void graphene$trimRenderDistance(CallbackInfoReturnable<Integer> cir) {
        GrapheneRuntime runtime = Graphene.active();
        if (runtime == null) {
            return;
        }
        int trim = runtime.settings().renderDistanceTrim();
        if (trim <= 0) {
            return;
        }
        int chosen = cir.getReturnValueI();
        cir.setReturnValue(Math.max(MINIMUM_RENDER_DISTANCE, chosen - trim));
    }

    @Inject(method = "getCloudsType", at = @At("RETURN"), cancellable = true)
    private void graphene$simplifyClouds(CallbackInfoReturnable<CloudStatus> cir) {
        GrapheneRuntime runtime = Graphene.active();
        if (runtime == null) {
            return;
        }
        QualitySettings settings = runtime.settings();
        if (!settings.fancyClouds() && cir.getReturnValue() == CloudStatus.FANCY) {
            cir.setReturnValue(CloudStatus.FAST);
        }
    }
}
