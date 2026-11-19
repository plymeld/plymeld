<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->

# `plymeld render`

[Command reference](README.md)

Draw what any g-code file lays down, read from the file alone: its layers from above (--layer, --layer-at-z, --layer-range; the middle layer by default), cuts through it (--cut-z from above; --cut-y, --cut-x, --cut-line, --cut-plane from the side, each line crossing the plane as its bead's cross-section), its outside from a side (--outside) or in 3D (--3d), and two files compared (--compare-with), with the model's outline (--outline-of) and the pre-print check's findings (--mark-bare, --mark-laid-twice) over the lines; the lines chosen by object, tool, or feature (--only-...), colored by feature, tool, speed, flow, width, height, fan, temperature, order, or layer time, each line as wide as the plastic it lays, framed round a point (--around, --span) or a window of the bed, at a scale that keeps the closest lines 3 px apart. Every picture's caption says what it shows. Lengths in mm, the first layer 1, tools by the file's own numbers.

Usage: `plymeld render [<options>] <file.gcode>`

Arguments:

- `<file.gcode>`: the g-code file to draw (any slicer's; also a .gcode.3mf)

Options:

- `--layer=<n>`: draw layer N from above; the first layer is 1; repeatable, or a list (5,10,20). With no view option at all: the middle layer
- `--layer-at-z=<mm>`: draw the first layer whose top is at or above this height (mm, a bare number or 5mm), as --pause-at-z picks it; repeatable, or a list
- `--layer-range=<a..b>`: draw layers A to B, one picture each (the first layer is 1); repeatable
- `--layer-step=<k>`: with --layer-range: every K-th layer of the range, from its first (default 1, every layer)
- `--cut-z=<mm>`: draw a level plane at this height (mm), seen from above: each bead it crosses as wide as the bead is there. Not a layer: where layers are curved or lines lowered, it meets several; repeatable, or a list
- `--cut-y=<mm>`: draw the vertical plane at this Y (mm, bed coordinates), seen from the front: X across, height up; each line crossing it as its bead's cross-section; repeatable, or a list
- `--cut-x=<mm>`: draw the vertical plane at this X (mm, bed coordinates), seen from the right: the bed's front at the picture's left, height up; repeatable, or a list
- `--cut-line=<x0,y0:x1,y1>`: draw the vertical plane along this line on the bed (mm), the first point at the picture's left, height up; repeatable
- `--cut-plane=<px,py,pz:nx,ny,nz>`: draw any plane, by a point on it (mm) and the direction it faces, seen from the side that direction points to; a level plane facing up is --cut-z; repeatable
- `--cut-depth=<mm>`: with a cut: also the lines within this distance (mm) of its plane that do not cross it, drawn lighter (from the side in a vertical cut, so a wall the cut follows shows as a wall)
- `--z-range=<z0..z1>`: with a cut seen from the side, --outside, or --3d: only these heights (mm). Not with layer pictures or --cut-z alone
- `--height-stretch=<factor>`: with a cut seen from the side or --outside from a side: how many times taller than true its heights are drawn (1: true proportions). Default: enough for a layer to be at least 3 px tall; the factor is in the caption
- `--outside=<side>`: draw the print's outside seen from this side: front, back, left, right, or top; its outermost lines (the nearest bead at each point), not a cut; repeatable
- `--3d`: draw the print in 3D, from the front-left above (turn -35, tilt 30 degrees), orthographic, each line a bead shaded as a tube
- `--3d-from=<turn,tilt>`: with --3d: where it is seen from, in degrees: 0,0 is level from the front, turn goes round to the right, tilt rises (90 is from above); repeatable, one picture each
- `--only-object=<name>`: only the lines of this object, named as the file names it (--list-objects); repeatable: any of them
- `--only-part=<name>`: only the lines of this part, named as the file names it; repeatable: any of them. Refused for a file that names no parts
- `--only-tool=<n>`: only the lines this tool lays, by the file's own tool numbers (T0 is 0); repeatable, or a list: any of them
- `--only-feature=<name>`: only the lines of this feature, named as the file writes it (--list-features), e.g. 'Outer wall'; repeatable: any of them. Different --only-... options together: a line must pass all of them
- `--others=hide|faint`: what becomes of the lines the --only-... options leave out: hidden (default) or drawn in gray under the chosen ones
- `--color-by=<kind>`: what a line's color says: feature (default), tool (the filaments' own colors where the file states them), speed (mm/s), flow (mm³/s), width (mm), height (layer thickness, mm), fan (part fan, %), temperature (nozzle, C), order (print order), or layer-time (the layer's time at its commanded speeds, s)
- `--line-width=plastic|declared|center`: how wide each line is drawn: as wide as the plastic it lays, by the bead model (default; needs the filament's diameter, from the file or its source block), as wide as the file declares (;WIDTH:), or as a thin center line
- `--show=<mark>`: with a layer picture: add marks: travels, seams, retractions, starts, or tool-changes; repeatable
- `--layers-below=<n>`: with a layer picture: also the N layers under it, in light gray (default 0)
- `--bed-window=<x0,y0,x1,y1>`: draw only this rectangle of the bed (mm, in the g-code's coordinates); a cut seen from the side, only where its plane crosses the rectangle; --outside and --3d, only the lines in it. Not with --around
- `--around=<x,y>`: draw only the square of side --span centered on this point of the bed (mm, in the g-code's coordinates, as plymeld's check messages give them); needs --span. Not with --bed-window
- `--span=<mm>`: the side of the square --around draws (mm); needs --around
- `--same-frame`: every layer picture of the run in one frame and at one scale, for flipping through a range
- `--px-per-mm=<n>`: the scale, pixels per mm. Default: the scale at which the closest lines in view lie at least 3 px apart, the picture between 1000 and 4096 px on its longer side (at most 2048 where the lines cannot be set 3 px apart and are drawn as flat regions). Not with --picture-size
- `--picture-size=<wxh>`: fit the drawing into W by H pixels, its proportions kept. Not with --px-per-mm
- `--allow-unresolved-lines`: draw lines even where the scale cannot set them 3 px apart (by default such a picture shows each feature's area as a flat region with its outline, at most 2048 px on its longer side, and the run names the --span that would resolve them); with this option, up to 4096 px at the default scale
- `--no-annotations`: the drawing alone: no caption, scale bar, or color key
- `-o, --output=<dir>`: the directory the pictures go in (default: beside the g-code, its name without .gcode and with .render: part.render for part.gcode); one file a picture, named from its view: layer-0060.png
- `--format=png|svg`: the pictures' format (default png)
- `--sheet=<file>`: also every picture of the run in one image, in a grid, each under its title, in the run's --format (a name without a directory goes in the output directory)
- `--outline-of=<model>`: with a layer picture or a cut: also the model's outline there (a layer at its middle height, a cut on its plane), a thin black line over the lines, never filled. The model is placed as plymeld placed it for the file (from the file's source block); a file without one: as the model file gives it
- `--mark-bare`: with a layer picture: mark the ground no line covers, by the pre-print check's own rules and the file's settings (its source block): the widest hole inside the walls and the outline left bare, their sizes in the caption; needs --outline-of
- `--mark-laid-twice`: with a layer picture: mark plastic laid twice, by the pre-print check's own rule and the file's settings (its source block): each spot ringed, its area beside it and the total in the caption; needs --outline-of
- `--compare-with=<other.gcode>`: each picture as three panels: this file, the other drawn with the same options in the same frame and scale, and where they differ (orange only in this file, blue only in the other, red in both but not alike)
- `--print-files`: print each written file's path, one a line, and nothing else (notes go to standard error)
- `--list-features`: print the feature names the file writes, one a line, and draw nothing. Not with an option that draws or writes a picture
- `--list-objects`: print the object names the file writes, one a line, and draw nothing. Not with an option that draws or writes a picture
- `-h, --help`: Show this message and exit
