package io.github.jaysyrk.graphene.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the set of chunk sections the renderer decided to draw this frame.
 *
 * <p>This is the whole basis of Graphene's entity culling. Minecraft already does the expensive part
 * -- walking the chunk graph and working out what is hidden behind terrain -- and then throws the
 * conclusion away as far as entities are concerned. Reading it costs nothing and is exactly as
 * correct as the terrain culling it comes from.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {

    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> graphene$visibleSections();
}
