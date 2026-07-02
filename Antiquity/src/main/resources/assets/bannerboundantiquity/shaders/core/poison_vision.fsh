#version 150

uniform sampler2D Sampler0;   // the captured scene (this frame)
uniform sampler2D Sampler1;   // the previous final frame (afterimage history)

uniform vec2 ScreenSize;
uniform float Desat;          // 0..1 toward grayscale
uniform float BlurRadius;     // px (0 = no blur) — wolfsbane "vision failing"
uniform float Vignette;       // 0..1 edge-darkening strength
uniform float ColdTint;       // 0..1 tint amount
uniform vec3 TintColor;       // tint hue (wolfsbane cold-blue / belladonna sickly-violet)
uniform float Warp;           // belladonna UV swim (the world breathing/swimming)
uniform float Chroma;         // belladonna chromatic split (double-vision edges)
uniform float Smear;          // 0..1 afterimage persistence
uniform float Goo;            // 0..1 green vomit splatter on the screen (Sea-of-Thieves style)
uniform float GooSeed;        // per-splat random offset so every hit looks different
uniform float PoisonTime;     // game-time for the slow animation

in vec2 texCoord;
out vec4 fragColor;

float gooHash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float gooNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = gooHash(i);
    float b = gooHash(i + vec2(1.0, 0.0));
    float c = gooHash(i + vec2(0.0, 1.0));
    float d = gooHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

vec3 sampleScene(vec2 uv) {
    if (BlurRadius > 0.01) {
        // 3x3 kernel (9 taps) — the wider offset from BlurRadius carries the strength, so we don't
        // need a big tap count. Keeps the fullscreen pass cheap at 4K (a 5x5 stalled the GPU).
        vec2 px = 1.0 / ScreenSize;
        vec3 sum = vec3(0.0);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                sum += texture(Sampler0, uv + vec2(float(x), float(y)) * BlurRadius * px).rgb;
            }
        }
        return sum / 9.0;
    }
    return texture(Sampler0, uv).rgb;
}

void main() {
    // Belladonna warp — the world swims/breathes (a low-freq UV wobble).
    vec2 uv = texCoord;
    if (Warp > 0.001) {
        uv.x += sin(texCoord.y * 16.0 + PoisonTime * 0.35) * 0.008 * Warp;
        uv.y += cos(texCoord.x * 14.0 + PoisonTime * 0.31) * 0.008 * Warp;
    }

    // Belladonna chromatic split — double-vision colour fringing.
    vec3 c;
    if (Chroma > 0.001) {
        float off = 0.004 * Chroma;
        c.r = sampleScene(uv + vec2(off, 0.0)).r;
        c.g = sampleScene(uv).g;
        c.b = sampleScene(uv - vec2(off, 0.0)).b;
    } else {
        c = sampleScene(uv);
    }

    // Desaturate, then a per-poison tint (cold for wolfsbane, sickly for belladonna).
    float g = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(c, vec3(g), clamp(Desat, 0.0, 1.0));
    c = mix(c, c * TintColor, clamp(ColdTint, 0.0, 1.0));

    // Smooth radial vignette (ellipse, no aspect-correct) with a slow breathe.
    vec2 vu = texCoord - 0.5;
    float d = length(vu);
    float breathe = 0.05 * sin(PoisonTime * 0.10);
    float inner = 0.16;
    float outer = max(inner + 0.05, 0.95 - Vignette * 0.55 + breathe);
    float v = smoothstep(inner, outer, d) * Vignette;
    c *= (1.0 - v);

    // Afterimage feedback (motion trails).
    vec3 hist = texture(Sampler1, texCoord).rgb;
    c = mix(c, hist, clamp(Smear, 0.0, 1.0));

    // Vomit goo on the screen (someone retched in your face) — lumpy green globs that slowly ooze down
    // and fade. Drawn last so it sits on top of everything (and doesn't get caught in the smear trail).
    if (Goo > 0.001) {
        vec2 off = vec2(GooSeed, GooSeed * 1.7);       // unique layout per splat
        // Pixelate: snap to a coarse SQUARE grid so the goo reads as chunky Minecraft pixels.
        float PIX = 84.0;                              // goo-pixels across the screen width
        vec2 grid = vec2(PIX, PIX * ScreenSize.y / ScreenSize.x);
        vec2 gp = (floor(texCoord * grid) + 0.5) / grid + off;
        // fbm: the low octave makes connected continents; the higher octaves roughen the coastline into
        // ragged lumps so the hard step doesn't produce one long clean (stair-stepped) diagonal edge.
        float n = gooNoise(gp * 2.5) * 0.50
                + gooNoise(gp * 6.0) * 0.27
                + gooNoise(gp * 13.0) * 0.15
                + gooNoise(gp * 28.0) * 0.08;
        // Splashed from the rim inward: clearer in the middle, heavier toward the screen edges.
        float edge = smoothstep(0.25, 0.70, length(texCoord - 0.5));
        // Hard step on the pixel grid = crisp blocky edges; the ragged fbm keeps the coast lumpy.
        float mask = step(0.58, n + edge * 0.18) * Goo;
        vec3 gooCol = mix(vec3(0.16, 0.30, 0.05), vec3(0.52, 0.80, 0.14), n);
        c = mix(c, gooCol, clamp(mask, 0.0, 0.96));
    }

    fragColor = vec4(c, 1.0);
}
