package com.xfestudio.mydimension.client.builder;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlueprintAltActionControllerTest {
    @AfterEach
    void restoreNoopBridge() {
        BuilderClientServices.install(BuilderClientBridge.NOOP);
    }

    @Test
    void saveRemainsOnWheelAfterExecutionAndScreenClose() {
        AtomicReference<BuilderClientCommand> sent = new AtomicReference<>();
        BuilderClientServices.install(new BuilderClientBridge() {
            @Override
            public void send(BuilderClientCommand command) {
                sent.set(command);
            }
        });
        BlueprintAltActionController controller = new BlueprintAltActionController();

        // One upward wheel step wraps from FLIP_X to the final SAVE sector.
        assertTrue(controller.scroll(1.0D));
        assertEquals(BlueprintAltActionController.Action.SAVE, controller.highlighted());
        assertTrue(controller.activateOrConfirm());
        assertEquals(BlueprintAltActionController.Action.SAVE,
                assertInstanceOf(BuilderClientCommand.ExecuteBlueprintAction.class, sent.get()).action());

        // Opening/closing the save dialogs resets visibility, not the action list or selection.
        controller.reset();
        controller.updateVisibility(true, true);
        assertEquals(BlueprintAltActionController.Phase.NAVIGATION, controller.phase());
        assertEquals(BlueprintAltActionController.Action.SAVE, controller.highlighted());
        assertTrue(controller.actions().contains(BlueprintAltActionController.Action.SAVE));
    }

    @Test
    void onlyTwoCornerSelectionIsSaveable() {
        BlockPos first = new BlockPos(1, 2, 3);
        assertTrue(!new BuilderPreviewState.Selection(null, first, null).complete());
        assertTrue(new BuilderPreviewState.Selection(null, first, first.offset(4, 5, 6)).complete());
    }

    @Test
    void copySelectionIsAOneShotWheelActionBeforeSave() {
        AtomicReference<BuilderClientCommand> sent = new AtomicReference<>();
        BuilderClientServices.install(new BuilderClientBridge() {
            @Override
            public void send(BuilderClientCommand command) {
                sent.set(command);
            }
        });
        BlueprintAltActionController controller = new BlueprintAltActionController();

        // SAVE remains the final sector; a second upward step reaches the new action before it.
        assertTrue(controller.scroll(1.0D));
        assertEquals(BlueprintAltActionController.Action.SAVE, controller.highlighted());
        assertTrue(controller.scroll(1.0D));
        assertEquals(BlueprintAltActionController.Action.COPY_SELECTION, controller.highlighted());
        assertFalse(controller.highlighted().repeatable());
        assertEquals("action.mydimension.realmwright.blueprint.copy_selection",
                controller.highlighted().translationKey());

        assertTrue(controller.activateOrConfirm());
        assertEquals(BlueprintAltActionController.Action.COPY_SELECTION,
                assertInstanceOf(BuilderClientCommand.ExecuteBlueprintAction.class, sent.get()).action());
        assertEquals(BlueprintAltActionController.Phase.NAVIGATION, controller.phase());
        assertEquals(List.of(BlueprintAltActionController.Action.RESET,
                        BlueprintAltActionController.Action.COPY_SELECTION,
                        BlueprintAltActionController.Action.SAVE),
                controller.actions().subList(controller.actions().size() - 3, controller.actions().size()));
    }
}
