package plymeld.machine

import java.util.Locale

/**
 * The machine-specific conventions of a Klipper g-code file, as each printer's own slicer
 * writes them (observed in their output). The printer firmware ignores comments;
 * matching them keeps plymeld's files readable by the same tools and previewers. Each bundled machine model names its
 * default ([MachineDescription.dialect]); a machine may override it (hardware.machine.<key>.firmware.dialect).
 *
 *   qidi:  `; CHANGE_LAYER` / `; Z_HEIGHT:` / `; FEATURE:`, accel `M204 S`
 *   flashforge: `;LAYER_CHANGE` / `;Z:` / `;TYPE:`, accel `SET_VELOCITY_LIMIT ACCEL=`
 *   klipper: a printer with no maker's conventions: the flashforge comments (the form most previewers read) without its
 *            file header, accel `SET_VELOCITY_LIMIT ACCEL=` (Klipper's own command; plymeld's choice of the two)
 */
enum class Dialect {
    QIDI {
        override fun layerChange(z: String, h: String) = "; CHANGE_LAYER\n; Z_HEIGHT: $z\n; LAYER_HEIGHT: $h\n"
        override fun feature(name: String) = "; FEATURE: $name\n"
        override fun width(w: String) = "; LINE_WIDTH: $w\n"
        override fun accel(a: Long) = "M204 S$a\n"
    },
    FLASHFORGE {
        override fun layerChange(z: String, h: String) = ";LAYER_CHANGE\n;Z:$z\n;HEIGHT:$h\n"
        override fun feature(name: String) = ";TYPE:$name\n"
        override fun width(w: String) = ";WIDTH:$w\n"
        override fun accel(a: Long) = "SET_VELOCITY_LIMIT ACCEL=$a\n"
    },
    KLIPPER {
        override fun layerChange(z: String, h: String) = FLASHFORGE.layerChange(z, h)
        override fun feature(name: String) = FLASHFORGE.feature(name)
        override fun width(w: String) = FLASHFORGE.width(w)
        override fun accel(a: Long) = FLASHFORGE.accel(a)
    };

    abstract fun layerChange(z: String, h: String): String
    abstract fun feature(name: String): String
    abstract fun width(w: String): String
    abstract fun accel(a: Long): String

    /** The name a config and a machine description use for it. */
    val id: String get() = name.lowercase(Locale.ROOT)

    companion object {
        /** By name ([id]); an unknown name is refused, naming the known ones. */
        fun named(name: String): Dialect = entries.firstOrNull { it.id == name.trim().lowercase(Locale.ROOT) }
            ?: error("no g-code dialect '$name' (plymeld knows: ${entries.joinToString { it.id }})")

        /**
         * The dialect a machine's files are written in: [override] (hardware.machine.<key>.firmware.dialect) when set, else
         * its [model]'s default (hardware.model.<key>.dialect).
         */
        fun forMachine(model: MachineDescription, override: String?): Dialect =
            named(override?.takeIf { it.isNotBlank() } ?: model.dialect)
    }
}
