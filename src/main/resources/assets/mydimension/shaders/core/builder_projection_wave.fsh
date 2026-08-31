#version 150

uniform float PreviewTime;
uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 faceUv;

out vec4 fragColor;

float hash21(vec2 value) {
    value = fract(value * vec2(123.34, 456.21));
    value += dot(value, value + 45.32);
    return fract(value.x * value.y);
}

void main() {
    vec2 centered = faceUv - 0.5;
    float angle = atan(centered.y, centered.x);
    float radius = length(centered);

    // A slightly broken radial field reads as a flat rift rather than a
    // perfectly mechanical target reticle.
    float distortion = sin(angle * 5.0 + PreviewTime * 0.72) * 0.026
        + sin(angle * 9.0 - PreviewTime * 0.51) * 0.012;
    float phase = (radius + distortion) * 43.0 - PreviewTime * 4.6;
    float primary = pow(0.5 + 0.5 * sin(phase), 7.0);
    float secondary = pow(0.5 + 0.5 * sin(phase * 0.58 + angle * 2.0), 10.0);

    vec2 cell = floor(faceUv * 13.0);
    float mote = step(0.92, hash21(cell))
        * (0.55 + 0.45 * sin(PreviewTime * 2.2 + hash21(cell + vec2(7.0)) * 6.28318));
    float rim = 1.0 - smoothstep(0.34, 0.53, radius);
    float energy = primary * 0.72 + secondary * 0.48 + mote * 0.20;

    vec3 violet = vec3(0.42, 0.04, 1.00);
    vec3 cyan = vec3(0.02, 0.72, 1.00);
    vec3 color = mix(violet, cyan, primary * 0.72 + secondary * 0.28);
    color += vec3(0.32, 0.04, 0.46) * rim * 0.24;
    color += texture(Sampler0, fract(faceUv * 0.72 + PreviewTime * vec2(0.006, -0.004))).rgb
        * vec3(0.18, 0.12, 0.30);

    float alpha = vertexColor.a * (0.075 + energy * 0.30);
    fragColor = vec4(color * vertexColor.rgb, alpha);
}
