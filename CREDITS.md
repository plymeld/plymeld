# Credits

plymeld builds on published work. Each entry says what plymeld takes from it: a method it implements, or the idea a
technique of its own starts from. All of plymeld's code is its own.

## Print techniques

- **Wave overhangs** (`plan.faces.under.technique = "wave_overhang"`, and a lifted bridge's waved first layer):
  implemented from the published method. Janis Andersons, Salomé Sanchez, and Tom Vaneker, "Wave-inspired
  path-planning strategy for support-free horizontal overhangs in FDM", *Additive Manufacturing Letters* 18 (2026),
  100392, [doi:10.1016/j.addlet.2026.100392](https://doi.org/10.1016/j.addlet.2026.100392). CC BY 4.0.
- **Z anti-aliasing of sloped tops** (`plan.faces.sloped_top.technique = "z_antialiasing"`): the idea's origin;
  plymeld's rules are its own. Hai-Chuan Song, Nicolas Ray, Dmitry Sokolov, and Sylvain Lefebvre, "Anti-aliasing for
  fused filament deposition", *Computer-Aided Design* 89 (2017), 25–34,
  [arXiv:1609.03032](https://arxiv.org/abs/1609.03032).
- **Variable-width walls** (`plan.layer.walls.style = "variable_width"`): the idea's origin; plymeld's construction is
  its own. Tim Kuipers, Eugeni L. Doubrovski, Jun Wu, and Charlie C.L. Wang, "A framework for adaptive width control of
  dense contour-parallel toolpaths in fused deposition modeling", *Computer-Aided Design* 128 (2020), 102907,
  [arXiv:2004.13497](https://arxiv.org/abs/2004.13497).

## Algorithms

- **Delaunay triangulation**, built incrementally (the Bowyer–Watson algorithm). Adrian Bowyer, "Computing Dirichlet
  tessellations", *The Computer Journal* 24 (2) (1981), 162–166,
  [doi:10.1093/comjnl/24.2.162](https://doi.org/10.1093/comjnl/24.2.162); David F. Watson, "Computing the
  n-dimensional Delaunay tessellation with application to Voronoi polytopes", *The Computer Journal* 24 (2) (1981),
  167–172, [doi:10.1093/comjnl/24.2.167](https://doi.org/10.1093/comjnl/24.2.167).
- **Line simplification** (the Douglas–Peucker algorithm). David H. Douglas and Thomas K. Peucker, "Algorithms for the
  reduction of the number of points required to represent a digitized line or its caricature", *Cartographica* 10 (2)
  (1973), 112–122, [doi:10.3138/FM57-6770-U75U-7727](https://doi.org/10.3138/FM57-6770-U75U-7727).
- **The medial axis**, the center lines that variable-width walls are laid along. Harry Blum, "A transformation for
  extracting new descriptors of shape", in W. Wathen-Dunn (ed.), *Models for the Perception of Speech and Visual Form*,
  MIT Press (1967), 362–380.

## Test models

- **#3DBenchy by CreativeTools**, [3DBenchy.com](https://www.3dbenchy.com/), CC0 1.0: the default test model, whole
  and in its two-color halves (`test-models/`, listed in [test-models/MANIFEST.md](test-models/MANIFEST.md)).

The libraries plymeld uses, with their licenses, are in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
