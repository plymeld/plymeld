package plymeld.check

import plymeld.config.ConfigFiles

/**
 * The seam the bundled defaults locked earlier (scarf seams on the smooth loops, corner seams elsewhere), for
 * tests that plan straight from the bundled files without `plymeld plan`'s seam decision (plan.seam unset is
 * decided per object by Product, not by the planner); the behavior these tests were written against.
 */
val seamLockedForTests = listOf(ConfigFiles.Entry(listOf("plan", "seam"), "corners", "test (the earlier bundled default)"),
    ConfigFiles.Entry(listOf("plan", "technique", "scarf_seam", "enabled"), true, "test (the earlier bundled default)"))
