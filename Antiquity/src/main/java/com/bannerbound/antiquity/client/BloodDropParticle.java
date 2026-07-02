package com.bannerbound.antiquity.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A red blood droplet: it's flung out, falls under gravity, and vanishes the instant it hits the
 * ground — a little burst of blood from a wounded animal. Used by the bleed pulses and spear hits.
 */
@OnlyIn(Dist.CLIENT)
public class BloodDropParticle extends TextureSheetParticle {
    protected BloodDropParticle(ClientLevel level, double x, double y, double z,
                                double dx, double dy, double dz) {
        super(level, x, y, z);
        this.gravity = 0.4F;           // clearly falls under gravity (0.06 just hangs)
        this.setColor(0.45F, 0.0F, 0.0F); // dark blood red
        this.quadSize *= 2.0F;         // big, clearly-visible droplets
        this.lifetime = 60;
        this.hasPhysics = true;        // collides with the ground
        this.xd = dx;
        this.yd = dy + 0.08;           // slight upward burst, then gravity arcs it down
        this.zd = dz;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.onGround) {
            this.remove(); // disappears when it lands
        }
    }

    @OnlyIn(Dist.CLIENT)
    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z, double dx, double dy, double dz) {
            BloodDropParticle particle = new BloodDropParticle(level, x, y, z, dx, dy, dz);
            particle.pickSprite(sprites);
            return particle;
        }
    }
}
