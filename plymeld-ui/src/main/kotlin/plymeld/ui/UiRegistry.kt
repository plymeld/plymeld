package plymeld.ui

/**
 * Where each command of the command model is in the UI (everything the CLI has, the UI has; the parity
 * test, UiParityTest, walks `plymeld commands` against this). A command is in exactly one of two places:
 *
 * - [surfaces]: the UI surface that offers it, by the command's line (`plymeld plan`), with the options that have a
 *   designed control; an option not named here is offered in the command's form, built from the model (the finder
 *   finds every command; a hit opens its form in the drawer: every option a field, a toggle, or chips, Run).
 * - [notYet]: not in the UI yet, each with the step of the UI plan that brings it. The
 *   test fails when a command is in neither, so a command added to the CLI is a decision here, never a drift.
 *
 * Every command has the form at least, but one that reads a secret value in place of a command-line argument
 * (`printer credential`), which the form has no field for yet; a designed place is listed where one exists.
 */
object UiRegistry {
    class Surface(val command: String, val where: String, val controls: Set<String> = emptySet())

    private const val FORM = "the finder: the command's form in the drawer (every option a field), Run into the console"

    val surfaces: List<Surface> = listOf(
        Surface("plymeld", "the finder: the root's help; its subcommands each their own"),
        Surface("plymeld plan", "the window: steps 1 to 4 (the job opened as `plan` reads it, step 1 offering every part from one slot where a filament is not loaded; the plan's sentences and Why); $FORM", setOf("-cf", "-m", "--machine", "--slot")),
        Surface("plymeld slice", "the window: step 5 (Slice & check), the console, the stage's Layers tab; $FORM", setOf("-cf", "-m", "--machine", "--slot", "-o")),
        Surface("plymeld printer send", "the window: step 6 (Send to the <key>): the sliced file uploaded, its refusal in the step; $FORM", setOf("-cf", "--machine")),
        Surface("plymeld config", "the finder: every setting of the job's config by name, value, or meaning; a hit opens the drawer on its chain (the value in force, its source, what it overrode)", setOf("-cf", "--machine")),
        Surface("plymeld commands", "the finder: every command by its line or help; a hit opens its form"),
        Surface("plymeld init", "the window: step 2's Set up a printer (shown with no config file open): the same questions as a form in the drawer, checked and written the same way (FirstUse)"),
        Surface("plymeld edit", "the drawer: lock a band's height, keep or release a spot, decide a technique or a spot again, set a setting's value; each recorded in the job's journal; $FORM", setOf("--keep", "--unset", "--unset-under", "--journal")),
        Surface("plymeld journal", "the bar and the tray's History: undo and redo over the job's journal beside its plan file; $FORM", setOf("--undo", "--redo")),
        Surface("plymeld printer status", "the window: step 2 (Ask the printer: the state's lines); $FORM", setOf("-cf", "--machine")),
        Surface("plymeld printer loadout", "the window: step 2 (Ask the printer: the loadout rows, disagreements in red); $FORM", setOf("-cf", "--machine")),
        Surface("plymeld printer camera", "the window: step 2 (Ask the printer: the frame); $FORM", setOf("-cf", "--machine", "-o")),
        Surface("plymeld schema", FORM),
        Surface("plymeld layers", FORM),
        Surface("plymeld calibrate", "the window's Calibrate… button and the finder: the group's help (the plates, in the order to run them); its plates and their readings each their own form"),
        Surface("plymeld calibrate melt", FORM),
        Surface("plymeld calibrate melt values", FORM),
        Surface("plymeld calibrate flow", FORM),
        Surface("plymeld calibrate flow values", FORM),
        Surface("plymeld calibrate first-layer", FORM),
        Surface("plymeld calibrate first-layer values", FORM),
        Surface("plymeld calibrate motion", FORM),
        Surface("plymeld calibrate motion readings", FORM),
        Surface("plymeld calibrate motion values", FORM),
        Surface("plymeld calibrate filament", FORM),
        Surface("plymeld calibrate ironing", FORM),
        Surface("plymeld calibrate ironing readings", FORM),
        Surface("plymeld calibrate ironing values", FORM),
        Surface("plymeld calibrate seams", FORM),
        Surface("plymeld calibrate seams readings", FORM),
        Surface("plymeld calibrate seams values", FORM),
        Surface("plymeld render-plate", FORM),
        Surface("plymeld printer", "the finder: its subcommands each their own form"),
        Surface("plymeld printer watch", FORM),
        Surface("plymeld printer files", FORM),
        Surface("plymeld printer pause", FORM),
        Surface("plymeld report", FORM),
        Surface("plymeld updates", "the window: the bar's settings (⚙), UPDATES: off, notify or install, the same setting; the window checks as it opens, its bar saying a newer release; $FORM", setOf("--set")),
        Surface("plymeld printer clear", "the window: step 6, where a send is refused because the printer still shows its last print finished (\"The bed is clear: tell the <key>\", then Send again); $FORM", setOf("-cf", "--machine")),
        Surface("plymeld printer resume", FORM),
        Surface("plymeld printer cancel", FORM),
        Surface("plymeld investigate", "the finder: its subcommands each their own form"),
        Surface("plymeld investigate slice", FORM),
        Surface("plymeld investigate plan", FORM),
        Surface("plymeld investigate check", FORM),
        Surface("plymeld investigate config", FORM),
        Surface("plymeld investigate predict", FORM),
    )

    val notYet: Map<String, String> = mapOf(
        "plymeld printer credential" to "a hidden field in its form that hands the value to the command as its standard input, never on its line; " +
            "until then the command line, and the setup's form (plymeld init) writes the credentials it asks for")
}
