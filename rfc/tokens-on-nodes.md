# Tokens on nodes: spec and plan

Branch: `tokens-on-nodes`. Status: draft, no code written yet.

## 1. Goal

Let a board, group or component instance choose its own token theme, so that
one component can change many token values at once.

Example: a single `Button` component binds its fill to `button.bg`, its stroke
to `button.border` and its text color to `button.fg`. Each instance then picks
`Button: Primary` or `Button: Secondary`, and all three values follow. No
variant per style is needed. The same pattern applies to inputs, icons, cards,
or a whole board set to `Mode: Dark`.

Today the active themes and sets live at file level only, so every shape in
the file sees the same token values.

Tracked upstream in [#11278](https://github.com/penpot/penpot/issues/11278)
("Apply tokens in different app scopes", labelled *needs definition*, no
maintainer position yet). The issue asks for themes at Page, Board, Component,
Variant and instance level, most specific wins (Variant > Component > Board >
Page > File). Our comment there describes the target: a button with only
`default` and `withIcon` variants, and sets `buttonPrimary`,
`buttonSecondary`, `buttonError` picked per instance, applied to the node and
its children "kind of CSS3" (CSS variables set by a selector, inherited by
children). That is what this spec models.

## 2. How Figma does it

Figma maps the same idea onto variables:

| Figma | Penpot today |
|---|---|
| Variable collection | Theme group (`TokenTheme :group`) |
| Mode of a collection | Theme inside a group (one active per group) |
| Variable | Token (name resolved through active sets) |
| Mode set on page / frame / instance ("explicit mode") | **missing** |

In Figma, any page, frame, section or instance can set an explicit mode per
collection. Nodes left on "Auto" inherit the mode of their nearest ancestor
that sets one, up to the page, then the collection default. The plugin API
exposes this as `explicitVariableModes` (set on the node) and
`resolvedVariableModes` (explicit modes of the node and its ancestors).
Known limits in Figma: nested instances could not set modes for a long time,
and a mode pinned inside a main component wins over the mode of the frame the
instance sits in.

Tokens Studio maps theme groups to Figma collections and themes to modes, which
confirms that Penpot theme groups are the right unit to override per node.

The DTCG resolver draft follows the same shape: "modifiers" (≈ theme groups)
with "contexts" (≈ themes).

## 3. What the code does today

Facts that drive the design:

- `TokensLib` (`common/src/app/common/types/tokens_lib.cljc`) holds sets and
  themes. A theme has a `:group` (empty string for top-level themes) and a set
  of set names. `activate-theme` in `common/src/app/common/files/tokens.cljc`
  keeps at most one active theme per group.
- `TokensStatus` (`common/src/app/common/types/tokens_status.cljc`) is stored
  in file data as `:tokens-status` and holds `active-theme-ids` and
  `active-set-ids`. It is file-wide.
- `cfo/get-tokens-in-active-sets` merges the tokens of the active sets in lib
  set order (later set wins).
- Shapes store token **names** in `:applied-tokens`, and also store the
  **concrete resolved values** in their normal attrs (`:fills`, `:r1`…).
  The renderer, viewer, exporter and backend only read concrete values.
- `propagate-workspace-tokens`
  (`frontend/src/app/main/data/workspace/tokens/propagation.cljs`) resolves the
  active tokens once (Style Dictionary or tokenscript), then walks every page
  and rewrites the concrete attrs of every shape with applied tokens.
- Token writes use `:ignore-touched true`, so a component copy whose concrete
  values differ from its main is **not** marked as overridden.
- After component instantiation and library sync the frontend already calls
  `propagate-workspace-tokens` (`frontend/src/app/main/data/workspace/libraries.cljs`
  around lines 660, 846, 1118, 1370), so a copy gets values from the current
  file, not from the library.
- Pages already have one token link: `:background-token`.

Main consequence: since shapes keep concrete values, per-node themes only
change **how values are resolved before they are written**. Renderer,
exporter, viewer and backend need no change beyond the schema.

## 4. Proposed model

### 4.1 New attribute

Add an optional attr `:token-themes` to shapes (boards, groups, component
instances; any shape is fine, it only matters if it has children or applied
tokens) and to pages:

```clojure
;; theme group name -> theme name
{:token-themes {"Button" "Secondary"
                "Mode"   "Dark"}}
```

- Absent attr or absent group = inherit ("Auto" in Figma).
- Keys and values are names, not ids, for the same reason `:applied-tokens`
  uses names: a component from a shared library must work in a file whose
  themes have the same names but other ids.
- The empty string key `""` targets top-level themes (no group).
- Schema: `[:map-of :string :string]` in `common/src/app/common/types/shape.cljc`
  and `common/src/app/common/types/page.cljc`. No migration: absent means
  today's behaviour.

### 4.2 Resolution rule

For a shape `S` on page `P`:

1. Collect overrides from `P`, then each ancestor of `S` from the root down,
   then `S` itself. Per group, keep the nearest choice and remember its
   depth (page = 0, then +1 per level).
2. **Base layer.** Take the file active sets (`TokensStatus`) and remove the
   sets of every theme in an overridden group, except sets still used by an
   active theme of a group that is not overridden. Works the same whether the
   file uses themes or bare sets.
3. **Override layers.** For each overridden group whose theme exists, take the
   sets of the chosen theme.
4. **Merge as a cascade**, later wins on the same token name:
   base layer (lib set order), then override layers from the farthest depth to
   the nearest (lib set order within one depth).

Step 4 is the CSS-like part. Today all active sets merge in lib set order, so
a set's place in the list decides which value wins. Here the **nearest node
decides**. This matters only when two groups define the same token name (see
4.4); with component-specific names there is no clash and the order changes
nothing.

Overrides naming a group or theme that does not exist are ignored and shown as
"missing" in the UI.

Example: file has `Button/Primary` active. Instance root sets
`{"Button" "Secondary"}`. Every shape inside it resolves `button.bg` from the
`button/secondary` set; everything else is unchanged.

### 4.3 Components

- Add `:token-themes` to `ctk/sync-attrs` under a new touched group
  `:token-themes-group`. A main can set a default; a copy that sets its own
  value becomes touched for that group and keeps it on sync, like any other
  override.
- Inheritance follows the **page tree**. Because the copy root receives the
  main's `:token-themes` through sync, a group set on the main counts as
  explicit on the copy and beats the board around it. Groups the main leaves
  on Auto are inherited from the board. This gives the order of #11278
  (Variant > Component > Board > Page > File) without a special rule:
  - variant main sets `Button: Error` → every copy of that variant is Error;
  - component main sets nothing for `Mode` → a copy inside a `Mode: Dark`
    board is dark;
  - a copy can still override either, which marks `:token-themes-group`
    touched.
  The Figma complaint (main pin always wins, even when unwanted) is avoided by
  telling designers to leave groups on Auto in mains unless the component must
  force them.
- Concrete values in copies may now differ from the main. This is already
  allowed (`:ignore-touched true`). Each sync path must be followed by a
  propagation of the touched subtree, or the copy shows main values until the
  next propagation. Section 7 lists this as the main risk to test.

### 4.4 Sets or themes on the node?

The comment on #11278 picks **sets** on the instance (`buttonSecondary`).
This spec picks **theme groups**. Reasons:

- **Replace, not add.** Picking `buttonSecondary` must also turn off
  `buttonPrimary` that the file activates. With bare sets, both stay active and
  the lib set order decides who wins, not the user's choice. A theme group says
  "one of these", so the swap is exact.
- **The noisy list problem goes away.** The comment worries that an instance
  would list unrelated sets like `modalPrimary`. With groups, the instance
  shows one select per group (`Button`, `Modal`, `Mode`…), not every set.
  The UI can further hide groups whose sets define none of the tokens applied
  inside the selected subtree, so a button only shows `Button` and `Mode`.
- **Figma parity** (collection/mode) and DTCG resolver parity (modifier/context).

Cost: the designer must create a group `Button` with themes `Primary`,
`Secondary`, `Error`, each using one set. To keep this cheap, an option to
test next week: treat **sibling sets in a set folder** (`button/primary`,
`button/secondary`, `button/error`) as an implicit group, so a node can pick
`button/secondary` directly and the other sets of `button/` turn off for that
subtree. Same resolver, less setup. Decide after trying both on the real
design system.

### 4.5 Specific vs generic token names

The right choice depends on how the design system names its tokens.

**Specific names** (`button.border-color`, `modal.border-color`). Each group
only touches its own tokens: group `Button` with themes `Primary`,
`Secondary`, `Error`; group `Modal` with its own themes. No clash, merge
order does not matter. This is also how Figma works: a variable belongs to
exactly one collection, so a Figma file always has this shape, and import /
export maps collection ↔ group, mode ↔ theme one to one.

**Generic names** (`border-color`, `border-width` used by every component).
Two cases:

- *One group for all components* (group `Component`, themes
  `ButtonPrimary`, `ButtonSecondary`, `ModalDefault`…). A button inside a modal
  overrides the same group, so its theme replaces the modal's for its subtree.
  Works. This is also expressible in Figma (one collection, one mode per
  component style), though its mode list grows large.
- *One group per component* (`Button` and `Modal` both define
  `border-color`). A button inside a modal has both groups active. With lib set
  order, the modal could win inside the button. With the cascade of 4.2 the
  button is nearer, so its `border-color` wins inside the button and the
  modal's wins elsewhere in the modal. Works, but has no Figma equivalent (two
  collections cannot share a variable), so export to Figma would need to
  rename tokens.

A trap with generic names: shapes in the modal that are not inside the button
still see the modal's `border-color`, which is wanted. But a component that
uses `border-color` and sits inside the modal **without** setting its own
group inherits the modal's value. That is CSS behaviour and may surprise
designers; the UI should show which node a resolved value comes from.

Recommendation: support both through the cascade, advise specific names (or
one shared group) when Figma compatibility matters, and check which style the
real design system uses before building the UI.

### 4.6 Rename and delete

- Renaming a theme or theme group rewrites `:token-themes` on all pages and
  shapes, as token renames rewrite `:applied-tokens`
  (see `frontend/src/app/main/data/workspace/tokens/remapping.cljs`).
- Deleting a theme leaves overrides in place; they are ignored and flagged.

## 5. Changes by module

### common

- `types/shape.cljc`, `types/page.cljc`: schema for `:token-themes`.
- `types/component.cljc`: sync attr + touched group.
- `files/tokens.cljc`, new pure functions:
  - `apply-theme-overrides [tokens-status tokens-lib overrides] -> TokensStatus`
  - `get-shape-theme-overrides [page objects shape-id] -> map`
  - `get-tokens-for-shape [tokens-status tokens-lib page objects shape-id]`
- `logic/tokens.cljc`: rename/delete helpers for theme names in shapes.
- Tests in `common/test` for the resolution rule (themes mode, sets-only mode,
  nested overrides, missing theme, top-level group `""`).

### frontend

- `tokens/propagation.cljs`: group shapes by effective active-set ids, resolve
  each distinct group once (cache key = sorted active set ids), then run the
  current collect/actionize code per group. Most files will have one to five
  groups, so cost stays close to today's.
- New event `set-token-themes [shape-ids group theme-or-nil]`: updates the attr
  inside an undo transaction, then propagates the affected subtrees only.
- `tokens/application.cljs` `apply-token`: resolve per shape scope instead of
  file scope.
- UI, design tab: a "Token themes" block for boards, groups and instances. One
  row per theme group: select with "Auto (inherits X)" plus the group's themes.
  Page options get the same block. Multi-selection shows `Mixed`.
- `ui/inspect/styles.cljs` and the tokens sidebar: show values resolved for
  the selected shape's scope.
- `app.plugins.*`: `shape.tokenThemes` getter/setter and `resolvedTokenThemes`
  (mirrors Figma's explicit/resolved split).
- Warnings when a token applied to a shape does not exist in that shape's
  scope (today `invert-collect-key-vals` silently skips it and the stale value
  stays).

### backend, exporter, render-wasm, viewer

No logic change: they read concrete values. Only check that the new attr
survives `.penpot` binfile export/import (`mem:common/data-model-change-checklist`).

## 6. Plan

Each step should be reviewable alone.

1. **Common model and resolver.** Schema, pure resolution functions, unit
   tests. No UI. Run `scripts/ci common` (or the common test command).
2. **Scoped propagation.** Per-scope resolution in `propagate-tokens` and
   `apply-token`, behind a frontend flag (e.g. `:token-node-themes`). Set
   `:token-themes` by REPL to test.
3. **UI.** Design-tab block for shapes and pages, undo, multi-selection,
   i18n strings.
4. **Components.** Sync attr, touched group, propagation after every sync
   path; tests for main default, copy override, nested instances, library
   update in a consumer file.
5. **Rename/delete, inspect, plugin API, warnings.**
6. **Rollout.** Decide on a file feature so that older clients do not edit
   files that use node themes (see risks).

## 7. Risks and open questions

- **Sync without propagation.** Any path that copies concrete attrs from main
  to copy without a follow-up propagation will show wrong values. Need a list
  of all sync entry points, including sync done when opening a file with
  pending library updates.
- **Older clients and multi-user editing.** A client without this feature that
  propagates tokens will write file-scope values into overridden subtrees. The
  attr itself survives, and the next new-client propagation fixes the values,
  but users see the flip. A file feature in `common/src/app/common/features.cljc`
  may be needed before release.
- **Performance.** One Style Dictionary run per distinct scope. Should be
  measured on a large file with many instances.
- **Scope of overrides.** Theme groups vs set folders as implicit groups, see
  4.4. Open until tested on the real design system.
- **Open questions from #11278** not covered yet: what the Tokens tab shows
  when the selection has its own scope (proposal: values resolved for the
  selection, with a badge naming the scope), Inspect output (resolved values
  plus the scope that produced them), and whether the Viewer may switch
  themes (out of scope here). Penpot has not given a design for these; the
  issue waits for UX sign-off, so the UI in step 3 is a proposal to show them.
- **Variants.** Figma can also bind variables to variant properties. Out of
  scope here.
- **Export of tokens (DTCG).** Node overrides are file data, not token data,
  so DTCG export stays unchanged.

## 8. Manual test scenario (for next week)

1. Create sets `button/primary` and `button/secondary`, each with color tokens
   `button.bg`, `button.border`, `button.fg` (different values).
2. Create theme group `Button` with themes `Primary` and `Secondary`, each
   using its set. Activate `Button/Primary`.
3. Build a `Button` component: apply `button.bg` to fill, `button.border` to
   stroke, `button.fg` to the label.
4. Place two instances. Set the second to `Button: Secondary`.
   Expected: second instance switches all three colors; first unchanged.
5. Change the value of `button.bg` in `button/secondary`. Expected: only the
   second instance changes.
6. Edit the main (e.g. corner radius). Expected: both copies update, the
   second keeps its secondary colors.
7. Wrap both in a board set to `Button: Secondary`. Expected: first instance
   turns secondary (inherits); second unchanged (explicit).
8. Undo each step. Export and reimport the file; overrides must survive.

## Sources

- Figma plugin API, `resolvedVariableModes`:
  https://developers.figma.com/docs/plugins/api/properties/nodes-resolvedvariablemodes/
- Figma help, modes for variables:
  https://help.figma.com/hc/en-us/articles/15343816063383
- Figma forum, variant mode overriding parent mode:
  https://forum.figma.com/report-a-problem-6/extended-collections-collection-variant-and-mode-share-one-value-57108
- Tokens Studio, themes and Figma modes:
  https://documentation.tokens.studio/platform/themes
- Tokens Studio resolver spec: https://github.com/tokens-studio/resolver-spec
