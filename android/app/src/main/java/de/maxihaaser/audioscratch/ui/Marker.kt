package de.maxihaaser.audioscratch.ui

/**
 * A named position in the loaded clip, drawn as a flag on [LoopMarkerBar].
 *
 * [id] is stable across edits and re-sorts, so the UI can address a marker
 * (rename / delete / jump) without holding on to a list index that an insert
 * before it would invalidate. [frame] is an absolute frame offset into the clip;
 * [label] is empty by default and only drawn when set.
 */
data class Marker(val id: Long, val frame: Int, val label: String)
