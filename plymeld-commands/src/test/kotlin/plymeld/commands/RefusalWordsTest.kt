package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.ForkJoinPool

/**
 * A refusal prints its own words. Raised in parallel work it reaches the command line as the fork-join pool's copy, whose
 * message is the original's `toString()`; [Commands.said] shows the original's message. Controls: a plain refusal and one
 * with words of its own beside a cause keep their message.
 */
class RefusalWordsTest : StringSpec({
    "a refusal raised in a fork-join task prints its own words, not the copy's" {
        val thrown = runCatching {
            ForkJoinPool.commonPool().submit<Unit> { error("plan.x needs plan.y (what it is)") }.join()
        }.exceptionOrNull()!!
        (thrown.message == thrown.cause.toString()) shouldBe true          // the copy, as the pool makes it
        Commands.said(thrown) shouldBe "plan.x needs plan.y (what it is)"
    }

    "control: a plain refusal, and one with its own words beside a cause, keep them" {
        Commands.said(IllegalStateException("plain words")) shouldBe "plain words"
        Commands.said(IllegalArgumentException("its own words", IllegalStateException("the cause's"))) shouldBe "its own words"
    }
})
