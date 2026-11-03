# plymeld-geom — design

The 2D kernel every later stage stands on: walls are insets, infill regions are
differences, support is a union of projected overhangs, bridges are intersections.
Written from scratch; this note records the approach so it can be reviewed
before it is buried in code.

## Representation

- **Integer grid, 1 µm** (`Units.PER_MM = 1000`), `Long` coordinates. Polygons are flat
  `LongArray`s (`Polygon`), sets of them are `Polygons` (outers CCW, holes CW). No objects
  per vertex.
- All predicates (orientation, segment intersection test) are **exact** in `Long`
  arithmetic. Coordinates are bounded to |c| < 2^31 grid units (2 km) so products fit;
  the booleans run their side tests on doubled coordinates, so they need |c| <= 2^29 (about 537 m).
- Intersection *points* are rounded to the grid — the one place where rounding happens —
  and every algorithm is written to tolerate that: the output is a valid polygon set on
  the grid, never an exact real-number result.

## Boolean operations: arrangement + winding numbers

Rather than a Vatti-style scanline with its many special cases, booleans are done in
three plain steps that are each easy to test on their own:

1. **Split.** Find every pairwise intersection among all edges of A and B (sweep over
   x-sorted edges with an active list; brute force for small inputs), cut the edges at
   the rounded intersection points, and dedupe zero-length pieces. After this, edges
   only meet at endpoints.
2. **Winding.** For each piece, compute the winding number of A and of B on its left and
   right side (a ray/scan against the original polygon sets, or propagated across the
   planar graph). A piece is *kept* iff the fill rule differs across it:
   - union: inside(A) ∨ inside(B) changes
   - intersection: inside(A) ∧ inside(B) changes
   - difference: inside(A) ∧ ¬inside(B) changes
   - xor: inside(A) ⊕ inside(B) changes
   where inside(X) = winding(X) ≠ 0 (nonzero rule; input rings may overlap freely).
3. **Assemble.** Orient kept pieces so the filled side is on the left, then stitch
   end-to-end (the same exact stitching the mesh slicer uses) into loops; drop
   degenerate loops; classify outers/holes by signed area.

Snap-rounding caveats (a rounded intersection point landing on another edge) are handled
by re-running the split on the pieces until no new intersections appear, with a bound,
and by tests against frozen answers from an independent geometry library.

## Offsetting

Per-vertex offset of each ring (miter with a limit, or round joins approximated by
segments at a chord tolerance), producing a possibly self-intersecting ring, then the
**union** of the offset rings' "sleeve" polygons with the original resolves all
self-intersections and vanishing regions. Insets are offsets of the reversed ring.
This reuses the boolean core instead of a dedicated offset algorithm; speed is measured
before deciding whether a dedicated one is needed.

## Testing

- Property tests on invariants (area conservation, `A∪B ⊇ A`, `A−B` disjoint from B,
  offset(-d)∘offset(+d) ≈ identity on convex input).
- **Frozen independent answers**: random simple polygons (pairs for the booleans, single ones for round-join offsets)
  with areas from GEOS, computed once outside plymeld and committed (`src/test/resources/geom/`): area within one grid
  cell per unit of perimeter (booleans) or 0.5 % of the offset's band (offsets); counts are not compared.
- A debug SVG renderer draws any disputed case with both answers side by side.
