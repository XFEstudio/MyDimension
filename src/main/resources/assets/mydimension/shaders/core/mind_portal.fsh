#version 150

#moj_import <matrix.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform float GameTime;
uniform int EndPortalLayers;

in vec4 texProj0;

const vec3[] PARTICLE_COLORS = vec3[](
    vec3(0.42, 0.08, 0.96),
    vec3(0.82, 0.16, 1.00),
    vec3(0.06, 0.70, 1.00),
    vec3(0.54, 0.10, 0.80),
    vec3(0.95, 0.30, 0.84),
    vec3(0.16, 0.32, 0.92)
);

const mat4 SCALE_TRANSLATE = mat4(
    0.5, 0.0, 0.0, 0.25,
    0.0, 0.5, 0.0, 0.25,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0
);

float hash21(vec2 value) {
    value = fract(value * vec2(123.34, 456.21));
    value += dot(value, value + 45.32);
    return fract(value.x * value.y);
}

mat4 mind_portal_layer(float layer) {
    mat4 translate = mat4(
        1.0, 0.0, 0.0, 11.0 / layer,
        0.0, 1.0, 0.0, 7.0 / layer,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
    );

    mat2 rotate = mat2_rotate_z(radians((layer * layer * 137.0 + layer * 29.0) * 1.35));
    mat2 scale = mat2(2.2 + layer * 0.42);

    return mat4(scale * rotate) * translate * SCALE_TRANSLATE;
}

float particle_field(vec2 uv, float density, float seed, float size,
                     float threshold, float time) {
    vec2 grid = uv * density;
    vec2 cell = floor(grid);
    vec2 local = fract(grid) - 0.5;

    float random = hash21(cell + vec2(seed, seed * 1.73));
    vec2 offset = vec2(
        hash21(cell + vec2(seed * 2.31, 17.17)),
        hash21(cell + vec2(31.71, seed * 3.13))
    );
    offset = (offset - 0.5) * 0.54;

    vec2 delta = local - offset;
    float squareDistance = max(abs(delta.x), abs(delta.y));
    float radialDistance = length(delta);
    float core = 1.0 - smoothstep(size * 0.38, size, squareDistance);
    float halo = 1.0 - smoothstep(size, size * 3.0, radialDistance);

    float verticalFlare = (1.0 - smoothstep(size * 0.20, size * 0.52, abs(delta.x)))
        * (1.0 - smoothstep(size * 1.2, size * 3.8, abs(delta.y)));
    float horizontalFlare = (1.0 - smoothstep(size * 0.20, size * 0.52, abs(delta.y)))
        * (1.0 - smoothstep(size * 1.2, size * 3.8, abs(delta.x)));
    float flare = (verticalFlare + horizontalFlare) * step(0.965, random) * 0.28;

    float twinkle = 0.68 + 0.32 * sin(time * (1.6 + seed * 0.11) + random * 6.2831853);
    return step(threshold, random) * (core + halo * 0.22 + flare) * twinkle;
}

out vec4 fragColor;

void main() {
    float time = GameTime * 400.0;

    vec4 backgroundCoords = texProj0;
    backgroundCoords.xy += vec2(time * 0.004, -time * 0.0025) * backgroundCoords.w;
    vec3 background = textureProj(Sampler0, backgroundCoords).rgb
        * vec3(0.28, 0.18, 0.42) * 0.34;

    vec4 hazeCoords = texProj0 * mind_portal_layer(7.0);
    hazeCoords.xy += vec2(-time * 0.0015, time * 0.0020) * hazeCoords.w;
    vec3 haze = textureProj(Sampler1, hazeCoords).rgb
        * vec3(0.22, 0.05, 0.34) * 0.10;

    vec3 color = vec3(0.0015, 0.0005, 0.0080) + background + haze;
    for (int i = 0; i < EndPortalLayers && i < 6; i++) {
        float layer = float(i + 1);
        vec4 projected = texProj0 * mind_portal_layer(layer);
        vec2 uv = projected.xy / projected.w;
        vec2 direction = normalize(vec2(
            cos(layer * 2.13 + 0.35),
            sin(layer * 1.77 + 0.80)
        ));
        uv += direction * time * (0.030 + layer * 0.0045);

        float depth = float(i) / max(float(EndPortalLayers - 1), 1.0);
        float density = mix(9.0, 24.0, depth);
        float size = mix(0.115, 0.052, depth);
        float threshold = 0.80 + depth * 0.055;
        if (i == 2) {
            threshold += 0.07;
        }

        float particle = particle_field(
            uv, density, layer * 4.71, size, threshold, time
        );
        float depthBrightness = mix(0.82, 0.42, depth);
        color += PARTICLE_COLORS[i] * particle * depthBrightness;
    }

    fragColor = vec4(min(color, vec3(1.0)), 1.0);
}
