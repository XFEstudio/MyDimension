# MyDimension

English | [Simplified Chinese](README.zh-hans.md)

A Mind-dimension mod for Minecraft 1.20.1 / Forge. It adds the Rift, five Minds, two-way Mind Portals, and optional Create train compatibility.

## Requirements and Dependencies

- Minecraft 1.20.1.
- Forge 47.x (the development environment uses 47.4.0).
- Create is optional; the rest of the mod works normally when it is not installed.
- Create 0.5.1 and 6.x for Minecraft 1.20.1 are explicitly supported. Train portal compatibility is enabled automatically when a supported version is detected.

## Main Features

### Rift

- Hold Shift and right-click while holding a Rift to open the action wheel.
- Select an action from the wheel, then right-click normally to perform it.
- Travel to private or shared Minds, send mobs, set Mind anchors, or visit another player's Minds after receiving access.
- When leaving the selected Mind with the same Rift, you return to the departure point recorded by that Rift.
- "Set Anchor" can only be used inside a Mind. It controls the destination used when sending mobs with a Rift, but does not affect player or portal destinations.
- Copying content from a shared Mind is limited to players in Creative mode. It overwrites blocks in the `3×3` chunks around the current coordinates in the private Mind, so check the target area first.

### Mind Dimensions

| Mind | Terrain |
| --- | --- |
| Ethereal Mind | Superflat: 60 layers of stone, 3 layers of dirt, and 3 layers of quartz blocks. |
| Mirror Mind | Uses the same terrain-generation settings and world seed as the Overworld. |
| Water Mind | Superflat ocean: 20 layers of stone, 5 layers of gravel, and 50 layers of water. |
| Nature Mind | Superflat grassland: 50 layers of stone, 9 layers of dirt, and 1 layer of grass blocks, with natural features enabled. |
| Soaring Mind | Large floating islands composed of several Overworld biomes. |

Mobs do not spawn naturally inside Mind dimensions. Players inside a Mind cannot be forcibly teleported by another player with `/tp` or `/teleport`.

### Mind Portals

Mind Portals provide two-way travel between the Overworld and a target Mind. Their surface uses purple, magenta, and subtle cyan particles to create an animated parallax effect that continues flowing and flickering during the portal transition.

1. In the Overworld, build a vertical `5×6` outer frame with a `3×4` opening.
2. Hold a Rift and use its action wheel to select a Mind travel target or another player's authorized Mind. Send Mob, Copy, and Set Anchor actions cannot be bound to a portal.
3. Right-click any Mind Portal Frame block with the Rift to activate the portal.
4. The mod automatically creates a paired portal at a safe location in the target Mind.
5. Remain in contact with the portal surface for about 2 seconds to travel. The destination portal leads back to the Overworld, and teleportation has a cooldown of about 4 seconds.

The frame layout is shown below. `F` is a Mind Portal Frame and `.` is the opening:

```text
F F F F F
F . . . F
F . . . F
F . . . F
F . . . F
F F F F F
```

A complete Overworld entrance requires 18 Mind Portal Frames. The portal's creator can select a new Rift target and right-click the frame again to update the connection; players in Creative mode are not restricted by ownership. The portal surface automatically teleports players only. Use the Rift's "Send Mob" action for other entities.

### Create Train Compatibility

With a supported version of Create installed, place tracks at both portal endpoints so they connect directly to the portal surface along the axis perpendicular to it. If the portal surface extends along the X axis, tracks must enter along the Z axis; if it extends along the Z axis, tracks must enter along the X axis. The paired portal is created automatically, but tracks are not generated at the destination.

After tracks are installed at both ends, trains can travel in either direction between the Overworld and the Mind, much like they travel through Nether portals.

The compatibility layer registers automatically when Create is detected. It supports the portal-track APIs used by both Create 0.5.1 and 6.x on Minecraft 1.20.1. The mod does not statically reference Create classes, so it starts and runs normally without Create installed.

## Recipes

### Rift

| Left | Center | Right |
| --- | --- | --- |
| Glass | Ender Pearl | Glass |
| Ender Pearl | Clock | Ender Pearl |
| Glass | Ender Pearl | Glass |

- Ingredients: 4× Glass, 4× Ender Pearl, and 1× Clock.
- Output: 1× Rift.
- Data recipe: [rift.json](src/main/resources/data/mydimension/recipes/rift.json).

### Mind Portal Frame

| Left | Center | Right |
| --- | --- | --- |
| Amethyst Shard | Nether Quartz | Amethyst Shard |
| Nether Quartz | Crying Obsidian | Nether Quartz |
| Amethyst Shard | Nether Quartz | Amethyst Shard |

- Ingredients: 4× Amethyst Shard, 4× Nether Quartz, and 1× Crying Obsidian.
- Output: 6× Mind Portal Frame.
- Data recipe: [mind_portal_frame.json](src/main/resources/data/mydimension/recipes/mind_portal_frame.json).

Building a complete `5×6` portal frame requires crafting this recipe three times. The total cost is 12× Amethyst Shard, 12× Nether Quartz, and 3× Crying Obsidian, producing exactly 18 Mind Portal Frames. The Mind Portal block itself has no crafting recipe; it is generated automatically when the frame is activated.

## Realmwright's Scepter and Resonant Supply Anchor

The Realmwright's Scepter can place or remove connected surface layers and construct from blueprints in any dimension. While held in the main hand, it extends block interaction range according to the server configuration (64 blocks by default), without increasing entity attack range. Its eight-neighbor surface traversal expands only across genuinely visible, connected surfaces in the direction hit by the crosshair; underground sections hidden by floors, backing blocks, or other full blocks are not previewed or modified. `Shift+middle click` opens the five-page tool menu, middle click switches between build and demolish modes, `Ctrl+right-click` selects the two blueprint corners, and `Ctrl+Z` / `Ctrl+Y` undo or redo. Chests, buttons, Supply Anchors, and other interactive blocks receive normal right-clicks first and do not display the surface preview; hold `Shift` to show the preview and perform a Scepter operation with right-click. While holding `Ctrl`, a blue outline shows the candidate point in real time. Scrolling during the same hold switches to air selection, and the mouse wheel adjusts distance along the view direction; releasing `Ctrl` resets the mode. The same method can place a blueprint anchor in midair. Every shortcut is also available from the menu.

- Building draws materials in this order: bound anchors → main inventory → hotbar → offhand. Positions missing materials remain as yellow ghosts; right-click any focused ghost to continue filling them. When the crosshair ray enters a yellow cell or the outline or interior volume of a blue selection/deployment box, it selects one unambiguous focus and thickens its outline. Connected missing-material cells use 26-neighbor connectivity and are emphasized as a group; the focused outline transitions smoothly over about 160 ms to four times its normal width. Deployment boxes take priority, while other objects select the nearest ray entry. Left-click cancels only the current focus, and cancelling a deployment does not clear the source selection. Normal build previews show green outlines without rendering actual blocks; yellow missing-material and blueprint projections use more opaque translucent models with a slight inset to prevent z-fighting between adjacent faces. Large previews cache entity QUADS outlines and projected models in `16³` sections. Six-sided blue-purple Rift ripples are used only for yellow missing-material projections. Preview range is at least 160 blocks and scales with the client's render distance up to 512 blocks; beyond 128 blocks, a level of detail preserves complete frames and missing-material ripples to avoid gaps at medium range and stalls while baking large blueprints.
- Manual surface building and demolition complete the configured batch limit in a single interaction instead of being split into a multi-tick queue. The Work page provides a per-Scepter History toggle, disabled by default. When disabled, operations do not create undo transactions, before-and-after block entity snapshots, or material ledgers, avoiding the most expensive batch-operation serialization; these new operations cannot be undone or redone. Full transaction semantics are retained only when History is enabled. The interaction entry point still coalesces and rejects simultaneous rapid duplicate clicks to prevent duplicate raycasts, BFS traversals, and supply scans. Automated blueprint construction continues to use its own queue and build limit.
- Demolition preserves normal drops only when the offhand tool has both the correct type and tier. Every successfully demolished block consumes one point of tool durability and produces no experience. If the offhand is empty or the tool type is wrong, the block is deleted directly without particles, the vanilla `2001` block-break effect, or creation of a drop list. Each batch operation plays only one representative block sound: building uses the block's placement sound, while demolition uses the original block's break sound.
- Place a Resonant Supply Anchor by sneaking and using it against the face of a container. It first accesses the Forge item capability on the target face, while also supporting `WorldlyContainer`, regular `Container`, and a configurable unsided-capability fallback. Placement against an ordinary non-container surface is rejected without consuming the item. If the container disappears, or a third-party bypass creates an invalid anchor, the anchor drops normally and retains its UUID and ACL. Cross-chunk revalidation wakes through a reverse index for the target chunk; it does not scan blocks, force-load chunks, or remain permanently ticking. Anchors are private by default and can be made public or assigned a whitelist. While a Scepter is held in the main hand, purple boxes are drawn only for Supply Anchors that are bound to that specific Scepter, in binding order, and located in the current dimension. The client intersects the Scepter's bound UUIDs with a lightweight index snapshot from the server instead of scanning nearby chunks. Changing items or Scepters, unbinding, changing dimensions, or leaving the world immediately clears or recalculates the cache.
- Blueprints are stored in the global client directory `<game directory>/mydimension/blueprints/` with the `.mindbp` extension, and can be imported or exported across saves and servers. Automated construction uses the Scepter's current Build Batch Limit as its per-tick budget. Loaded targets across chunk boundaries are consolidated into a single supply scan and history commit; unloaded targets are stably grouped by chunk and leased temporarily, avoiding the previous 64-block throttling and row-by-row traversal between chunks. The server revalidates dimensions, permissions, materials, target blocks, and complete block entity NBT.
- Holding `Alt` immediately opens a dark-purple, Rift-themed radial wheel. All ten operations have dedicated icons: axis flips, rotation, offsets, reset, copy selection, and save. The selected sector glows cyan-purple, the center displays only an enlarged version of the current icon, and the current action hint appears outside the wheel. Releasing `Alt` closes the wheel or confirms the adjustment according to the current state. The Copy Selection and Save icons remain available whenever a complete source selection exists. After cancelling a moving copy preview, Copy Selection can immediately create another preview from the retained blue source selection. Server-side selections no longer expire through inactivity; they are cleared only when a new selection begins, they are explicitly cancelled, the Scepter or dimension changes, the player leaves, or the server stops. In the save dialog, the block-state policy occupies the first row, with Save and Cancel side by side on the second row. After saving, the same dialog waits for the server capture and writes it directly to the local blueprint library instead of opening a second save screen.
- Server configuration is located under `[builder]`. Setting `enabled=false` disables the recipes, Creative inventory entries, extended reach, construction, binding, remote container access, and temporary chunk loading, but does not delete items, NBT, history, or the client-side blueprint library.
- Both the Scepter and Supply Anchor use native Rift-themed 3D models. The Scepter is made of black-gray void stone, blue-purple Rift crystals, a twisted shaft, and fractured crystal clusters, replacing brass, gears, and wrench-like mechanical styling. It is displayed at an angle of about 45° in the inventory, while its first-person model is smaller and shifted to the right to preserve visibility. Switching modes replaces only the floating core's counter-rotating cyan-blue or blue-purple vortex animation; it does not refresh the entire model geometry or material. The Supply Anchor uses a 3D side view as its item model and non-occluding geometry in the world, preventing the inset model from clipping the surface of adjacent blocks.

Default recipes:

| Realmwright's Scepter | Center | Right |
| --- | --- | --- |
| Echo Shard | Nether Star | Echo Shard |
| Netherite Ingot | Rift | Netherite Ingot |
| Crying Obsidian | Blaze Rod | Crying Obsidian |

| Resonant Supply Anchor | Center | Right |
| --- | --- | --- |
| Echo Shard | Eye of Ender | Echo Shard |
| Hopper | Ender Chest | Hopper |
| Mind Portal Frame | Lodestone | Mind Portal Frame |

The mod provides the `mydimension:construction_protected` and `mydimension:transaction_unsafe` data-pack tags. Third-party machines that modify external networks, global `SavedData`, asynchronous tasks, or custom entities should be excluded through a compatibility adapter or the `transaction_unsafe` tag, because Forge has no general-purpose API for undoing such external mod side effects.

## Building

```powershell
.\gradlew.bat build
```

Release artifacts are written to `build/libs/`:

- `mydimension-<version>.jar`: full build with private Minds, shared Minds, team access, and shared-Mind copying.
- `mydimension-<version>-no-private.jar`: shared Minds only, without private Minds, team access, or shared-Mind copying.
