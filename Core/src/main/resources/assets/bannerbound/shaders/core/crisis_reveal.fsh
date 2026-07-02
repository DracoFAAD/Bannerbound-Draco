#version 150

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float RevealProgress;
uniform float RevealTime;
uniform float LayerSeed;
uniform float LayerAlpha;
uniform float Aspect;

in vec2 texCoord0;

out vec4 fragColor;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += vec2(dot(p, p + vec2(45.32)));
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float value = 0.0;
    float amp = 0.52;
    value += noise(p) * amp;
    p = p * 2.04 + vec2(7.2, 2.8);
    amp *= 0.52;
    value += noise(p) * amp;
    p = p * 2.07 + vec2(3.5, 9.1);
    amp *= 0.52;
    value += noise(p) * amp;
    return value / 0.9308;
}

float angleDistance(float a, float b) {
    float d = mod(a - b + 3.14159265, 6.2831853);
    return abs(d - 3.14159265);
}

float liquidMask(vec2 uv, float progress, float time, float seed) {
    vec2 center = vec2(0.5 + (hash(vec2(seed, 1.7)) - 0.5) * 0.05,
                       0.52 + (hash(vec2(seed, 9.1)) - 0.5) * 0.035);
    vec2 p = vec2((uv.x - center.x) * Aspect, uv.y - center.y);
    float radius = length(p);
    float angle = atan(p.y, p.x);

    vec2 warp = vec2(
        fbm(uv * 3.4 + vec2(seed * 2.1 + time * 0.035, -time * 0.025)),
        fbm(uv * 3.4 + vec2(seed * 3.7 - time * 0.030, time * 0.040))
    ) - 0.5;
    float broad = fbm(uv * 7.0 + warp * 1.15 + vec2(seed * 4.0, time * 0.055));
    float tooth = noise(uv * 46.0 + vec2(time * 0.42, seed * 12.0));

    float flow = 0.0;
    for (int i = 0; i < 7; i++) {
        float fi = float(i);
        float h = hash(vec2(fi + seed * 3.0, seed * 11.0));
        float laneAngle = fi * 2.3999632 + seed * 0.84 + (h - 0.5) * 0.72;
        float curl = sin(radius * (6.2 + h * 5.0) + time * (0.18 + h * 0.22) + fi) * (0.18 + h * 0.13);
        float lane = 1.0 - smoothstep(0.020, 0.092 + progress * 0.036, angleDistance(angle, laneAngle + curl));
        float reach = smoothstep(radius - 0.035, radius + 0.120, progress * (1.0 + h * 0.32));
        flow = max(flow, lane * reach * (0.09 + h * 0.16));
    }

    float blooms = 0.0;
    for (int i = 0; i < 8; i++) {
        float fi = float(i);
        float h1 = hash(vec2(fi * 1.7 + seed, 13.0 + seed));
        float h2 = hash(vec2(fi * 3.1 + seed, 29.0 + seed));
        float dist = 0.08 + h1 * 0.66;
        float delay = dist * 0.58 + h2 * 0.15;
        float bp = smoothstep(delay, delay + 0.18, progress);
        float ba = fi * 2.3999632 + seed * 0.92 + (h2 - 0.5) * 0.95;
        vec2 b = vec2(cos(ba) * dist, sin(ba) * dist * 0.72);
        float br = (0.025 + h2 * 0.075) * bp;
        float bd = length(p - b);
        blooms = max(blooms, (1.0 - smoothstep(br * 0.72, br + 0.012, bd)) * (0.08 + h1 * 0.13));
    }

    float front = progress * (1.06 + progress * 0.22);
    float paper = (broad - 0.5) * 0.18 + (tooth - 0.5) * 0.050;
    float field = front + flow + blooms + paper - radius;
    return smoothstep(-0.012, 0.018, field);
}

void main() {
    vec4 art = texture(Sampler0, texCoord0) * ColorModulator;
    if (art.a <= 0.0) {
        discard;
    }

    float progress = clamp(RevealProgress, 0.0, 1.0);
    float mask = liquidMask(texCoord0, progress, RevealTime, LayerSeed);
    float alphaMask = smoothstep(0.09, 0.19, mask);
    if (alphaMask <= 0.002) {
        discard;
    }

    float edge = 1.0 - smoothstep(0.16, 0.42, mask);
    float granulation = noise(texCoord0 * 92.0 + vec2(LayerSeed * 8.0, RevealTime * 0.26));
    float pigment = clamp(0.98 + mask * 0.07 - edge * 0.14 + (granulation - 0.5) * 0.08, 0.72, 1.05);
    art.rgb *= pigment;
    art.rgb = mix(art.rgb * vec3(0.68, 0.61, 0.52), art.rgb, smoothstep(0.12, 0.62, mask));
    art.a *= alphaMask * LayerAlpha;
    fragColor = art;
}
