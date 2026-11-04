package plymeld.printer

/**
 * One way to talk to any printer. A caller (the CLI's `printer`
 * subcommands; the UI's buttons, which run them) holds a Printer and never knows which adapter it has: it asks [can]
 * and calls the method; a method of a capability the adapter lacks is refused with one plain line ([refuse]). Each
 * adapter is one protocol family over one transport, and says what it knows and never what it guesses
 * ([PrinterState]'s fields are [Reported]).
 *
 * A printer is its machine [key] (`hardware.machine.<key>`, or another unique label the user gives it), never its
 * model: two machines of one model are two keys with two connections and two states.
 */
interface Printer {
    /** The machine key: how the printer is addressed, listed, reported, and refused. */
    val key: String
    val protocol: Protocol
    /** What this adapter can do with this printer. */
    val capabilities: Set<Capability>

    fun can(c: Capability): Boolean = c in capabilities

    fun state(): PrinterState = refuse(Capability.STATE)
    fun loadout(): ReportedLoadout = refuse(Capability.LOADOUT)
    fun files(): List<PrinterFile> = refuse(Capability.FILES)
    /** Upload [bytes] as [name]; the outcome says whether the printer's own list confirms it. */
    fun upload(name: String, bytes: ByteArray): Upload = refuse(Capability.UPLOAD)
    /**
     * Start printing the file [name] already on the printer; [mapping] says which slot prints each tool the file uses,
     * where the API takes that (an API that does not ignores it and says so in [Done.sent]); [level]: ask the printer to
     * level the bed first, refused ([Capability.LEVEL_ON_START]) where the API has no such request.
     */
    fun start(name: String, mapping: ToolMapping? = null, level: Boolean = false): Done = refuse(Capability.START)
    fun pause(): Done = refuse(Capability.PAUSE)
    fun resume(): Done = refuse(Capability.RESUME)
    fun cancel(): Done = refuse(Capability.CANCEL)
    /**
     * One JPEG from the printer's camera: the [frame]-th (1-based) of a stream, or the adapter's own choice when null
     * (an adapter whose stream hands out a stale first frame skips it); a snapshot URL gives its one image whatever
     * [frame] says.
     */
    fun cameraFrame(frame: Int? = null): CameraFrame = refuse(Capability.CAMERA)
    /** One g-code line or macro, sent as is; the printer's answer as text. */
    fun rawCommand(command: String): String = refuse(Capability.RAW_COMMAND)
}

/** The one-line refusal of a capability an adapter lacks: the printer's key and the protocol, never a credential. */
fun Printer.refuse(c: Capability): Nothing = error("printer '$key' cannot ${c.words}: ${protocol.id} has no way plymeld knows")
