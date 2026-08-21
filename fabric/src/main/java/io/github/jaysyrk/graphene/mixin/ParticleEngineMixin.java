package io.github.jaysyrk.graphene.mixin;

import io.github.jaysyrk.graphene.Graphene;
import io.github.jaysyrk.graphene.client.GrapheneRuntime;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Thins out particles when the frame budget is tight.
 *
 * <p>Particles are the cheapest thing to give up under load and among the most expensive to keep:
 * a single explosion or a campfire in a crowded base can add hundreds of quads and their ticking
 * cost, and at a reduced density nobody can tell the difference in motion.
 *
 * <p>Thinning is deterministic per call rather than random. A counter admits a fixed fraction, so at
 * 50% density every second particle survives -- an even thinning of the effect. Rolling a die per
 * particle gives the same average and a visibly clumpy result, because randomness produces runs.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {

    private int graphene$counter;
    private double graphene$credit;

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void graphene$thin(
            ParticleOptions options, double x, double y, double z,
            double dx, double dy, double dz, CallbackInfoReturnable<Particle> cir) {

        GrapheneRuntime runtime = Graphene.active();
        if (runtime == null) {
            return;
        }
        double density = runtime.settings().particleDensity();
        if (density >= 1.0) {
            return;
        }
        if (density <= 0.0) {
            cir.setReturnValue(null);
            return;
        }
        graphene$counter++;
        // Accumulate fractional allowance and spend it a whole particle at a time, which spreads
        // the survivors evenly through the stream at any density.
        graphene$credit += density;
        if (graphene$credit >= 1.0) {
            graphene$credit -= 1.0;
            return;
        }
        cir.setReturnValue(null);
    }
}
