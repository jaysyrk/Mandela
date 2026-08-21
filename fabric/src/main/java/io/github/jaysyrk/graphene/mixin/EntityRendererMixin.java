package io.github.jaysyrk.graphene.mixin;

import io.github.jaysyrk.graphene.Graphene;
import io.github.jaysyrk.graphene.client.GrapheneRuntime;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drops entity blob shadows when quality falls far enough.
 *
 * <p>A shadow is not one quad. It samples the blocks under the entity and builds geometry per
 * surface it lands on, every frame, for every entity -- so the cost scales with exactly the thing
 * that is already hurting when the frame rate is in trouble, which is how many mobs are on screen.
 *
 * <p>Returning a radius of zero is how vanilla itself expresses "this entity has no shadow", so the
 * rest of the pipeline needs no convincing.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(method = "getShadowRadius", at = @At("RETURN"), cancellable = true)
    private void graphene$suppressShadow(
            EntityRenderState state, CallbackInfoReturnable<Float> cir) {
        if (cir.getReturnValueF() <= 0.0f) {
            return;
        }
        GrapheneRuntime runtime = Graphene.active();
        if (runtime != null && !runtime.settings().entityShadows()) {
            cir.setReturnValue(0.0f);
        }
    }
}
