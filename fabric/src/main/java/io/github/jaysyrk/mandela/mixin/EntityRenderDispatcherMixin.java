package io.github.jaysyrk.mandela.mixin;

import io.github.jaysyrk.mandela.Mandela;
import io.github.jaysyrk.mandela.client.MandelaRuntime;
import io.github.jaysyrk.mandela.core.quality.QualitySettings;
import io.github.jaysyrk.mandela.core.visibility.VisibilityCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skips entities that are in range and in front of the camera but standing behind something solid.
 *
 * <p>Vanilla's test is distance and frustum. That leaves a large and very common category of wasted
 * work: the mobs in the cave under the hill you are looking at, the villagers on the far side of a
 * building, everything in the room you just walked out of. They are all in front of the camera and
 * all invisible, and every one of them costs a render state extraction and a draw.
 *
 * <p>Three cheap tests, in increasing order of cost, decide otherwise:
 *
 * <ol>
 *   <li><b>Distance.</b> Scale the range entities draw at by the current quality setting.
 *   <li><b>Occlusion.</b> If the chunk sections the entity occupies were not drawn this frame, the
 *       entity is behind whatever hid them.
 *   <li><b>Hysteresis.</b> Require that verdict to hold for several frames before acting on it, so a
 *       borderline case flickering between the two does not flicker on screen.
 * </ol>
 *
 * <p>The injection only ever turns a yes into a no: whatever vanilla already rejected stays
 * rejected, so nothing here can make Mandela draw something Minecraft would not have.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    /** Entities closer than this are always drawn, whatever the tests say. */
    private static final double ALWAYS_DRAW_RADIUS = 8.0;
    private static final double ALWAYS_DRAW_RADIUS_SQ = ALWAYS_DRAW_RADIUS * ALWAYS_DRAW_RADIUS;

    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
    private <E extends Entity> void mandela$cull(
            E entity, Frustum frustum, double camX, double camY, double camZ,
            CallbackInfoReturnable<Boolean> cir) {

        if (!cir.getReturnValueZ()) {
            return;
        }
        MandelaRuntime runtime = Mandela.active();
        if (runtime == null || !runtime.isEntityCullingEnabled()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        // Never cull what the camera is attached to, or what the player is riding: getting either
        // wrong hides the whole view or the boat under your feet.
        if (client != null && (entity == client.getCameraEntity()
                || (client.player != null && entity == client.player.getVehicle()))) {
            return;
        }

        double dx = entity.getX() - camX;
        double dy = entity.getY() - camY;
        double dz = entity.getZ() - camZ;
        double distanceSq = dx * dx + dy * dy + dz * dz;
        if (distanceSq <= ALWAYS_DRAW_RADIUS_SQ) {
            return;
        }

        QualitySettings settings = runtime.settings();
        double limit = runtime.entityDrawDistance() * settings.entityDistanceScale();
        if (distanceSq > limit * limit) {
            cir.setReturnValue(false);
            return;
        }

        if (settings.cullingAggression() <= 0.0) {
            return;
        }

        VisibilityCache cache = runtime.entityVisibility();
        int id = entity.getId();
        switch (cache.poll(id)) {
            case RENDER -> {
                return;
            }
            case CULL -> {
                cir.setReturnValue(false);
                return;
            }
            case TEST -> {
                AABB box = entity.getBoundingBox();
                boolean visible = runtime.sections().isVisible(box);
                if (!cache.submit(id, visible)) {
                    cir.setReturnValue(false);
                }
            }
            default -> {
            }
        }
    }
}
