package com.markleaf.notes.data.sync

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.markleaf.notes.domain.model.Note

/**
 * The folder → **deletion** direction of the mirror.
 *
 * The other two directions are deliberately conservative: [MirrorWrite] only
 * ever overwrites a file it wrote itself, and [MirrorImport] "only ever *reads*
 * the folder into notes — it never deletes one", because upstream Markleaf is
 * the source of truth for its own notes and a missing file is not evidence of
 * anything (`MirrorImport.staleEntryIds` says why: a sync client that has not
 * delivered yet, or a listing that came back short, both look like a deletion).
 *
 * This object is the opposite trade, and it is opt-in per call site: when the
 * linked folder **is** the source of truth, a note Markleaf put there and that
 * the folder no longer holds is a note the user has moved on — they dragged the
 * file into another folder, renamed it away, or deleted it on another device —
 * and leaving the row behind means the list slowly fills with copies of files
 * that are gone. This is the "paste → file → move the file away → the note
 * disappears" loop, closed at the deletion end.
 *
 * ## Who is eligible, and why the bar is that high
 *
 * A note is a candidate only when **all** of the following hold:
 *
 * - It carries `lastImportedAt != null`. That stamp is written by every mirror
 *   write ([NoteFolderMirror.writeNoteAndStamp]) and by the editor's auto-save
 *   once a mirrored write is confirmed, so it means "Markleaf has put this note
 *   in the sync folder at least once". A note that was never exported has no
 *   file by construction and must never be mistaken for one whose file is gone.
 * - It is not in Trash, not Archived, and not Locked — a hidden note is one the
 *   user is not looking at, and the Locked space is meant to stay on the device
 *   ([MirrorImport] skips both for the same reason).
 * - It is not a conflict copy: those are kept for a hand merge, not for the
 *   folder to take away.
 * - No file in the folder claims it: neither by `markleaf_id` nor by its
 *   title-derived filename. The filename half is the safety net for a file
 *   whose id was stripped by another app (#213) — it keeps the note alive, which
 *   is the harmless direction to be wrong in.
 *
 * ## Guard: two listings must agree
 *
 * The one failure that would be catastrophic rather than annoying is deleting a
 * whole library because the folder looked empty when it was not. Neither
 * `DocumentFile` implementation reports that: `TreeDocumentFile.listFiles`
 * swallows the provider exception and `RawDocumentFile.listFiles` returns empty
 * when `File.listFiles()` gives null, so a failed listing is indistinguishable
 * from an empty folder ([MirrorTraversal.walk] documents this at length). So the
 * folder is listed [CONFIRMATION_READS] times and the pass does nothing at all
 * unless every read agrees. That cannot turn a *persistently* failing provider
 * into a safe one — nothing available here can — but it does reject the
 * transient, first-call-empty shape, and it is free next to the listing it
 * already costs.
 *
 * A folder that genuinely holds nothing is a legitimate state and does delete
 * every exported note; that is the source-of-truth contract, not a bug.
 */
internal object MirrorPrune {

    /**
     * How many independent listings of the folder have to agree before a file's
     * absence is believed. See the guard note on this object.
     */
    internal const val CONFIRMATION_READS = 2

    /**
     * The ids of the notes the folder no longer holds, given what a listing of
     * it saw.
     *
     * Pure, so the eligibility rules above are unit-testable without Android —
     * the same split [MirrorReconcile] and [MirrorFileNames] use.
     *
     * @param notes the complete note set (active, archived and trashed), the
     *   same set [MirrorImport.importChangesFrom] requires.
     * @param presentNoteIds the `markleaf_id` of every file the folder holds.
     * @param presentFileBases every file's name with its extension stripped and
     *   folded to lower case — compared against [MirrorFileNames.sanitizeBase].
     */
    internal fun missingNoteIds(
        notes: List<Note>,
        presentNoteIds: Set<String>,
        presentFileBases: Set<String>
    ): List<String> = notes.filter { note ->
        note.lastImportedAt != null &&
            !note.trashed &&
            !note.archived &&
            !note.locked &&
            !note.isConflictCopy &&
            note.id !in presentNoteIds &&
            MirrorFileNames.sanitizeBase(note.title).lowercase() !in presentFileBases
    }.map { it.id }

    /**
     * Delete every note the folder no longer holds, through [applyDelete].
     *
     * Returns how many notes were handed to [applyDelete]. Zero is the safe
     * answer and the one every guard above produces: an unreadable folder, a
     * listing that did not repeat, or simply a folder that still holds every
     * exported note.
     *
     * [maxDepth] is the same parameter [MirrorImport.importChanges] takes and
     * must match it — the pass reads the folder the same way the import does, so
     * a file inside a subdirectory that the import cannot see is not one this
     * can see either. Both settle depth through [MirrorTraversal.effectiveDepth].
     */
    internal suspend fun pruneMissing(
        context: Context,
        folder: DocumentFile,
        notes: List<Note>,
        metadata: MirrorMetadata = MirrorMetadata.Frontmatter,
        maxDepth: Int = 0,
        applyDelete: suspend (String) -> Unit
    ): Int {
        // An unreadable folder is not an empty one, and this is the cheapest
        // place to say so.
        if (!folder.canRead()) return 0

        val reads = (1..CONFIRMATION_READS).map {
            readPresence(context, folder, metadata, maxDepth)
        }
        val seen = reads.firstOrNull() ?: return 0
        if (reads.any { it != seen }) return 0

        var deleted = 0
        for (id in missingNoteIds(notes, seen.noteIds, seen.fileBases)) {
            runCatching { applyDelete(id) }.onSuccess { deleted++ }
        }
        return deleted
    }

    /** What one complete listing of the folder found. */
    private data class FolderPresence(
        val noteIds: Set<String>,
        val fileBases: Set<String>
    )

    /**
     * One listing of the folder, reduced to the two facts [missingNoteIds]
     * needs: which notes the files claim, and what they are named.
     *
     * Data-class equality is what the two-read guard compares, so the sets must
     * be content-equal rather than the same instance; both are built fresh each
     * read for exactly that reason.
     */
    private fun readPresence(
        context: Context,
        folder: DocumentFile,
        metadata: MirrorMetadata,
        maxDepth: Int
    ): FolderPresence {
        val walk = MirrorTraversal.walk(folder, MirrorTraversal.effectiveDepth(metadata, maxDepth))
        val files = walk.files
        val bases = files.mapTo(HashSet(files.size)) {
            it.name.substringBeforeLast('.').lowercase()
        }

        val ids = HashSet<String>()
        when (metadata) {
            MirrorMetadata.Frontmatter -> files.forEach { ref ->
                MirrorFileLookup.peekMarkleafId(context, ref.file.uri)?.let(ids::add)
            }
            is MirrorMetadata.Sidecar -> {
                // Sidecar mode has no header to read: the id lives in the index,
                // and an index row whose file is present is the same claim as a
                // `markleaf_id` in the frontmatter mode above.
                val merged = SidecarStore.load(context, folder, metadata.deviceId)
                for (entry in merged.values) {
                    if (entry.fileName.substringBeforeLast('.').lowercase() in bases) {
                        ids.add(entry.noteId)
                    }
                }
            }
        }
        return FolderPresence(ids, bases)
    }
}
