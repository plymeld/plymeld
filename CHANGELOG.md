# Changelog

What changed in plymeld, for people using it. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Versions are `0.YYYYMMDD.N`: the release's date (UTC) as one number, and N counting the releases of that day
(`plymeld --version`). Changes collect under Unreleased; a release is a tag, and the change after it gives that
section the release's version and date.

## [Unreleased]

### Added

- Short walls slower: with `plan.mechanism.motion.short_walls.length` set, a wall line or loop shorter than it prints
  no faster than `short_walls.speed` (bundled: half the visible lines' speed, a placeholder). With
  `short_walls.full_length` set too, a wall between the two lengths prints no faster than a speed rising in a straight
  line from `short_walls.speed` to its own, the whole wall at that one speed; unset, it is a single step at `length`. A
  scarfed loop counts as long as its loop. Unset, nothing changes.
- A line no faster than it can hold steady: with `plan.mechanism.motion.steady.share` set, every printed line is laid no
  faster than the speed at which that share of its length is laid at a steady speed, starting and stopping at rest at
  its acceleration: `sqrt(acceleration * length * (1 - share))`, the whole line at one speed. Not the purge tower, the
  prime line, or a lifted bridge's own speeds over air. Unset, nothing changes.
- `plan.layer.sliver.width`: on an object of several filaments, how thin an area of one filament is handed to the
  filament beside it. Unset, it is the thinnest area the walls print, as before. The pre-print check allows a line that
  much further from its own tool's part.
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
  were laid ahead of its outer walls, whatever `plan.layer.walls.order` said (about half of a two-color nameplate's
  layers printed inner walls first). Its lines now keep their planned order there; where it lays infill, the infill
  still comes first and the walls follow in their order.
- Several multi-color objects on one plate: an object on several filaments was sliced on its own or refused. On each
  layer each filament now lays its lines of every object in turn, so the plate changes tool no more often than one of
  its objects would, with one purge tower beside them all. Copies of an object (`model.object.<key>.count`) can each
  take settings of their own (`plan.object."<key>#2".…`), for trying variations side by side. The objects print
  together, by height, each on its own flat layers; one object at a time is not written for such a plate yet.
- A section of a model printed on its own: `model.object.<key>.section.min` and `.max` give a box in the model's own
  coordinates, and only what is inside it prints. For trying a detail of a large model (one face, a line of lettering)
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

### Changed

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
  from all around (the default before), a plain round wall's seams stayed wherever a feature first put them, on a
  nameplate's front face from its lettering to its top edge. A model with no front of its own sets `all_around`.
- Tool and filament changes are made over where the incoming filament first prints (the purge tower when there is one),
  after the outgoing tool leaves the part with its usual retraction, wipe, and lift and travels there. The change was
  made standing on the outgoing filament's last line, which left ooze on the part: on a plate, every change stood on
  the last object's face.
- On an object of several parts, an outer wall whose seam is pressed against another part (that part within one line
  width of the seam on the layer) gets a point seam, not a scarf: the seam is hidden there, and the scarf's ramp and its
  closing pass ran on from it onto the faces that show. On a small island of one filament inside a letter of another,
  the ramp ran from a seam hidden against the letter round the island's front corner and across its whole front, laying
  a thin line and a second over it there, and the print showed a hump. Least-visible seams count such a seam as the
  point seam it is laid as, so they no longer avoid a covered spot because a scarf would be better elsewhere.
  `plan.technique.scarf_seam.covered = "scarf"` (per object or part too) scarfs a covered seam as before; seams that are
  not covered, and every seam of an object of one part, are laid as they were.

### Fixed

- On an object of several parts, what counts as another part beside a part's lines (for a seam pressed against one, and
  for a speck of outer wall left out in front of one) is the other parts as laid. It was the object's outline less the
  part, which is closed (`method.slicing.close_gaps`) and so left hairline slivers along a round open edge that read as
  another part there: 18 of the 97 points of a round base's open outer wall.

- A region's speed and acceleration caps (`mechanism.motion.speed.max` and `.acceleration.max` under
  `plan.object.<key>.region.<key>`) apply to an object printed in several filaments, alone or on a plate, and to the other
  objects on such a plate; they were ignored there.
- The step from an inner wall onto the outer wall's start, and from it onto the wall beside it, goes unretracted only
  within two line widths: in a letter's narrow stroke, steps of 2 to 5 mm crossed the pocket's edge and drew strands.
- A plate of several objects heats the next tool ahead of a change as one object does (`hardware.tool.<key>.heat.time`);
  it waited at every change.
- A retraction's wipe is written under the feature it wipes: the next path's `;TYPE:` and `;WIDTH:` lines came first, so
  a wipe along an inner wall was labelled as the outer wall that followed.
- A scarf seam is judged by its whole ramp, not its start alone, where it is placed and in the seam measure: the seam
  goes where the most-seen point of its ramp is seen least. A nameplate's seams started hidden in a letter's pocket, and
  their 10 mm ramps ran out onto the face beside the letter. (A seam another part covers is now laid, and judged, as a
  point seam: under Changed.)
- Least-visible seams are planned up the whole wall, not layer by layer. Each layer's seam stayed in line with the one
  below rather than pay the move to a better place on its own, so a seam drawn to the front by a feature low on the
  wall ran up the front face above it to the top. Planned over all the layers a loop continues through, the move is
  paid once where it costs least: on a round column with a slot in its front, the 118 seams above the slot went from
  the front face to the back, in one line. Least-visible placement also counts a scarf seam's blemish as the seam
  measure does (`method.quality.seam.scarf_share` of a point seam's); counted as a point seam's, a move to a less-seen
  point looked worth more than it was.

## [0.20261109.1] - 2026-11-09

### Added

- `plan.mechanism.purge.tower.first_layer.speed`: the purge tower's first layer, its brim included, no faster than this.
  Unset in a config of yours, it is the object's first-layer speed, as before.

### Changed

- The purge tower stands beside the object by default: on its right, back, left or front, the first side with room on
  the bed, centered along it, `plan.mechanism.purge.tower.gap` (5 mm, a placeholder) clear of the object and its brim. It
  stood at 70 % of the bed's width and depth, wherever the object was; a tower at the bed's corner, far from the object,
  peeled off there twice. A place you set (`plan.mechanism.purge.tower.x` and `.y`, both) is kept.
- The purge tower above its first layer is laid at `plan.mechanism.purge.tower.speed`, the visible lines' speed unless
  set (it was the hidden lines', the machine's print speed: 300 mm/s on a Creator 5, beside walls laid at 100). Set it
  faster where a tower is known to hold.

### Fixed

- After a pause, every nozzle's temperature is set back to what it was and the tool in hand waited for before printing
  resumes. A Creator 5 turns its nozzles off while paused (the bed kept warm): where no tool change follows the pause to
  heat the tool, printing would resume with a cold nozzle. A printer that keeps them on resumes at once.
- A purge tower's tool changes start their purge at a different corner of their band in turn (four, layer by layer and
  change by change), where every change started at one point: a nozzle back from the dock lays its first plastic there,
  and on a two-color nameplate all 188 changes put it in one spot, by the tower corner that peeled. Each change purges as
  much as before; the rest of its band fills from the other side.
- A purge tower's first layer is laid at the first layer's line width and spacing, as the object's is: it was laid at
  the tower's own (the nozzle's) width, narrower and with less plastic per millimeter than the object's first layer
  beside it (0.4 mm against 0.5 mm, 0.038 against 0.051 mm of filament per mm, on a two-color nameplate).

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

- A purge tower's hold on the bed, after one came loose twice. After a tool change the new filament lays its purge lines inside the tower first and
  its wall last, so its first, least even plastic goes inside, not on the edge: every change had started the wall at
  the same corner, piling a blob there each time (188 times on a two-color nameplate), and that corner peeled off the
  bed. The tower's brim reaches into its outline (`plan.mechanism.purge.tower.brim.overlap`, a placeholder: a quarter
  of a first-layer line) so the two fuse; it only touched the tower, laid by another tool before it, and the brim
  stayed down while the tower lifted off it. The brim is twice as wide as the object's brim would be (it was as wide).
- Requests to a printer are plain HTTP/1.1. They asked the printer's web server to switch to HTTP/2 on each new
  connection (an upload's included), which printers' small servers do not speak; a Creator 5 refused several uploads
  in a row ("Download failed" on its screen) where the same files had gone through before.
- A printer refusing a request says why where it gives a reason: a Flashforge printer's answer other than HTTP 200 is
  quoted after the status. A refusal it cuts short (it answers, then closes the connection before its reason arrives,
  as a Creator 5 did refusing an upload) is said to be a refusal, where plymeld said it "could not reach" the printer.
- On a model of several parts in different filaments, a part's outer-wall speck (a wall line kept only because it lines
  the outline, where the part is too thin for a loop) shorter than a line width is left out where another part lies
  within a line of it: the other part fills the outline there. Each was a dot of filament stopped and restarted on the
  visible face every layer; on a two-color nameplate, 142 of them stacked into columns of blobs on the lettering's face.
- Least-visible seams on a model of several parts in different filaments: a part's edge pressed against another part is
  covered by it and hides a seam. Each part's seams were placed seeing that part alone, so such an edge read as an
  open wall, and the seams went to corners on the visible face instead. On a two-color nameplate, the inlaid lettering's
  seams on the front face went from 587 to 205 of about 1,700, and the seam measure from 6.5 to 1.5 mm².
- After a tool change, the new filament's first lines on the model are its infill on that layer, hidden inside it, then
  the rest in their usual order. Its outer wall had come first: a nozzle back from the dock lays its first, least even
  lines on the visible surface. On a two-color nameplate, every one of 94 changes back to the base color began on the
  lettering's front face. On a layer where the filament has no infill, its inner walls come first, before any outer
  wall. A line laid after another than it was planned after retracts on the travel into it.
- The installed window's update check never ran: the release feed's address was packaged beside the executables, where
  the window did not look. It is now built into both executables.
- A Flashforge printer's "canceling" (a cancel under way) reads as busy, where plymeld called it a status word it does
  not know: `printer status` says busy, an upload waits, and `printer watch` keeps watching until the print has ended.
- A purge tower's first layer is solid wall to wall. Where the first layer is thicker than the layers above it, its purge
  takes fewer lines than the tower holds, and the rest was left empty: the tower stood on its outline, its brim and a
  strip of purge, and the next layer's purge lines over the empty part were laid in the air above the bed. On a
  two-filament print with a 0.28 mm first layer under 0.08 mm layers, about three quarters of the tower's first layer
  was empty; that print's tower came loose partway up.

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
  not as the straight line it replaces, so a layer is slowed only by what it still falls short. On the 3DBenchy, 25
  slowed layers each run up to 0.04 s faster.
- A concentric top or bottom (outside-in or inside-out) is laid as one path where its rings lie side by side: each ring
  starts at its point nearest where the last ended and joins onto it, so the surface has one start and one end, not
  a radial line of starts and stops, one per ring. A ring cut by a hole is still its own piece.
- `plan.bridge_order = "one_at_a_time"` lays the bridges from the side the head reaches least (the tools' head size and
  nozzle position, else their machine model's), as objects printed one at a time are placed, so the head never passes
  over a finished bridge. It always went from +X, which suited only a head reaching least towards +X.
- G-code files are shorter: a line width or acceleration already in force is not written again, and no travel goes to
  where the head already is (a scarf seam's ramp, loop and down pass each wrote all three). The motion is unchanged:
  every move runs with the same width, acceleration and feed rate as before. On the 3DBenchy, 1,570 width lines,
  2,590 acceleration lines and 1,055 zero-length travels fewer.
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
  at the tower's own speed (the hidden lines', about 250 mm/s on a Creator 5 where the model's first layer went at
  100-120), and a tower came loose during a print.
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
  side, and the pose search merges each cross-section it reads once, not once per candidate plan. On a 29-part,
  4.7-million-triangle model the slice took half the time (54 s to 28 s) and the seam decision less than half. The
  search reuses one placed copy of the model per pose across its measures, and the retraction decision routes its two
  sides at once (on that model the pose search 154 s to 129 s, the retraction decision 27 s to 20 s).
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
- Deciding how to print a model is faster: on 3DBenchy the pose and technique search takes about a quarter less time,
  with the same decisions and the same g-code. The slicer prepares each posed model once rather than for each cut, and
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
