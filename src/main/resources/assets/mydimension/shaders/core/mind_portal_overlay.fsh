#version 150

uniform sampler2D Sampler0;

uniform vec2 ScreenSize;
uniform float PortalTime;
uniform float PortalAlpha;

in vec4 texProj0;

float hash21(vec2 value) {
    value = fract(value * vec2(123.34, 456.21));
    value += dot(value, value + 45.32);
    return fract(value.x * value.y);
}

float particle_layer(vec2 uv, float density, vec2 flow, float seed,
                     float size, float threshold) {
    vec2 grid = (uv + flow * PortalTime) * density;
    vec2 cell = floor(grid);
    vec2 local = fract(grid) - 0.5;

    float random = hash21(cell + vec2(seed, seed * 1.73));
    vec2 offset = fract(random * vec2(7.91, 13.37) + vec2(seed * 0.17, seed * 0.31));
    offset = (offset - 0.5) * 0.48;

    vec2 delta = local - offset;
    float square_distance = max(abs(delta.x), abs(delta.y));
    float core = 1.0 - smoothstep(size * 0.38, size, square_distance);
    float halo = 1.0 - smoothstep(size, size * 2.6, length(delta));
    float twinkle_phase = fract(PortalTime * (0.18 + seed * 0.012) + random);
    float twinkle = 0.62 + 0.38 * (1.0 - abs(twinkle_phase * 2.0 - 1.0));

    return step(threshold, random) * (core + halo * 0.20) * twinkle;
}

out vec4 fragColor;

void main() {
    vec2 screen_uv = texProj0.xy / texProj0.w;
    float aspect = ScreenSize.x / max(ScreenSize.y, 1.0);
    vec2 portal_uv = vec2((screen_uv.x - 0.5) * aspect + 0.5, screen_uv.y);

    vec2 background_uv = fract(portal_uv * vec2(0.78, 0.78)
        + vec2(PortalTime * 0.0035, -PortalTime * 0.0022));
    vec3 background = texture(Sampler0, background_uv).rgb
        * vec3(0.26, 0.14, 0.40) * 0.34;

    float far_particles = particle_layer(
        portal_uv, 11.0, vec2(0.0038, -0.0024), 2.7, 0.090, 0.82);
    float middle_particles = particle_layer(
        portal_uv, 18.0, vec2(-0.0050, 0.0032), 5.9, 0.066, 0.855);
    float near_particles = particle_layer(
        portal_uv, 26.0, vec2(0.0065, 0.0041), 9.4, 0.050, 0.89);

    vec3 color = vec3(0.0020, 0.0006, 0.0100) + background;
    color += vec3(0.48, 0.08, 0.95) * far_particles * 0.72;
    color += vec3(0.92, 0.20, 0.88) * middle_particles * 0.58;
    color += vec3(0.05, 0.68, 1.00) * near_particles * 0.42;

    fragColor = vec4(min(color, vec3(1.0)), PortalAlpha);
}
