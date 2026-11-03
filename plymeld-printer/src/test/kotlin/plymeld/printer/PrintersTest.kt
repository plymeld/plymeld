package plymeld.printer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigStack
import java.time.Duration

/**
 * The factory: a machine's `connection.*` opens the right adapter with its
 * credentials from the environment variables NAMED in the config; two machines of one model are two distinct printers;
 * what is missing is refused by name; a machine `like` another does not take over its connection.
 */
class PrintersTest : StringSpec({
    fun config(vararg entries: Pair<String, Any>) =
        Config(ConfigStack(ConfigFiles.bundled() + entries.map { (k, v) -> ConfigFiles.Entry(ConfigFiles.value("$k=0").path, v, "test") }), emptyList())
    val machine = { k: String -> listOf("hardware.machine.$k.model" to "qidi-q2", "hardware.machine.$k.build.x" to "200 mm", "hardware.machine.$k.build.y" to "200 mm",
        "hardware.machine.$k.build.z" to "200 mm", "hardware.machine.$k.firmware.flavor" to "klipper") }
    val env = mapOf("A_SN" to "SN-A", "A_CODE" to "CODE-A", "B_SN" to "SN-B", "B_CODE" to "CODE-B", "Q_KEY" to "KEY-Q")
    val detail = PrintersTest::class.java.getResource("/flashforge/creator5-detail-2026-10-09.json")!!.readText()

    "two machines of one model are two printers: each its own key, host and credentials, nothing shared" {
        val c = config(*(machine("shop-a") + machine("shop-b")).toTypedArray(),
            "hardware.machine.shop-a.connection.api" to "flashforge-http", "hardware.machine.shop-a.connection.host" to "a.test",
            "hardware.machine.shop-a.connection.credential.serial_number.env" to "A_SN", "hardware.machine.shop-a.connection.credential.check_code.env" to "A_CODE",
            "hardware.machine.shop-b.connection.api" to "flashforge-http", "hardware.machine.shop-b.connection.host" to "b.test", "hardware.machine.shop-b.connection.port" to "8899",
            "hardware.machine.shop-b.connection.credential.serial_number.env" to "B_SN", "hardware.machine.shop-b.connection.credential.check_code.env" to "B_CODE")
        val ta = Recorded { Recorded.json(detail) }; val tb = Recorded { Recorded.json(detail) }
        val a = Printers.open(c, "shop-a", env::get, ta); val b = Printers.open(c, "shop-b", env::get, tb)
        a.key shouldBe "shop-a"; b.key shouldBe "shop-b"
        a.state().key shouldBe "shop-a"; b.state().key shouldBe "shop-b"
        ta.sent[0].url shouldBe "http://a.test:8898/detail"; tb.sent[0].url shouldBe "http://b.test:8899/detail"
        ta.bodyText(0) shouldBe """{"serialNumber":"SN-A","checkCode":"CODE-A"}"""
        tb.bodyText(0) shouldBe """{"serialNumber":"SN-B","checkCode":"CODE-B"}"""
        ta.bodyText(0) shouldNotContain "SN-B"
    }

    "a moonraker machine opens without a credential; with api_key named, the key comes from that variable; port, timeout and camera read" {
        val c = config(*machine("q").toTypedArray(), "hardware.machine.q.connection.api" to "moonraker", "hardware.machine.q.connection.host" to "q.test",
            "hardware.machine.q.connection.timeout" to "2.5 s", "hardware.machine.q.connection.camera.url" to "http://q.test:8080/?action=snapshot")
        val cn = Printers.connection(c, "q", env::get)
        cn.protocol shouldBe Protocol.MOONRAKER; cn.port shouldBe 7125; cn.timeout shouldBe Duration.ofMillis(2500)
        cn.credentials shouldBe emptyMap(); cn.cameraUrl shouldBe "http://q.test:8080/?action=snapshot"
        val keyed = Printers.connection(config(*machine("q").toTypedArray(), "hardware.machine.q.connection.api" to "moonraker", "hardware.machine.q.connection.host" to "q.test",
            "hardware.machine.q.connection.credential.api_key.env" to "Q_KEY"), "q", env::get)
        keyed.credentials shouldBe mapOf("api_key" to "KEY-Q")
        Printers.open(keyed, Recorded { Recorded.json("{}") }).protocol shouldBe Protocol.MOONRAKER
    }

    "what is missing is refused by name: no connection, an unknown api, no host, a credential's variable unset (the control opens)" {
        val base = machine("c").toTypedArray()
        shouldThrow<IllegalStateException> { Printers.connection(config(*base), "c", env::get) }.message shouldBe
            "machine 'c' has no connection: set hardware.machine.c.connection.api (one of flashforge-http, moonraker) and hardware.machine.c.connection.host"
        shouldThrow<IllegalStateException> { Printers.connection(config(*base, "hardware.machine.c.connection.api" to "flashforge-http"), "c", env::get) }.message shouldBe
            "machine 'c' has no address: set hardware.machine.c.connection.host"
        val ok = arrayOf("hardware.machine.c.connection.api" to "flashforge-http", "hardware.machine.c.connection.host" to "c.test",
            "hardware.machine.c.connection.credential.serial_number.env" to "A_SN", "hardware.machine.c.connection.credential.check_code.env" to "C_CODE_UNSET")
        shouldThrow<IllegalStateException> { Printers.connection(config(*base, *ok), "c", env::get) }.message shouldBe
            "printer 'c': credential 'check_code' comes from environment variable C_CODE_UNSET, and none has it"
        shouldThrow<IllegalStateException> { Printers.connection(config(*base, *ok.take(3).toTypedArray()), "c", env::get) }.message shouldBe
            "printer 'c' needs credential 'check_code' for flashforge-http: set hardware.machine.c.connection.credential.check_code.env to the name of the environment variable holding it, or hardware.machine.c.connection.credential.check_code.file to a secrets file with a 'check_code' line"
        // control: with the variable set, it opens, and the value is never in a message
        Printers.connection(config(*base, *ok), "c") { if (it == "C_CODE_UNSET") "CODE-C" else env[it] }.credentials shouldBe mapOf("serial_number" to "SN-A", "check_code" to "CODE-C")
        // an api the schema refuses never reaches the factory: the config itself has the problem
        shouldThrow<plymeld.config.ConfigError> { config(*base, "hardware.machine.c.connection.api" to "carrier-pigeon") }.message!! shouldContain "'carrier-pigeon' is not one of flashforge-http, moonraker"
    }

    "a machine `like` another takes over its build volume but never its connection; control: its own connection reads" {
        val c = config(*machine("one").toTypedArray(), "hardware.machine.one.connection.api" to "moonraker", "hardware.machine.one.connection.host" to "one.test",
            "hardware.machine.two.like" to "one", "hardware.machine.two.connection.api" to "moonraker", "hardware.machine.two.connection.host" to "two.test",
            "hardware.machine.three.like" to "one")
        c.entry(listOf("hardware", "machine", "two", "build", "x"))?.value shouldBe "200 mm"
        Printers.connection(c, "two", env::get).host shouldBe "two.test"
        c.entry(listOf("hardware", "machine", "three", "connection", "host")) shouldBe null
        shouldThrow<IllegalStateException> { Printers.connection(c, "three", env::get) }.message!! shouldContain "machine 'three' has no connection"
    }

    "the protocols' required credentials are what the factory demands" {
        Protocol.FLASHFORGE_HTTP.required shouldContainExactly listOf("serial_number", "check_code")
        Protocol.MOONRAKER.required shouldContainExactly emptyList()
        Protocol.MOONRAKER.optional shouldContainExactly listOf("api_key", "ssh_password", "ssh_key_file")
    }
})
