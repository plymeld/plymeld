package plymeld.geom

import io.kotest.core.Tag

/**
 * Tests that read models not in the repository (local-only, git-ignored; test-models/MANIFEST.md).
 * Skipped unless the build runs with `-Pplymeld.localFixtures` (buildSrc conventions).
 * TO RE-ENABLE: move the test onto generated or public models and drop the tag.
 */
object LocalFixtures : Tag()
