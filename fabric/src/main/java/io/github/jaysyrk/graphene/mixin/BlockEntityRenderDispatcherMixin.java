package io.github.jaysyrk.graphene.mixin;

import io.github.jaysyrk.graphene.Graphene;
import io.github.jaysyrk.graphene.client.GrapheneRuntime;
import io.github.jaysyrk.graphene.core.quality.QualitySettings;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caps how many chests, signs and banners are drawn, keeping the ones that matter.
 *
 * <p>Block entities are the classic way to bring a strong machine to its knees: a storage room is a
 * few hundred chests, each with an animated lid and its own draw, and a shop district is a wall of
 * signs whose text is laid out every frame. They are individually cheap and collectively ruinous,
 * and the vanilla answer is a single distance setting that either keeps all of them or none.
 *
 * <p>So each one is scored by how much of the screen it can possibly account for -- size over
 * distance -- and admitted against a per-frame budget. When the scene is quiet everything gets
 * through; when it is not, what survives is the near and the large rather than whatever happened to
 * be first in the iteration order.
 *
 * <p>Returning null is the vanilla-supported way to skip one: the caller checks for it and simply
 * does not add the state to the frame.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    /** Block entities closer than this are never skipped. */
    private static final double ALWAYS_DRAW_RADIUS_SQ = 12.0 * 12.0;
    /** Distance at which a score saturates, used to normalise into [0, 1]. */
    private static final double SCORE_REFERENCE_BLOCKS = 96.0;

    @Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true)
    private <E extends BlockEntity, S extends BlockEntityRenderState> void graphene$budget(
            E blockEntity, float partialTick, ModelFeatureRenderer.CrumblingOverlay crumbling,
            CallbackInfoReturnable<S> cir) {

        GrapheneRuntime runtime = Graphene.active();
        if (runtime == null || !runtime.isBlockEntityBudgetEnabled()) {
            return;
        }
        Vec3 camera = cameraPosition();
        if (camera == null) {
            return;
        }

        BlockPos pos = blockEntity.getBlockPos();
        double dx = pos.getX() + 0.5 - camera.x;
        double dy = pos.getY() + 0.5 - camera.y;
        double dz = pos.getZ() + 0.5 - camera.z;
        double distanceSq = dx * dx + dy * dy + dz * dz;

        if (distanceSq <= ALWAYS_DRAW_RADIUS_SQ) {
            return;
        }

        QualitySettings settings = runtime.settings();
        double limit = SCORE_REFERENCE_BLOCKS * settings.blockEntityDistanceScale();
        if (distanceSq > limit * limit) {
            cir.setReturnValue(null);
            return;
        }

        // Nearer is more important, and the falloff is on distance rather than its square so the
        // scores spread across the range instead of collapsing towards zero past a few dozen blocks.
        double distance = Math.sqrt(distanceSq);
        double score = 1.0 - Math.min(1.0, distance / SCORE_REFERENCE_BLOCKS);

        if (!runtime.blockEntityBudget().admit(score)) {
            cir.setReturnValue(null);
        }
    }

    private static Vec3 cameraPosition() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gameRenderer == null) {
            return null;
        }
        Camera camera = client.gameRenderer.getMainCamera();
        return camera == null ? null : camera.position();
    }
}
