package com.xfestudio.mydimension.builder;

import com.xfestudio.mydimension.builder.blueprint.BlueprintNames;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlueprintNamesTest {
    @Test
    void normalizesNfkcAndCaseInsensitiveCollisionKey() {
        assertEquals("abc", BlueprintNames.collisionKey("  ＡＢＣ  "));
    }

    @Test
    void rejectsTraversalAndPathCharacters() {
        assertThrows(IllegalArgumentException.class, () -> BlueprintNames.normalize(".."));
        assertThrows(IllegalArgumentException.class, () -> BlueprintNames.normalize("folder/name"));
        assertThrows(IllegalArgumentException.class, () -> BlueprintNames.normalize("bad:name"));
    }
}
