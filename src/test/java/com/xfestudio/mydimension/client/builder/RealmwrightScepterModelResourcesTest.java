package com.xfestudio.mydimension.client.builder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the JSON models rendered by {@link RealmwrightScepterRenderer}. */
class RealmwrightScepterModelResourcesTest {
    private static final double MIN_ELEMENT_COORDINATE = -16.0D;
    private static final double MAX_ELEMENT_COORDINATE = 32.0D;
    private static final List<String> MODEL_NAMES = List.of(
            "realmwright_scepter",
            "realmwright_scepter_base",
            "realmwright_scepter_build",
            "realmwright_scepter_demolish",
            "realmwright_scepter_extension",
            "realmwright_scepter_ring_lower",
            "realmwright_scepter_ring_upper",
            "realmwright_scepter_floating",
            "realmwright_scepter_floating_secondary"
    );

    @Test
    void everyScepterModelStaysInsideVanillaBakeBounds() throws IOException {
        for (String modelName : MODEL_NAMES) {
            JsonObject model = loadModel(modelName);
            if (!model.has("elements")) continue;

            for (JsonElement rawElement : model.getAsJsonArray("elements")) {
                JsonObject element = rawElement.getAsJsonObject();
                String elementName = element.has("name")
                        ? element.get("name").getAsString() : "<unnamed>";
                String description = modelName + ":" + elementName;

                assertVectorInsideBakeBounds(element.getAsJsonArray("from"), description + " from");
                assertVectorInsideBakeBounds(element.getAsJsonArray("to"), description + " to");
                assertOrdered(element.getAsJsonArray("from"), element.getAsJsonArray("to"), description);
                if (element.has("rotation")) {
                    assertVectorInsideBakeBounds(
                            element.getAsJsonObject("rotation").getAsJsonArray("origin"),
                            description + " rotation origin");
                }
            }
        }
    }

    @Test
    void everyScepterParentAndTextureReferenceResolves() throws IOException {
        Map<String, JsonObject> models = new LinkedHashMap<>();
        for (String modelName : MODEL_NAMES) {
            models.put(modelName, loadModel(modelName));
        }

        Map<String, BufferedImage> decodedTextures = new HashMap<>();
        for (Map.Entry<String, JsonObject> entry : models.entrySet()) {
            String modelName = entry.getKey();
            JsonObject model = entry.getValue();
            if (model.has("parent")) {
                String parent = model.get("parent").getAsString();
                if (parent.startsWith("mydimension:item/")) {
                    String parentName = parent.substring("mydimension:item/".length());
                    assertTrue(models.containsKey(parentName),
                            () -> modelName + " references missing parent " + parent);
                } else {
                    assertTrue(parent.startsWith("builtin/") || parent.startsWith("minecraft:"),
                            () -> modelName + " references unexpected external parent " + parent);
                }
            }

            Map<String, String> textures = resolvedTextures(modelName, models, new HashMap<>());
            if (model.has("elements")) {
                for (JsonElement rawElement : model.getAsJsonArray("elements")) {
                    JsonObject element = rawElement.getAsJsonObject();
                    if (!element.has("faces")) continue;
                    for (Map.Entry<String, JsonElement> face
                            : element.getAsJsonObject("faces").entrySet()) {
                        String reference = face.getValue().getAsJsonObject()
                                .get("texture").getAsString();
                        assertTrue(reference.startsWith("#"),
                                () -> modelName + " has a non-variable face texture " + reference);
                        String key = reference.substring(1);
                        assertTrue(textures.containsKey(key),
                                () -> modelName + " face references undefined texture #" + key);
                        assertTextureExists(modelName, key, textures, decodedTextures);
                    }
                }
            }
            for (String key : textures.keySet()) {
                assertTextureExists(modelName, key, textures, decodedTextures);
            }
        }

        assertFalse(decodedTextures.isEmpty(), "the scepter model set must use actual texture resources");
    }

    private static JsonObject loadModel(String modelName) throws IOException {
        String path = "assets/mydimension/models/item/" + modelName + ".json";
        try (InputStream stream = resource(path)) {
            return JsonParser.parseReader(new java.io.InputStreamReader(
                    stream, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static Map<String, String> resolvedTextures(
            String modelName, Map<String, JsonObject> models,
            Map<String, Map<String, String>> cache) {
        Map<String, String> cached = cache.get(modelName);
        if (cached != null) return cached;

        JsonObject model = Objects.requireNonNull(models.get(modelName));
        Map<String, String> result = new LinkedHashMap<>();
        if (model.has("parent")) {
            String parent = model.get("parent").getAsString();
            if (parent.startsWith("mydimension:item/")) {
                result.putAll(resolvedTextures(
                        parent.substring("mydimension:item/".length()), models, cache));
            }
        }
        if (model.has("textures")) {
            for (Map.Entry<String, JsonElement> texture
                    : model.getAsJsonObject("textures").entrySet()) {
                result.put(texture.getKey(), texture.getValue().getAsString());
            }
        }
        cache.put(modelName, result);
        return result;
    }

    private static void assertTextureExists(
            String modelName, String key, Map<String, String> textures,
            Map<String, BufferedImage> decodedTextures) throws IOException {
        String value = textures.get(key);
        int visited = 0;
        while (value.startsWith("#")) {
            assertTrue(++visited <= textures.size(),
                    () -> modelName + " contains a cyclic texture reference starting at #" + key);
            String referencedKey = value.substring(1);
            assertTrue(textures.containsKey(referencedKey),
                    () -> modelName + " texture #" + key
                            + " references undefined texture #" + referencedKey);
            value = textures.get(referencedKey);
        }

        String[] identifier = value.split(":", 2);
        String namespace = identifier.length == 2 ? identifier[0] : "minecraft";
        String texturePath = identifier.length == 2 ? identifier[1] : identifier[0];
        if (!"mydimension".equals(namespace)) return;

        String resourcePath = "assets/" + namespace + "/textures/" + texturePath + ".png";
        if (!decodedTextures.containsKey(resourcePath)) {
            try (InputStream stream = resource(resourcePath)) {
                BufferedImage image = ImageIO.read(stream);
                assertNotNull(image, () -> resourcePath + " is not a decodable PNG texture");
                assertTrue(image.getWidth() > 0 && image.getHeight() > 0,
                        () -> resourcePath + " has invalid dimensions");
                decodedTextures.put(resourcePath, image);
            }
        }
    }

    private static InputStream resource(String path) {
        InputStream stream = RealmwrightScepterModelResourcesTest.class
                .getClassLoader().getResourceAsStream(path);
        return Objects.requireNonNull(stream, "missing classpath resource " + path);
    }

    private static void assertVectorInsideBakeBounds(JsonArray vector, String description) {
        assertNotNull(vector, description + " is missing");
        assertTrue(vector.size() == 3, description + " must contain exactly three coordinates");
        for (JsonElement rawCoordinate : vector) {
            double coordinate = rawCoordinate.getAsDouble();
            assertTrue(Double.isFinite(coordinate)
                            && coordinate >= MIN_ELEMENT_COORDINATE
                            && coordinate <= MAX_ELEMENT_COORDINATE,
                    () -> description + " coordinate " + coordinate
                            + " is outside Minecraft's [-16, 32] item-model bake range");
        }
    }

    private static void assertOrdered(JsonArray from, JsonArray to, String description) {
        for (int axis = 0; axis < 3; axis++) {
            double lower = from.get(axis).getAsDouble();
            double upper = to.get(axis).getAsDouble();
            int checkedAxis = axis;
            assertTrue(lower <= upper,
                    () -> description + " has from > to on axis " + checkedAxis);
        }
    }
}
