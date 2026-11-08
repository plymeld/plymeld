package plymeld.printer

/**
 * What a printer can be asked to do through [Printer]. An adapter declares the
 * ones it has for its protocol; a caller asks [Printer.can] before offering a button, and a method of a capability the
 * adapter lacks is refused with one line built from [words] (the UI grays the button with the same sentence).
 */
enum class Capability(val words: String) {
    STATE("read its state"),
    LOADOUT("read what it has loaded"),
    FILES("list its files"),
    UPLOAD("upload a file"),
    START("start a print"),
    /** Asked as part of the start request (Flashforge's `levelingBeforePrint`); where levelling is g-code, the g-code does it. */
    LEVEL_ON_START("level the bed as a print starts"),
    PAUSE("pause the print"),
    RESUME("resume the print"),
    CANCEL("cancel the print"),
    /**
     * Tell the printer its bed is clear, the last print removed (Flashforge's "completed" screen, which holds back the next
     * upload until acknowledged). Only someone who has seen the bed can say so: never done on its own.
     */
    CLEAR("be told its bed is clear"),
    CAMERA("read a camera frame"),
    RAW_COMMAND("send a raw command"),
}

/**
 * A protocol family an adapter speaks: its [id] is the value of
 * `hardware.machine.<key>.connection.api`; [defaultPort] the API's own port when the config sets none; [required] and
 * [optional] the credential names the config maps to environment variables (`connection.credential.<name>.env`).
 * Adding a family: an entry here, an adapter class, a branch in [Printers.open]; the schema's choice list for
 * `connection.api` is held equal to these ids by a test.
 */
enum class Protocol(val id: String, val defaultPort: Int, val required: List<String>, val optional: List<String>, val words: String) {
    /** Flashforge's LAN HTTP API (the Creator 5; the Adventurer 5M / 5X family per the community endpoint map, not tried). */
    FLASHFORGE_HTTP("flashforge-http", 8898, listOf("serial_number", "check_code"), emptyList(), "Flashforge's LAN HTTP API"),
    /**
     * Moonraker's HTTP API on a Klipper machine (the QIDI Q2; most Klipper printers). `ssh_password` and `ssh_key_file`
     * (the path of a private key) unlock the printer's shell for what Moonraker does not report (the Box's slots).
     */
    MOONRAKER("moonraker", 7125, emptyList(), listOf("api_key", "ssh_password", "ssh_key_file"), "Moonraker's HTTP API (Klipper)");

    companion object {
        val ids: List<String> get() = entries.map { it.id }
        fun byId(id: String): Protocol? = entries.firstOrNull { it.id == id }
    }
}
