package reikai.data

import app.cash.sqldelight.ColumnAdapter

/**
 * A list of free text stored one item per line. Titles can hold ", ", which the genre adapter
 * (StringListColumnAdapter) splits on; a title never holds a line break, since the editor takes one per line.
 */
object LineListColumnAdapter : ColumnAdapter<List<String>, String> {
    override fun decode(databaseValue: String) = if (databaseValue.isEmpty()) emptyList() else databaseValue.split("\n")
    override fun encode(value: List<String>) = value.joinToString("\n")
}
