package plymeld.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A line typed in the console split into a command's words: spaces
 * separate, quotes group, a backslash escapes, as a shell would for plain words; nothing else is interpreted.
 */
class ConsoleWordsTest : StringSpec({
    "plain words split on spaces, runs of spaces are one" {
        JobState.splitWords("config   -cf a.toml  -m b.stl") shouldBe listOf("config", "-cf", "a.toml", "-m", "b.stl")
    }
    "quotes keep a path with spaces as one word" {
        JobState.splitWords("slice -m 'My Models/boat.stl' -o \"out dir/boat.gcode\"") shouldBe listOf("slice", "-m", "My Models/boat.stl", "-o", "out dir/boat.gcode")
        // control: unquoted, the same path is two words
        JobState.splitWords("slice -m My Models/boat.stl").size shouldBe 4
    }
    "a backslash escapes outside quotes and before a quote inside double quotes" {
        JobState.splitWords("edit f.toml plan.x=a\\ b") shouldBe listOf("edit", "f.toml", "plan.x=a b")
        JobState.splitWords("edit f.toml \"plan.label=say \\\"hi\\\"\"") shouldBe listOf("edit", "f.toml", "plan.label=say \"hi\"")
    }
    "an empty quoted word is a word; a blank line is none" {
        JobState.splitWords("edit f.toml ''") shouldBe listOf("edit", "f.toml", "")
        JobState.splitWords("   ") shouldBe emptyList()
    }
})
