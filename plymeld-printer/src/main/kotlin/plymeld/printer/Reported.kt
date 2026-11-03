package plymeld.printer

/**
 * A figure a printer either gave or did not: [Value], the printer's own, or
 * [NotReported] with why (the API has no such field, the field was blank, the adapter does not ask for it). Nothing in
 * plymeld stands a default or a derived figure in for a missing one: a caller that needs a number asks [orNull] and
 * decides for itself; a report prints "not reported".
 */
sealed class Reported<out T> {
    data class Value<T>(val value: T) : Reported<T>()
    data class NotReported(val why: String) : Reported<Nothing>()

    val orNull: T? get() = (this as? Value)?.value

    companion object {
        /** [v] as reported, or not reported with [why] when it is null. */
        fun <T : Any> of(v: T?, why: String): Reported<T> = if (v == null) NotReported(why) else Value(v)
    }
}

/** The figure as text ([show] renders a value; a missing one reads "not reported"). */
fun <T> Reported<T>.text(show: (T) -> String = { it.toString() }): String = when (this) {
    is Reported.Value -> show(value)
    is Reported.NotReported -> "not reported"
}
