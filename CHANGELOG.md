# Changelog

What changed in plymeld, for people using it. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Versions are `0.YYYYMMDD.N`: the release's date (UTC) as one number, and N counting the releases of that day
(`plymeld --version`). Changes collect under Unreleased; a release is a tag, and the change after it gives that
section the release's version and date.

## [Unreleased]

### Added

- `plymeld render FILE.gcode`: pictures of what any g-code file lays down, read from the file alone (no slicing, no
  printer). Layers seen from above (`--layer N`, the first layer 1; `--layer-at-z MM`; `--layer-range A..B` with
  `--layer-step K`; the middle layer when no view is given) and cuts through it: `--cut-y MM` (seen from the front),
  `--cut-x MM` (from the right), `--cut-line X0,Y0:X1,Y1`, and `--cut-plane PX,PY,PZ:NX,NY,NZ`, each line crossing the
  plane drawn as its bead's cross-section, heights stretched so a layer is at least 3 px tall (`--height-stretch`),
  `--z-range Z0..Z1` for some heights, `--cut-depth MM` for the lines beside the plane; `--cut-z MM`, a level plane seen
  from above, each bead it crosses as wide as it is at that height. The whole print: `--outside SIDE` (front, back,
  left, right, or top: the nearest bead at each point) and `--3d` (from the front-left above, or `--3d-from TURN,TILT`),
  each bead shaded as a tube; `--compare-with OTHER.gcode` makes each picture three panels, this file, the other in the
  same frame and scale, and where they differ. `--outline-of MODEL` draws the model's outline over a layer or a cut,
  placed as plymeld placed it for the file; `--mark-bare` and `--mark-laid-twice` mark, on a layer, the ground no line
  covers inside the walls, the outline left bare, and plastic laid twice, by the pre-print check's own rules with the
  settings in the file's source block (a file of one object on flat layers), their sizes in the caption. Each line as wide as the plastic it lays (`--line-width
  plastic`, the default; `declared`; `center`), colored by `--color-by` feature, tool, speed, flow, width, height, fan,
  temperature, order, or layer-time. `--only-object`, `--only-part`, `--only-tool`, and `--only-feature` choose lines,
  `--others faint` keeps the rest in gray; `--layers-below N` adds the layers under it; `--show` marks travels, seams,
  retractions, starts, and tool changes. `--around X,Y --span MM` or `--bed-window` frame a part of the bed,
  `--same-frame` one frame for a whole run. The default scale sets the closest lines at least 3 px apart; where it
  cannot, each feature's area is drawn as a flat region with its outline and the run names the `--span` that would
  resolve the lines (`--allow-unresolved-lines` draws them anyway; `--px-per-mm` or `--picture-size` set the scale).
  PNG or SVG (`--format`), into `<file>.render/` or `-o DIR`, with `--sheet` for all of a run's pictures in one image
  and `--print-files` for the paths alone; `--list-features` and `--list-objects` print a file's names. Arcs (G2, G3)
  are read; a file plymeld wrote gives its filaments' colors and diameters from its source block. Every caption says
  what was drawn; an option that applies to no picture asked for, or two that cannot both hold, is refused, naming them.
- The pre-print check finds plastic laid twice: a spot of a layer where two lines lie over each other further than
  neighboring lines overlap by plan (their beads' rounded flanks, the slicing tolerance, a line end's reach into the walls,
  `plan.layer.infill.wall_overlap`, and fuzzy skin's thickness allowed), wider than the narrowest place a line is laid in.
  Ironing, wave beads, a line over itself without stopping, and sparse infill lines crossing each other are not judged.
  `slice` reports it in a `laid twice:` line beside the seams and strings (the window's console too): the number of
  spots, their area, the layers holding one, and the widest (its layer, place, width, area, and the two kinds of line),
  with a picture of each such layer among the check's pictures. It does not refuse the file for them: the planner still
  lays some plastic twice itself, in places on ordinary models.
- A curved island over an object of parts in several filaments or settings (`plan.object.<key>.island.<key>`): every
  part inside the island follows its layers, each laid by its own filament, settings, and tool in its turn of the layer,
  the parts meeting in the island's layers as they do in flat ones. Each island layer is a layer among the flat ones: its
  filaments are ordered and changed as any layer's (each layer starts with the filament the one before ended with), a
  change there purges into the tower at the flat layer's height. It was refused ("an island is laid as one"). The
  pre-print check reads each island line against its own part's tool. Not yet on a plate of several objects (refused,
  saying so), and the plan does not weigh such an island unlocked: a plan locks it.
- A curved island locked with no start where no start removes stair-step by the survey's measure takes the start that
  steps least (it was refused: "no start removes stair-step from its top"); the plan's facts say how much more its own
  layers step than flat layers there. Unlocked, such a cap still gets no island.
- Short walls slower: with `plan.mechanism.motion.short_walls.length` set, a wall line or loop shorter than it prints
  no faster than `short_walls.speed` (bundled: half the visible lines' speed, a placeholder). With
  `short_walls.full_length` set too, a wall between the two lengths prints no faster than a speed rising in a straight
  line from `short_walls.speed` to its own, the whole wall at that one speed; unset, it is a single step at `length`. A
  scarfed loop counts as long as its loop. Unset, nothing changes.
- A line no faster than it can hold steady: with `plan.mechanism.motion.steady.share` set, every printed line is laid no
  faster than the speed at which that share of its length is laid at a steady speed, starting and stopping at rest at
  its acceleration: `sqrt(acceleration * length * (1 - share))`, the whole line at one speed. Not the purge tower, the
  prime line, a lifted bridge's own speeds over air, or a line of no length (it lays nothing). Unset, nothing changes.
- `plymeld printer send` refuses a file with a number a printer may refuse or act wrongly on, before anything is sent,
  naming each line: a value that is not a finite number, a move at a feed rate of 0 or below, a temperature, fan, or
  wait below 0, an acceleration of 0 or below. It reads the file it is given, sliced by any build or by hand.
- `plan.layer.sliver.width`: on an object of several filaments, how thin an area of one filament is handed to the
  filament beside it (along the object's surface, as `plan.layer.sliver.at_surface` says). Unset, it is the thinnest
  area the walls print, as before. The pre-print check allows a line that much further from its own tool's part inside
  the object.
- `plan.objects.gap`: the least distance between objects plymeld lays out on the bed. Unset, it is one outer wall
  width, as before.
- A note when an object stands on a small footprint with no brim: its first layer under
  `plan.mechanism.adhesion.small_footprint.area` (100 mm², a placeholder), or the object taller than
  `small_footprint.height_ratio` (3, a placeholder) times its first layer's narrower side. `plymeld slice` says so in
  one line per object; nothing is refused or changed.
- No wipe back along a line the head leaves below the line's highest point (a line or an edge strip laid sloping down
  over a waved bridge): the wipe ran at the head's height, into the line where it rises. A waved bridge's last bead on
  a layer is retracted and wiped before the layer change, on the layer it was laid on.
- The wall order holds after a tool change: on a layer where the incoming filament lays no infill, all its inner walls
  were laid ahead of its outer walls, whatever `plan.layer.walls.order` said. Its lines now keep their planned order there; where it lays infill, the infill
  still comes first and the walls follow in their order.
- Several multi-color objects on one plate: an object on several filaments was sliced on its own or refused. On each
  layer each filament now lays its lines of every object in turn, so the plate changes tool no more often than one of
  its objects would, with one purge tower beside them all. Copies of an object (`model.object.<key>.count`) can each
  take settings of their own (`plan.object."<key>#2".…`), for trying variations side by side. The objects print
  together, by height, each on its own flat layers; one object at a time is not written for such a plate yet.
- A section of a model printed on its own: `model.object.<key>.section.min` and `.max` give a box in the model's own
  coordinates, and only what is inside it prints. For trying a detail of a large model (one face, one small feature)
  without the hours of the whole. By default its cut faces are left open (`section.faces = "open"`): the whole model is
  planned as it would be, and only its lines inside the box are laid, so the section prints as that part of the whole
  print would, with no walls or top added where it is cut; on the bed it is laid out by its box, so several copies fit. With `faces = "closed"` the model itself is cut
  and its cut faces closed, and the section prints as an object of its own, standing on the bed; a part of a 3MF with
  nothing in the box is left out.
- Renamed settings are read under their new names: a config file, a plan file, or a g-code's recorded settings
  (`slice --from`) that uses a name a release changed reads as the new one, its value converted, with a warning naming
  both. `plymeld migrate FILE...` rewrites config and plan files to the current names, comments and the rest kept, each
  change recorded for `plymeld journal --undo`.
- `plan.technique.scarf_seam.speed`, `.step` and `.flow`: a scarf seam's ramp and its closing pass at their own speed
  (half the visible lines' by default) and flow (100 %), the ramp rising in steps no longer than `.step` (1 mm, the length
  that was fixed before).
- A machine model's toolheads may carry a heat time (`hardware.model.<key>.toolhead.<key>.heat.time`): a tool that sets
  no `heat.time` of its own heats ahead of a change by its model's. The Creator 5's is 15 s (a placeholder), so its tool
  changes heat the next nozzle 15 s of printing ahead instead of waiting for it.
- Z anti-aliasing (`plan.faces.sloped_top.technique = "z_antialiasing"`) on an object printed in several filaments or laid
  part by part (part settings), alone and on a plate; it was laid as laid there. Every filament's lines are lowered onto
  the object's whole top, all its parts together, so a part's face that another part covers is not lowered onto; on a
  plate each object as its own setting says, on its own layers. A lowered line is never thinner than the thinnest layer
  (`hardware.tool.<key>.layer.height.min`) of any tool the object's parts print with.
- `plan.mechanism.motion.speed.factor`: every printing speed plymeld decides, times this, for a slower (or faster) print
  without changing each speed: 75% for a print where finish matters more than time. Travels and retractions keep their
  speeds; above 100% no speed goes past the machine's printing speed. The filament's melt cap, the minimum layer time
  and the time prediction read the speeds scaled. Bundled 100%; set it for an object or a part as any plan value.
- The documentation's start, [docs/README.md](docs/README.md): installing plymeld, and a first print from setting up to
  sending the file. Every command a tutorial shows is run by the tests as written. The window's finder lists the pages.

### Changed

- Variable-width walls lay an island too narrow anywhere for all its walls (a lens, a wedge, a ring, a strip, a
  narrow stroke) by its local width: across each place as many lines as its width holds, each between
  `plan.layer.walls.line.width.min` and the new `plan.layer.walls.line.width.max` (unset: twice the inner wall's width),
  as near the set widths as can be; one line as wide as the place, two sharing it, from three the outer two at the outer
  wall's width and the rest sharing what is left. Every pair of lines is a loop built from the outline, so the outer
  loop is there on every layer where the island is two narrowest lines wide anywhere, closed, its seam placed as every
  loop's: it turns back where the island narrows below two lines (a neck, a sliver, a long tip, each getting a line of its
  own that meets the turn as a line meets a wall), and runs on into a short tip, out and back along its middle to the
  tip's end. A middle line starts where three lines fit, the loop beside it giving way to its round start. A lens growing
  from one line wide to three, which was laid as one line, then a small loop with a hole and two short end pieces, its
  front edge bare, is one closed loop a layer, edge to edge; a ring 1 mm wide, two loops with a strip bare between them
  all round, is two lines 0.51 mm wide. Each such island's lines are measured against its walls of before and kept only where no worse. Islands
  wide enough for their walls somewhere are laid as before.
- `seam_scar` counts an open outer-wall line's two ends as point seams (a line along the outline where no loop fits
  starts and stops on the surface), reads a loop's jog only from the seams of what lies under it (a loop over nothing on
  the layer below, a new island, has no jog), and counts open outer lines as hiding what lies behind them, as loops do.
- Each contour's walls are laid in the order `plan.layer.walls.order` names (it replaces the old switch, `inner_first`):
  `inner_outer_inner` by default, the walls past the one beside the outer wall, then the outer wall, then that one; so a
  layer's first line, its un-retraction and the travel's arrival land on hidden walls, not on the visible face, and the
  outer wall is still laid free. With fewer than three walls, or where the outer wall hangs out past the layer below,
  inner first. The inner wall laid just before the outer one, and the one laid just after it, start nearest the outer
  wall's start and end, so the head steps between them and the outer wall without a long travel. `inner_first` and `outer_first` (the order before) stay values.
- An outer wall's restore after a retracted travel happens a line inside its start, and the head then moves onto the
  start with nothing pushed (`hardware.filament.<key>.retract.return.inside`, on in the bundled filaments). Restored at the
  start, the push left a blob on the visible face, where a scarf seam's ramp is thinnest.
- After a wall loop, the outer wall or an inner one, the wipe steps a line inside it before running back along it, the
  whole retraction spread over its moves (`retract.wipe.inward`, on in the bundled filaments), so its last ooze lands on
  hidden material; no wipe follows a line lowered below its layer.
- The bundled filaments wipe as they retract (`hardware.filament.<key>.retract.wipe.enabled = "true"`): the nozzle
  retraces the path it just laid by the wipe distance (1 mm), so what oozes lands along the line, not in a blob where the
  nozzle stopped. Off before; a filament of yours that sets it keeps its own.
- Prints are looked at from their front by default (`plan.view_from = "front"`: the model's own front, its small-y side
  as modeled, turned with its pose): quality's measures count its back less, and least-visible seams go there. Looked at
  from all around (the default before), a plain round wall's seams stayed wherever a feature first put them, even in a
  column up the front face from a detail low on it to the top edge. A model with no front of its own sets `all_around`.
- Tool and filament changes are made over where the incoming filament first prints (the purge tower when there is one),
  after the outgoing tool leaves the part with its usual retraction, wipe, and lift and travels there. The change was
  made standing on the outgoing filament's last line, which left ooze on the part: on a plate, every change stood on
  the last object's face.
- On an object of several parts, an outer wall whose seam is pressed against another part (that part within one line
  width of the seam on the layer) gets a point seam, not a scarf: the seam is hidden there, and the scarf's ramp and its
  closing pass ran on from it onto the faces that show. On a small island of one filament inside another's part,
  the ramp ran from a seam hidden against that part round the island's front corner and across its whole front, laying
  a thin line and a second over it there. Least-visible seams count such a seam as the
  point seam it is laid as, so they no longer avoid a covered spot because a scarf would be better elsewhere.
  `plan.technique.scarf_seam.covered = "scarf"` (per object or part too) scarfs a covered seam as before; seams that are
  not covered, and every seam of an object of one part, are laid as they were.
- On an object of several filaments, a sliver of one filament (thinner than `plan.layer.sliver.width`) lying along the
  object's outer surface stays its own part's: its own filament lays it where it is wide enough for a line
  (`plan.layer.walls.feature.min`), and it is left empty where it is not. It went to the filament beside it, whose
  plastic then showed in the wrong color on the surface, outside its own part, and whose outline stepped out over the
  layer below for the layers the sliver was thin: where one filament's wall leans out over another's face, starting at
  zero width on that face, the face's outer wall was laid 0.13 mm out over the leaning wall's place for a layer before
  that wall's first line. A sliver inside the object, and one meeting the surface only end on, still goes to its neighbor
  (it fills what would be a void), and between two parts of one filament with settings of their own every sliver does.
  `plan.layer.sliver.at_surface = "neighbor"` (per object too) hands a sliver along the surface over as before. The
  pre-print check refuses a line whose bead covers a stretch of the object's surface that is another tool's part
  (beyond its tolerance; with `neighbor`, beyond the width handed over), reading each line's width from its plastic. On
  an object with a curved island, it reads the flat layers' lines with the tool in force (an island layer's tool change
  was left out of the flat layers' reading, so the next flat layer's lines were judged as the other tool's).
- `slice` holds less in memory: the slicer's stages are let go once the file is written, before the pre-print check
  reads it; the check reads the file line by line rather than holding it as a list of lines, keeps its look-up of each
  layer's outline in flat arrays and lets it go once the layer is read. The file written is the same.
- An island laid by its width is planned with less work: its lines' simplification, their measure against its walls, and
  the grids its construction reads cost less. The file written is the same.

### Fixed

- A top or bottom in round rings (`concentric-inside-out`) no longer leaves a dashed slit along its edge. Where the rings
  run along a wall (an oval's sides and ends, a cylinder's edge), each arc stopped where its center met the edge of the
  area, and a wedge of ground between the ring inside it and the wall, up to nearly a line wide, was laid by nothing.
  Each ring now runs on past its arc there, its line narrowed to the ground left (its own reach into the wall a share of
  its own width, `plan.layer.infill.wall_overlap`), down to half a line; two arcs it runs between become one line, and a
  ring that fits all round narrowed becomes whole. Where a ring crosses the wall steeply, its arc ends as before. On the
  test catalog's oval (`RingTopTest`), the widest spot left bare on a quarter of its top layer is 0.02 mm, against
  0.12 mm without the narrowed lines, with nothing laid twice; a path with a narrowed stretch is written as straight
  moves, not fitted to arcs.
- A line no longer runs out along a short dead end at a junction and back. Where a thin area too small to keep all its
  forks kept two of them, they were the two longest of those it was cutting, which could be a short trunk beside two
  longer forks: its line ran down the trunk and back. It now keeps its own two longest forks. And where a narrow island
  laid by its width has a short piece from a junction of its middle line to a dead end, covering nothing the line through
  the junction does not, the piece is now left out instead of joined, so the line no longer runs out along it and back.
  The lines in the small corners of the bundled Benchy and test plate follow their corners' longest forks. A dot of one
  part beside another, left out on the object's surface, is still judged on its line as it would have been joined with
  those dead ends, the lines that rule was set on.
- A narrow island's outer line no longer runs out to a short tip's end and back where that covers nothing its own line
  does not. Where the line of an island laid by its width runs into a tip narrower than the narrowest line, it went out
  along the tip's middle to its end and came back; where the tip ends within the line's own bead beside it (no further
  past it than an interior line's end reaches into the walls, `plan.layer.infill.wall_overlap`) and the line goes on
  past the tip, the run is now left out. A corner the line turns keeps its run into the corner.
- A narrow island's outer line no longer dips toward its outline where a slit is cut into it. Where an island laid by
  its width has a slit narrower than its outline is sampled (a sliver of another part between two narrow strokes),
  its width read round the slit's inner corner came out a few tens of microns, a stretch too short to keep a count of
  its own; it took the two lines of its neighbors, and the outer loop's points there, drawn onto that narrow reading, ran
  0.1 mm in toward the outline and back. Such a place, narrower than the narrowest line, now keeps its count, and the
  loop turns at its mouth as at any place too narrow for it.
- A fill line passing a corner of a wall no longer stops at it and starts again beside itself. Where a line of a fill
  (a surface, internal solid, sparse infill, a bridge) only grazed the corner of what lies outside its area, it was cut
  there into two lines, their round ends both laid against the corner, over each other by up to a line's width. Now the
  two are one line laid past the corner wherever that lays less plastic twice: their ends closer together than a line
  is wide, and the line laid through coming off its area by less than the ends would overlap.
- Two wall loops no longer turn back over each other at a pinch. Where an area narrows between two notches (two pockets
  cut into a face, the corners of four cells of a pattern) below what a loop needs, the loop is laid as two loops, one each
  side; each turned back at the pinch, and their turns faced each other closer than a line apart, 0.25 to 0.4 mm laid
  twice. The smaller loop is now cut back from the larger one to a line's spacing, the two touching as neighboring lines
  do.
- Where the line of an area too narrow for two walls forks, one pair of its branches runs through the fork and the
  others give way to it. Where four branches met (two bands crossing, a corner of four cells of a pattern), both pairs
  were joined through the fork and two lines lay over each other across its whole width, up to 0.9 mm; now the ends left
  at the fork start where they reach into the line through it by `plan.layer.infill.wall_overlap` of their width. And the
  pair run through a fork is the straightest one that leaves no branch lost: a short branch that gives way to the other
  two along all its length was dropped, and where it led into a neck or a stub wider than the narrowest line, the neck
  was left bare (a hole inside the walls, 0.35 mm wide, at a neck between two notches in an outline).
- A narrow island's outer line no longer zigzags where a place about two to three lines wide gets a middle line. Where
  its count rises from two lines to three, the outer loop steps toward the outline within a few points, and it moves
  aside for the middle line's round start; each point moved across the line as read from its neighbors, which on that
  step points along the line, so the step's points moved along it and the line went back and forth over itself, a
  fraction of a millimeter, roughening its edge. The outer loop now moves across the outline at every point, the points
  where it stops short of a corner too. Where an inner line moves aside entirely at a single point, it is no longer cut
  into two ends lying over each other: the line goes on across.
- Two fills sharing an edge no longer lay their edges over each other. Every fill of a layer (internal solid, sparse
  infill, a bridge, a top) placed its lines against each edge of its area as against a wall: where two of them met, both
  did, and their line ends and linking runs lay over each other along the edge, 0.25 to 0.38 mm wide. Now one fill owns
  such an edge (a top, a bottom, a bridge, an internal bridge, internal solid, sparse, in that order) and lays its lines
  against it as against a wall, and the other lays its lines against the owner's as against a wall's, with the same
  line-end overlap (`plan.layer.infill.wall_overlap`). And a band of solid fill laid as loops (up to five lines wide, round
  a hole) kept its loops at the band's mean width all round: where the band is narrower they ran on over its hole, over
  sparse infill or a wall. They are now cut where they leave the band. Where the gaps between both fills' round line
  ends meet, holes up to about 0.3 mm across remain along the edge.
- Far fewer moves of a few microns in the walls of narrow areas. An area too narrow for its walls is laid by its local
  width, its lines built from points on the outline's samples, two or three points to a sample a few microns apart, each
  with a width of its own; the lines kept them all, so the printer was sent thousands of moves shorter than the slicing
  tolerance (`method.slicing.tolerance`) carrying steps in width. Points closer together than the tolerance are now one,
  its width keeping the plastic of the moves on either side; an open line's ends are left as they were.
- Walls no longer run across the tip of a sharp notch in an area, or end against each other at a narrow neck. A loop's
  place is found by shrinking the area's outline; at a notch sharper than 60 degrees that shrink cut the corner short,
  keeping ground nearer the tip than the line fits, and the loop ran across the notch over the loop inside it. It now
  keeps a line's distance from the tip. Where an area turns in beside a narrow neck, the shrink cut deeper at the turn
  than the line needs and cut the neck through, and the loops grown from the two sides ended against each other or
  crossed the cut twice; such a neck is now kept and one loop runs through it.
- On an object of several filaments, a sliver of one filament too thin for a line that goes over to the filament around
  it now takes the gap between them along. Parts meet a few microns apart, and that gap was left as a crack in the
  neighbor's area, a hole of its own or one opening onto its edge, which the neighbor's walls looped round: three or four
  lines over each other at the crack's end.
- A small area laid by its local width (one too narrow anywhere for the set walls, such as a tooth along an outline) could
  also get a bridge or solid infill across its own lines: where the walls laid no loop of theirs in it, they handed most of
  it to infill, and that piece was taken back only when its first corner lay inside the area (a mitred tip of it lay a few
  hundredths of a millimeter past the area's sharp corner). The area now hands nothing to infill.
- A move inside a line whose plastic rounds to nothing in the file is now merged into the next move instead of written
  as `E0`, where its point lies within the slicing tolerance of the straight move that replaces the two: a reader takes a
  move without plastic for a travel, and the line broke in two there. A scarf seam's closing pass over a small loop, so broken, read to the pre-print check as a line of its own at
  the line's full width over the loop's start; ironing lines broken the same way now read as one line.
- A bridge's lines, over air or over sparse infill, ended with their middle on the edge of the area they fill: every line
  end and every run linking two lines along that edge lay half a line over the walls and the infill beside it. They now
  end where their bead touches the wall's and reach on by `plan.layer.infill.wall_overlap`, as every other interior
  line does.
- A hole of an area was sometimes read as another area's: a hole whose first corner touched a smaller area beside it (a
  sliver a micron wide, as the space between two walls' lines can leave) was given to that area, and the area that does
  hold it was taken to have none. Variable-width walls then laid lines up to 0.9 mm wide along the middle of that whole
  area, over the infill, and none where they were needed: on a block with a plate of a second filament buried in it,
  such lines over the infill, and the web between the block's side and the plate's end left bare. A hole now goes to the smallest area that holds it, judged at its first
  corner not on that area's edge.
- A model could now and then be cut wrong where the mesh was sliced on several threads at once (a slice's own parallel
  work, the pre-print check's, or several slices in one process, as in the window): every thread wrote each cut
  triangle's crossing point to one shared place, and another thread could overwrite it before it was read, leaving a
  layer's outline missing pieces (walls absent along an outline, or a layer with nothing of the model on it). It
  showed mostly in a process's first work, while the slicer's code still runs interpreted. Each cut now keeps its own.
- The pre-print check refuses a hole inside the walls: a spot of a layer no line covers, closed in by walls alone (not
  reaching the outline, an infill or surface line, or the infill region), wider than the narrowest place a line is laid
  in (`plan.layer.walls.feature.min` and the slicing tolerance a side), reading each line's width from its plastic (on
  a curved layer, whose thickness varies along it, on its own thickness where the line lies) and, where fuzzy skin is
  on, its thickness wider a side. A lens laid as a loop and a line down its middle, its loop's turn and its line's end
  0.25 to 0.29 mm apart on layer after layer, passed.
- Variable-width walls keep a short line between walls where its area is wider than that narrowest place: a web between
  an opening and the outside, where the inner walls on both sides end, was left bare between its two outer walls (0.72 mm
  over 1.4 mm on a web 1.6 mm wide), its line dropped as too short; so were
  the pockets where the inner walls part. The voids a round bead leaves in a sharp corner, narrower than that, stay empty.
- Variable-width walls keep a thin area's fork into a corner where its area reaches out past the line's end by more than
  that narrowest place: a strip's line ending in a head 1 mm across left its point bare, and where
  three thin strips meet beside another part of the object the pruned forks left pockets 0.25 to 0.45 mm wide. A part's edge where another part of the object lies beside it counts as between walls, not as the surface. A
  third line from a junction starts where it reaches into the line the other two make by the infill's wall overlap,
  instead of laying the junction twice; ends on one point join the straightest pair first; and a junction's third line no
  longer joins another line's end across a gap (it ran back out and over that line's fork).
- Two loops with a slit between them too narrow for a line share it: each is half the slit wider and a quarter of it
  nearer, its other edge in place (the outer wall's on the outline), within `plan.layer.walls.line.width.max`. A web 1 mm
  wide between a hole and the side left a slit 0.12 mm wide between its two outer walls on every layer. A gap that opens
  onto the area the walls hand to infill (between a loop and a short line in that area's corner) is that area's corner,
  not a slit: a loop widened into it narrowed a ridge's infill pocket below what infill is handed and left it bare.
- An area too narrow anywhere for all its walls that keeps its walls (where its lines laid by width would leave it no
  better covered) now shares the slits between those walls too: a strip whose inner wall's two sides lay a slit apart
  was left bare along that slit. A loop takes no more of a slit than its
  own stretch across the slit is away, measured straight: where a lens-shaped loop's two sides converge, a side widened
  by the gap seen along its normal reached over the other side.
- On an object of several parts, a dot of one part's outer line (shorter than its width, beside another part) is left
  out only on the object's surface; along a part's edge inside the object it is laid: where a narrow stroke of one
  filament met another part through a neck too narrow for its loop, the pocket there was left bare, 0.25 to 0.45 mm
  wide.
- Fewer retractions for the short lines walls by width keep: a wall or infill path whose travel in or out would retract
  is moved beside a path that ends within `hardware.filament.retract.travel.min` of it, a travel that never retracts; such
  a travel counts its length alone when paths are ordered; an open line is turned end to end where its far end lies
  nearer the next path; and lines meeting end to end within that travel are laid with one contour's walls (each went
  with its own nearest contour, and a thin area's pieces between two contours were laid apart).
- An outer line no longer steps back where the line beside it gives way to a middle line's round start: each point moved
  across the track on its own, and one moved farther than the point before it landed behind it, a spike 15 to 30 µm
  long turning about 159 degrees on every layer of a wedge. A point left behind, or
  moved to within the slicing tolerance of the one before it, is left out.
- Planning runs each layer as a task of its own on the cores: the slowest layers of a run (an object's first layers)
  ran one after another on one core. The g-code is unchanged; a few of the walls' and paths' measures skip what lies too far away to count.
- A loop's seam is aligned only with the seams of the loops under it on the layer below, as `seam_scar` reads a jog: a new
  island took the corner nearest another island's seam.
- A part narrowing to a ridge a line or two wide (variable-width walls) is laid as one line where two walls do not fit,
  joined to the two walls it continues from, and as two walls where they do, along the whole stretch. Where the ridge's
  width hovered at the width two walls need (its outline a little uneven, as a sliced curved face is), the wall loop came
  and went along it: a string of closed loops a millimetre or so long overlapping each other across the whole width, and
  fragments between them; where the loop ended, the single line started short of it, leaving a notch; a stretch of a
  ridge between two loops, 1.2 mm of it 0.75 mm wide, was laid as a 0.4 mm stub across it (the line's corner forks were
  pruned first, then its trunk read as a fork of its own and went too); and on an object of several parts, a short line
  at such a ridge's end was dropped as a "speck" beside the other part, leaving 0.9 mm of a 0.78 mm ridge with no line.
  Now pieces of a loop joined by an area at most the slicing tolerance a side narrower than the loop needs are one loop
  (`method.slicing.tolerance`); a loop piece shorter than `plan.layer.walls.transition_length` gives way to the line
  running through it; a line ending against a loop's turn reaches into it as an interior line's end does
  (`plan.layer.infill.wall_overlap`); a line's forks are pruned as the line stood before, never its trunk; the pieces of a
  band beside a stretch too thin for a line are kept, as wide as the band; a speck is left out beside another part only
  where it runs into a sliver thinner than the narrowest line; a thin leftover between walls gets the narrowest line where
  that line reaches into the walls no further than an interior line may (`plan.layer.infill.wall_overlap` of its width a
  side; narrower, it is left empty); and a line of no length (its ends on one point) is not written, saving a restart
  and a retraction for nothing.
- A line is never laid where the area is narrower than `plan.layer.walls.feature.min`, however short the stretch: a
  line 0.3 mm wide was printed through a gap 0.1 mm wide between a model's face and another part's tip, into that
  part's place; it now stops either side of the gap, and two lines' ends are not joined across such a place. A small
  area whose skeleton's forks all read as corners (its junction several points a few microns apart) keeps a line
  through its two longest forks (it got a line a few microns long); a "speck" beside
  another part is left out only where it is a sliver, not where it is wider somewhere than two narrowest lines; and a
  thin sliver in an acute corner of the model's outline, beside the infill region, gets its line (it was left to infill
  anchors that do not reach the outline).
- The pre-print check refuses a stretch of the model's outline longer than a line with no line beside it where the model
  holds one ("the model's outline has no line beside it"): it counted a wall within a whole line of the outline, so a
  hole in a band a line or two wide, with walls on either side of it, passed. The model holds a line where a disc as
  wide as `plan.layer.walls.feature.min`, and the slicing tolerance a side, fits against the outline (on the first layer,
  in the outline drawn in by `hardware.plate.first_layer.squish`): an acute corner's tip and a place narrower than that
  are not refused. Bare places on a band's two faces are one stretch, but not across a line lying between them.
- The pre-print check of a curved island reads a wall by its own width: a lip as wide as two lines, laid as one wall that
  wide, was refused as an unwalled side of the model.
- The nozzle's angle and height (`hardware.tool.<key>.head.nozzle.angle` and `.height`), where the tool sets none, are its
  machine model's toolhead's (`hardware.model.<key>.toolhead.<index>.head.nozzle.angle` and `.height`, as the bundled
  Flashforge Creator 5 gives them) everywhere the cone is read: a curved island a plan locks (it was refused, "a curved
  island needs hardware.tool.<key>.head.nozzle.angle"), the curved islands the plan weighs (not offered), the pre-print
  check of a curved band or island (refused for want of the cone), support's order (a column was never finished first),
  and the clearance every travel keeps over what is printed (the cone left out). The technique screen already read the
  model's. An object printed with several tools takes the strictest cone among them: the narrowest angle and the lowest
  tip. A value the tool sets still wins over its model's, and one it sets alone takes the other from the model.
- A dead feed: with `plan.mechanism.motion.steady.share` set, a line of no length (a thin area's center line whose
  points come out as one, at sharp tips) was capped to a speed of 0 and written `G1 F0`, at which a printer stopped the
  print, beside a preheat comment of "NaN s"; and the pre-print check passed the file. Such a line is no longer capped.
  The writer now fails the slice at any line holding such a number (the same rule as the send's), the templates' lines
  included, rather than write it; a feed above 0 that rounds to F0 is written F1. The pre-print check refuses such a
  line too, and a NaN or Infinity where plymeld's comments hold a number.
- On an object of several parts, what counts as another part beside a part's lines (for a seam pressed against one, and
  for a speck of outer wall left out in front of one) is the other parts as laid. It was the object's outline less the
  part, which is closed (`method.slicing.close_gaps`) and so left hairline slivers along a round open edge that read as
  another part there (along `ScarfCoveredTest`'s round base, 18 of the 97 points of its open outer wall).
- A region's speed and acceleration caps (`mechanism.motion.speed.max` and `.acceleration.max` under
  `plan.object.<key>.region.<key>`) apply to an object printed in several filaments, alone or on a plate, and to the other
  objects on such a plate; they were ignored there.
- The step from an inner wall onto the outer wall's start, and from it onto the wall beside it, goes unretracted only
  within two line widths: in a narrow stroke, a longer step crossed the pocket's edge and drew strands.
- A plate of several objects heats the next tool ahead of a change as one object does (`hardware.tool.<key>.heat.time`);
  it waited at every change.
- A retraction's wipe is written under the feature it wipes: the next path's `;TYPE:` and `;WIDTH:` lines came first, so
  a wipe along an inner wall was labelled as the outer wall that followed.
- A scarf seam is judged by its whole ramp, not its start alone, where it is placed and in the seam measure: the seam
  goes where the most-seen point of its ramp is seen least. Seams started hidden in a small pocket another part
  fills, and their 10 mm ramps ran out of it onto the face beside it. (A seam another part covers is now laid, and judged, as a
  point seam: under Changed.)
- Least-visible seams are planned up the whole wall, not layer by layer. Each layer's seam stayed in line with the one
  below rather than pay the move to a better place on its own, so a seam drawn to the front by a feature low on the
  wall ran up the front face above it to the top. Planned over all the layers a loop continues through, the move is
  paid once where it costs least: on `SeamColumnTest`'s round column with a slot in its front, the seams above the slot
  go to the back, in one line, not up the front face. Least-visible placement also counts a scarf seam's blemish as the seam
  measure does (`method.quality.seam.scarf_share` of a point seam's); counted as a point seam's, a move to a less-seen
  point looked worth more than it was.
- `plymeld update` on the Linux command line's download gives the new files the permissions the download holds
  (rwxr-xr-x for the executable, rw-r--r-- for its libraries), where it gave them the shell's default (group-writable
  under a umask of 002). It says each check as it passes: the checksum, and the new executable's version.

## [0.20261109.1] - 2026-11-09

### Added

- `plan.mechanism.purge.tower.first_layer.speed`: the purge tower's first layer, its brim included, no faster than this.
  Unset in a config of yours, it is the object's first-layer speed, as before.

### Changed

- The purge tower stands beside the object by default: on its right, back, left or front, the first side with room on
  the bed, centered along it, `plan.mechanism.purge.tower.gap` (5 mm, a placeholder) clear of the object and its brim. It
  stood at 70 % of the bed's width and depth, wherever the object was, often far from it; beside it, its first layer
  holds where the object's does. A place you set (`plan.mechanism.purge.tower.x` and `.y`, both) is kept.
- The purge tower above its first layer is laid at `plan.mechanism.purge.tower.speed`, the visible lines' speed unless
  set (it was the hidden lines', the machine's print speed, often several times the walls' beside it). Set it faster
  where a tower is known to hold.

### Fixed

- After a pause, every nozzle's temperature is set back to what it was and the tool in hand waited for before printing
  resumes. A printer may turn its nozzles off while paused (the bed kept warm): where no tool change follows the pause
  to heat the tool, printing would resume with a cold nozzle. A printer that keeps them on resumes at once.
- A purge tower's tool changes start their purge at a different corner of their band in turn (four, layer by layer and
  change by change), where every change started at one point: a nozzle back from the dock lays its first plastic there,
  and every change put it in one spot. Each change purges as much as before; the rest of its band fills from the other
  side.
- A purge tower's first layer is laid at the first layer's line width and spacing, as the object's is: it was laid at
  the tower's own (the nozzle's) width, narrower and with less plastic per millimeter than the object's first layer
  beside it (0.4 mm against 0.5 mm with a 0.4 mm nozzle).

## [0.20261109.0] - 2026-11-09

### Added

- `plymeld updates`: whether plymeld checks for a newer release, off until you turn it on (`--set notify` or
  `install`), and `--check` to ask now. Turned on, every command run in a terminal asks the release feed in the
  background, at most once a day, and says a newer release in one line on stderr after the command; the command line
  installs nothing by itself (`plymeld update` does, when you run it). One setting for the command line and the window:
  the window's settings offer the same three.
- `plymeld update`: installs the newest release over this one. An install from the Windows installer, the portable zip
  or the AppImage is updated by its own updater as the command exits, the window included. The Linux command line's
  download has its files replaced where it was unpacked, once the download matches the checksum now published beside
  it (`plymeld-linux-x64.tar.gz.sha256`) and the new executable's `--version` says the expected version; otherwise
  nothing changes. On Windows, other plymeld processes running from the install (a window, a command in another
  terminal) keep its files from being replaced: the update names them and stops, and `--stop-others` stops them first.
  The window's own install does the same: its bar names them, and a second click stops them and installs.

### Changed

- The window checks for updates only once you turn it on (it checked by default). A choice made in its settings before
  is kept.

### Fixed

- A purge tower's hold on the bed. After a tool change the new filament lays its purge lines inside the tower first and
  its wall last, so its first, least even plastic goes inside, not on the edge: every change had started the wall at
  the same corner, piling a blob there each time. The tower's brim reaches into its outline
  (`plan.mechanism.purge.tower.brim.overlap`, a placeholder: a quarter of a first-layer line) so the two fuse; it only
  touched the tower, laid by another tool before it, and a brim that only touches can stay down while the tower lifts
  off it. The brim is twice as wide as the object's brim would be (it was as wide).
- Requests to a printer are plain HTTP/1.1. They asked the printer's web server to switch to HTTP/2 on each new
  connection (an upload's included), which printers' small servers do not speak, and a printer could refuse an upload
  for it.
- A printer refusing a request says why where it gives a reason: a Flashforge printer's answer other than HTTP 200 is
  quoted after the status. A refusal it cuts short (it answers, then closes the connection before its reason arrives)
  is said to be a refusal, where plymeld said it "could not reach" the printer.
- On a model of several parts in different filaments, a part's outer-wall speck (a wall line kept only because it lines
  the outline, where the part is too thin for a loop) shorter than a line width is left out where another part lies
  within a line of it: the other part fills the outline there. Each was a dot of filament stopped and restarted on the
  visible face every layer, stacking into a column of blobs.
- Least-visible seams on a model of several parts in different filaments: a part's edge pressed against another part is
  covered by it and hides a seam. Each part's seams were placed seeing that part alone, so such an edge read as an
  open wall, and the seams went to corners on the visible face instead.
- After a tool change, the new filament's first lines on the model are its infill on that layer, hidden inside it, then
  the rest in their usual order. Its outer wall had come first: a nozzle back from the dock lays its first, least even
  lines on the visible surface. On a layer where the filament has no infill, its inner walls come first, before any outer
  wall. A line laid after another than it was planned after retracts on the travel into it.
- The installed window's update check never ran: the release feed's address was packaged beside the executables, where
  the window did not look. It is now built into both executables.
- A Flashforge printer's "canceling" (a cancel under way) reads as busy, where plymeld called it a status word it does
  not know: `printer status` says busy, an upload waits, and `printer watch` keeps watching until the print has ended.
- A purge tower's first layer is solid wall to wall. Where the first layer is thicker than the layers above it, its purge
  takes fewer lines than the tower holds, and the rest was left empty: the tower stood on its outline, its brim and a
  strip of purge, and the next layer's purge lines over the empty part were laid in the air above the bed.

## [0.20261108.0] - 2026-11-08

### Added

- `plymeld report`: a problem report about what you are working on, as a zip, and a GitHub issue filled in for you to
  attach it to and submit (`--open` opens it in the browser). The zip holds plymeld's version and the system, the
  config as plymeld reads it, the plan for the models given (`-m`), a g-code file's header and source block
  (`--gcode`), and the printer's status (`--printer`). The models and the whole g-code only with `--include-model`;
  never credentials; network addresses and the home folder's path hidden. In the window, from the finder.
- `plymeld printer clear`: tells the printer its bed is clear, the last print removed. A Flashforge Creator 5 keeps
  showing a finished print and refuses an upload ("Printer is busy") until told; `printer send` now says so and names
  the command, and the window's Send step offers "The bed is clear" there. On a Klipper printer it sends nothing:
  nothing is held back.
- A region's support rule, `plan.object.<key>.region.<key>.support`: `"always"` supports every downward face in the
  region whatever the switch or the weights decide, `"never"` keeps support off it, even with `plan.support.enabled =
  true` (its droop counted as unsupported area). A blocker, which plymeld did not have. An `"always"` region and a
  `"never"` region over the same faces are refused.
- Regions of an object, chosen by geometry: `plan.object.<key>.region.<key>` with a box in the model's own coordinates
  (`min`, `max`, turned with the pose), a height range as printed (`from`, `to`), the faces its lines lay (`facing`:
  walls, tops, undersides), or several together. A region's values apply to the lines in it; the first are the new motion
  caps, `mechanism.motion.speed.max` and `.acceleration.max` (also for the plate, an object, or a part): the lines in a
  region print no faster and accelerate no more, segment by segment, and the object stays one, with no walls where the
  region ends. A region outside the object, or a value plymeld does not read per region, is refused.
- `hardware.machine.<key>.firmware.arc_segment`: the length of the pieces the firmware cuts an arc into (Klipper's
  `[gcode_arcs]` resolution). No arc shorter than one piece is written: the firmware would run it as a single chord
  across it, so the stretch keeps its own lines. Unset, every arc that fits is written, as before. On the 3DBenchy at
  1 mm: 3,616 arcs where there were 4,428 (857 of them shorter than 1 mm), the filament unchanged.
- `plan.mechanism.motion.inner_walls.as_visible`: inner walls at the visible lines' speed and acceleration, so a wall
  laid fast right behind the outer wall does not ripple through it. Off by default (whether it shows is not measured
  yet); the plan's time estimates follow it.
- Coasting and a restore made while moving, both off until set. `hardware.filament.<key>.retract.coast.distance`
  lays the last mm of a path before a retraction with no filament pushed, the pressure in the nozzle laying them.
  `retract.return.distance` spreads the push back after a travel over the path's first mm, rather than standing still at
  its start, as much of it as the tool allows (`hardware.tool.<key>.extrude.section.max`, the firmware's limit on a
  move's extruded cross-section, Klipper's `max_extrude_cross_section`); the rest is pushed back standing still first.
  Without that limit every restore stands still, since a move past it stops a Klipper print. The pre-print check refuses
  a move past the limit.
- A machine's Z step (`hardware.machine.<key>.motion.z.step`, its Z motor's step as configured): flat layers are held
  to it, their height a whole number of steps (the nearest) and every top on a step. A layer height between two
  multiples otherwise prints alternating between them, in bands. Unset, layers are as planned; curved bands, layers
  that follow the underside, and lifted bridges keep heights of their own.
- `plymeld slice` says where the predicted time goes: a `predicted by feature:` line, each feature's minutes and share,
  longest first, travel among them.
- A purge tower stands steadier: a brim around its first layer by default (`plan.mechanism.purge.tower.brim.*`, as wide
  as the object's brim would be, whether or not the object has one), and each side at least half its height
  (`plan.mechanism.purge.tower.side_share.min`): a tower its purge would leave narrower is widened and deepened, its
  bands' lines beyond the purge laid at the sparse spacing. Both are placeholders, not tested on a print. The tower's
  bed and overlap checks count its brim.
- A config may spell a setting's name or a chosen value the British way: `colour` names `color`, and `centre` is read
  as `center` (`plan.placement`, a machine's `build.origin`, `--placement`), wherever the American spelling is what
  the setting takes. A key of your own (a filament called `grey`) is never respelled.
- The pre-print check refuses a layer with wave beads that leaves part of its area over air unlaid (a gap wider than a
  line between beads), saying how much, where, and with the layer's picture.

### Changed

- The locked support spots (`locked.<key>` under `plan.support`) are gone: support is locked on a region of the object, `plan.object.<key>.region.<key>`
  with `support = "always"` (its box as `min` and `max` as before, or a height range). A locked spot for every object at
  once is no longer possible; each object names its own. Configs with the old key are refused.
- The minimum layer time counts a travel's detour (round the layer's other islands, or inside its own) as the head goes,
  not as the straight line it replaces, so a layer is slowed only by what it still falls short.
- A concentric top or bottom (outside-in or inside-out) is laid as one path where its rings lie side by side: each ring
  starts at its point nearest where the last ended and joins onto it, so the surface has one start and one end, not
  a radial line of starts and stops, one per ring. A ring cut by a hole is still its own piece.
- `plan.bridge_order = "one_at_a_time"` lays the bridges from the side the head reaches least (the tools' head size and
  nozzle position, else their machine model's), as objects printed one at a time are placed, so the head never passes
  over a finished bridge. It always went from +X, which suited only a head reaching least towards +X.
- G-code files are shorter: a line width or acceleration already in force is not written again, and no travel goes to
  where the head already is (a scarf seam's ramp, loop and down pass each wrote all three). The motion is unchanged:
  every move runs with the same width, acceleration and feed rate as before.
- One seam vocabulary. `plan.seam` says where wall loops start, in the same words everywhere: `corners`, `back`,
  `least_visible` (were `corner_seam`, `back_seam`, `least_visible_seam`). Scarf seams are a switch beside it,
  `plan.technique.scarf_seam.enabled` (was `plan.seam = "scarf_seam"` with `scarf_seam.plain_style`, now gone).
  Each is locked on its own, or decided when unset: `plan.seam = "corners"` alone leaves scarf seams decided (set
  `scarf_seam.enabled = "false"` for none). Configs and plan files in the old words are refused; `plan -o` writes the new.
- A part whose own settings only restate what it would have anyway (a project's per-part keys repeating the object's)
  is laid with the parts beside it, as one area, with no walls or seams where they touch. It was laid part by part, each
  part walled on its own. A config already left such values out.
- The bundled filaments' idle temperature (a parked nozzle on a machine with several) is 100 C below printing, was
  40 C below (170 C for the bundled PLA), where a parked nozzle may ooze onto the print and the bed. The rise back to
  printing is the next tool's pre-heat (`hardware.tool.heat.time`) or its wait.

### Fixed

- In a Windows console, plymeld's text shows as written: `init`'s chooser (`❯`, `↑ · ↓`), dashes, `°`, `²` came out as
  `Γ¥»`, `ΓÇö` and the like (UTF-8 shown in the console's own code page, 437 in many places). The console is put in UTF-8
  for the run and given its own code page back at exit; output to a file or a pipe is unchanged.
- A model thinner than one layer (most often a file in other units: a model file holds none, and plymeld reads
  millimetres) is refused in one line saying so. `plan` and `slice` crashed on it with an index out of bounds (#63). A
  pose in which the model is thinner than a layer is ruled out.
- `plymeld init` shows a Creator 5's serial number as it is typed (it is on the printer's screen, not a secret), and
  says "typing is hidden" where input is hidden (the check code, an API key; `printer credential` too): a user could not
  tell that typing went in. The README says where the portable zip's command line is (`current\cli\plymeld.exe`): its
  top `plymeld.exe` opens the window.
- A purge tower's first layer is laid at the first layer's speed, no faster than the model's own first layer. It was laid
  at the tower's own speed (the hidden lines', often twice the model's first layer or more), too fast for a first
  layer to hold the bed.
- Wave overhangs: where the fronts close in from two sides (the roof over a narrow slot), the strip they leave between
  them gets a bead along its middle. It was left unlaid and not bridged: on the 3DBenchy, a strip 0.6 mm wide and 6 mm
  long over the stern's slot.
- Several lifted bridges in one object: the flat layers above a strip's end are laid onto the strip as onto a printed
  surface. They were planned as if over air, so wave beads or bridge lines were laid onto solid strip.

## [0.20261107.1] - 2026-11-07

### Changed

- Choosing a pose takes a first look at every pose under one plan (flat layers at the baseline's layer height) and
  searches in full only the as-given pose and those scoring within `method.search.pose.shortlist_margin` (0.1 per unit
  of weight, a placeholder) of the best there. `plan` lists each pose left out with its first-look score; unset the
  margin to search every pose. On the test models every choice is the same as before.
- A model of many parts slices faster, with the same g-code: the parts' outlines are merged layer by layer side by
  side, and the pose search merges each cross-section it reads once, not once per candidate plan. The search reuses one
  placed copy of the model per pose across its measures, and the retraction decision routes its two sides at once.
- The pose search spends its time where a pose can still win: the overhangs of a pose are surveyed only when the pose
  is looked at in detail, and a pose whose best possible score cannot reach the best candidates (lying on its side,
  needing support under most of the model) is ruled out without its strength and time estimates. `plan` says which
  poses were ruled out and why. Where the search runs to completion it chooses as before.

## [0.20261107.0] - 2026-11-07

### Added

- A multi-color model printed with the filaments you have, without editing it: `model.slots` maps the filament numbers
  the model file gives its parts to the loadout's slots (`model.slots = { 4 = 1, 5 = 2 }`; `"*"` for every number; an
  object's own `model.object.<key>.slots` first), and `--slot N` on `slice` and `plan` prints every part from slot N. A
  part on a filament that is not loaded is refused with both named, and the window's Model step offers "Print every
  part from slot N". plymeld says which of the model's filaments print from another slot.

### Changed

- A 3MF whose parts are all on one filament is said to have its parts "all on one filament" (it said "sliced as one
  body", which depends on the config as well).
- Deciding how to print a model is faster, with the same decisions and the same g-code. The slicer prepares each posed model once rather than for each cut, and
  finding what an overhang stands on looks only at the faces under it. On a large model, where the search stops at its
  time limit (`method.search.time.max`), it measures more candidates in that time.
- `plymeld calibrate ironing` and `plymeld calibrate seams` print their first layer at the filament's first-layer
  temperature (`temperature.first_layer`), as a print does, and the rest at its print temperature: the head waits,
  parked in front of the plate, for the print temperature before the ironing plate's top and before the seam plate's
  pieces. Each plate's check and page name both temperatures.
- `slice` from a plan file plans each object's layers once: the in-island retraction the plan locks is no longer
  planned for again before the slice. Where the layers do not all fit the layer cache (a large model), this saves
  planning the whole model a second time.

### Fixed

- `plymeld plan -o` wrote the first layer's height twice in an object's table when a first-layer window decided it,
  and `slice` refused the plan file ("previously defined"); it is written once, with the window's reason.
- The prime line no longer follows the first object's edge in between two of its feet (an arch's, a bridge's piers),
  where it lay under the span: a side whose line would turn in there is skipped for the next.

## [0.20261106.3] - 2026-11-06

### Added

- Command-line docs: a guide to how the commands fit together (`docs/cli/README.md`: setup, the config, slicing,
  the printer, calibration), and a command reference (`docs/cli/reference/`), a page per command with every option,
  written from the commands themselves. `plymeld commands --markdown DIR` writes the reference.

### Changed

- The calibration plates run melt, flow, first layer, motion, then the rest: the first-layer plate now comes before the
  motion plate, whose tower needs a first layer that holds. `plymeld calibrate --help` and its recommendations list
  them in that order.
- `plymeld calibrate motion`: every face's speed stands raised on the slab in front of it, read from outside that face,
  so the plate is read without its page; the slab reaches 10.6 mm past the cross on every side (4 mm before) to hold them. The arm
  notches take the size every plate's tally notches have. The slab's first layer prints at the filament's first-layer
  temperature (`temperature.first_layer`, worked out from `--print-temp` when given), and the head waits, parked, for
  the print temperature before the tower.
- `plymeld calibrate first-layer` prints a table in one piece, like the melt plate: a column per temperature and a row per
  first-layer speed and nozzle height, every tile a 40 mm strip with its lines along it. Each column's temperature, each
  row's height, and each speed are raised two layers on a two-layer base along the plate's front and left (its
  front-left corner cut off, as on the other plates), where marker dots were. Every tile touches its neighbors and the
  base, and a frame of three layers over their walls, with as tall a border round the base, is the edge to peel the
  plate by on every side; the thin connectors between columns, which were hard to remove, are gone. The page adds a way
  to read the tiles: once peeled, held up to a light, gaps between a tile's lines show as bright streaks and pinholes. The defaults are tighter: the filament's print temperature up to its maximum at 10 C
  steps (210, 220, and 230 C for the bundled generic PLA), three speeds around the first layer's (half, as set, and half
  again), and four heights from its height up (three steps above it, a step a tenth of the nozzle: 0.2 to 0.32 mm for a 0.2 mm first layer on a 0.4 mm nozzle). The plate's
  conditions describe the new layout, so `first-layer values` reads only plates made from now on.
- The first-layer window is two readings per temperature, the lowest good nozzle height (every lower one bad) and the
  fastest good speed (`"210 C" = { height.min = "0.28 mm", speed.max = "120 mm/s" }`), in place of a grade per tile.
  `plymeld calibrate first-layer values 210=0.28,120 ... --plate-gcode PLATE` writes it into the filament's file, checked
  against the plate and recorded for `plymeld journal --undo`; it replaces `first-layer window`. A window in the old
  grid form is refused: enter its readings again.
- `--help` names every option's value (`-o FILE`, `--layer MM`, ...), and `plymeld --help` lists `printer credential`
  and `investigate predict`, which it left out.
- `--help` marks a required option `(required)`, a subcommand that may be left out `[<command>]` (a plate runs without
  its `values`), and every option with a default says it in its help (`--tool`'s 1,
  `layers --layer`'s 0.2, ...).
- The window shows each command as `--help` does: a form's fields are labeled with the option as `--help` writes it
  (`-cf, --config-file=FILE|GLOB`, `--machine=<key>`) and described in its words, an argument as the usage line writes it
  (`[<prefix>]`, `<temp=flow>...`); a group's form lists its commands, each opening its own form, and offers Run only
  where the command runs on its own. The command reference (`docs/cli/reference/`) writes them the same way.
- The window's console shows every command it runs as a terminal would, `$ plymeld ...`, the job's own steps included.

### Fixed

- `plymeld render-plate` crashed ("negative width") on a first-layer plate of many blocks, such as the default nine.
- On Windows, a config glob (`-cf "C:\printers\*.toml"`) was refused ("Illegal char <*>") by every command, and the
  window could not open a job given one; a glob now reads on Windows as it does elsewhere, and the window opens the
  files it names.
- In the window, `--help` and `--version` printed nothing (a form's Run, or a line typed in the console).
- The window's Slice & check wrote another file than `plymeld slice` on the same job: the same toolpaths, but its source
  block left out the seams and the in-island retraction decided (the plan was decided twice, the second time over the
  first one's answers) and listed the filament's color among the values the print used (the window reads it to tint the
  view). The window and the command line now write the same file, byte for byte.
- In the window, the bar's search box cut its hint off mid-word, and the finder cut each hit's description at a fixed
  length mid-word; both now end in an ellipsis where they do not fit. A command's form said the job's files were filled
  in when no job was open.

## [0.20261106.2] - 2026-11-06

### Fixed

- `plymeld calibrate --help` said every plate writes an instructions page (the melt and flow plates do not yet) and
  left `melt values` and `flow values` out of the plates' write-back commands.

## [0.20261106.0] - 2026-11-06

### Added

- `plymeld calibrate flow`: the flow ratio on a plate of its own, after the melt plate. A column per temperature, a
  square patch per flow ratio (`--flow-ratios`), each patch's ratio and each column's temperature printed on the plate;
  only the patches' top layer is laid at the column's temperature, after a wait.
- `plymeld calibrate melt values TEMP=FLOW...`: a read melt plate's results written into the config file that defines
  the filament, as its maximum flow on the machine: a formula through every reading, straight between neighboring
  temperatures and flat past the ends, with the readings in the comment above it. The change is printed and checked
  first, `--dry-run` writes nothing, and `plymeld journal --undo` takes it back.
- `plymeld calibrate flow values TEMP=RATIO...`: a read flow plate's results, the best patch in each column, written as
  the filament's flow ratio in the same way: one value when the columns agree, else a formula through every reading.

### Changed

- Every move's flow keeps a buffer below the filament's maximum flow: `plan.mechanism.motion.melt.buffer`, 10 % by
  default (moves capped at 90 % of `melt.flow.max`), 0 % for none. Moves the melt rate limits print 10 % slower; the
  melt plate's rows, which probe the limit itself, are not held to it.
- `plymeld calibrate melt` prints a table: a column per temperature (10 °C apart by default), a row per flow from half
  the filament's expected maximum flow to twice it (`--flow-step`), each row's flow and each column's temperature
  printed on the plate. Its base prints at the filament's own temperatures; the columns are waited for one by one.
- The filament plate no longer has flow-ratio patches (`--tests flow`, `--flow-ratios`): the flow plate measures the flow
  ratio before the plates that depend on it.

### Fixed

- The filament plate's recommendation named the key to enter a measured melt rate under as `melt.flow.maxw`; it is
  `melt.flow.max`.

## [0.20261104.3] - 2026-11-04

### Fixed

- `plymeld init` drew its choice lists blank in a terminal that reports no width (a pseudo-terminal whose size was
  never set); it now draws them 80 columns wide there.
- `plymeld init` said Esc stops it; Esc does nothing in its lists and prompts. It now says Ctrl+C, which stops it at
  any question with nothing written.
- `plymeld init --folder DIR` ended by saying no `-cf` is needed; that is so only for plymeld's own config folder. For
  another folder it now says to pass the files with `-cf 'DIR/*.toml'`.

## [0.20261104.2] - 2026-11-04

### Added

- `plymeld printer send --print --level` asks the printer to level the bed before it prints, where its API takes that
  request (a Flashforge printer); `connection.start.level = true` on a machine makes it that machine's default, and
  `--no-level` prints without. A printer that levels from g-code instead (a Klipper printer, over Moonraker) refuses it
  before anything is sent.

## [0.20261104.1] - 2026-11-04

### Changed

- `plymeld init` sets up a Flashforge Creator 5 with Flashforge's own start sequence (the first tool selected and
  heated, the fans off) and an end that lifts clear of the print and switches every tool's heater off; a print speed of
  300 mm/s and a travel speed of 600 mm/s (the maker's figures); `M25` to pause; the 140x110 preview its screen shows;
  a nozzle limit of 320 C; and the shape of its heads.
- For any printer without its own start and end, `plymeld init`'s start now selects the print's first tool and
  heats that one (on a printer that selects tools by `T`), and its end lifts the head 10 mm clear of the print,
  within the build height, and turns the fans off.
- `plymeld init` offers travel at the printer's top speed rather than its print speed, and asks for its pause
  command, its preview pictures and each nozzle's top temperature where the printer's model names them.

### Fixed

- `plymeld slice` no longer calls a machine's `motion.acceleration.max` unused: the print and travel accelerations
  `plymeld init` writes are formulas of it.

## [0.20261104.0] - 2026-11-04

The first public release. [README.md](README.md) says what plymeld does and [FEATURES.md](FEATURES.md) lists it feature by
feature. In short:

- **Plans each print**: the pose on the bed, layer heights, the technique for each region, support, and the order of
  objects, weighed against your priorities (quality, accuracy, speed, material, strength, and reliability);
  `--explain` shows the facts, the alternatives, and why each lost.
- **Every value is a setting** with its source, bundled printer and filament values included; lock any decision and
  the rest is planned around it.
- **Slices to Klipper g-code** and checks the result against the machine and the model before you print.
- **Calibration plates** for melt, motion, filament, first layer, ironing, and seams, whose readings go back in as your
  filament's and machine's values.
- **Talks to printers** over Flashforge's LAN API and Moonraker: status, camera, files, send, pause, resume, cancel,
  and the loaded filaments compared with your config (`loadout -o` writes what the printer has, empty tools included,
  as a config file). Credentials stay out of config files: `plymeld printer credential` sets one in a secrets file,
  never from the command line.
- **A desktop window** beside the command line: a Windows installer (or portable zip) and a Linux AppImage, with the
  command line for Linux as its own download.
