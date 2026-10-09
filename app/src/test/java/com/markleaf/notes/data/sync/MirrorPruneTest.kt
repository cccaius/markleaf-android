package com.markleaf.notes.data.sync

import com.markleaf.notes.domain.model.Note
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Pure-logic checks of [NoteFolderMirror.missingMirrorNoteIds] — which notes the
 * prune pass is allowed to delete when the linked folder no longer holds their
 * file. These drive the real production function (no reimplementation) and
 * deliberately do not exercise the SAF IO, which stays covered by the live
 * tablet smoke — the same split [NoteFolderMirrorConflictLogicTest] uses.
 */
class MirrorPruneTest {

    /** A note with the given sync stamp; present in the folder unless flagged. */
    private fun note(
        id: String = "n1",
        title: String = "Note",
        lastImportMs: Long? = 1_000,
        trashed: Boolean = false,
        archived: Boolean = false,
        locked: Boolean = false,
        conflictCopy: Boolean = false
    ) = Note(
        id = id,
        title = title,
        contentMarkdown = title,
        excerpt = title,
        createdAt = Instant.ofEpochMilli(0),
        updatedAt = Instant.ofEpochMilli(1_000),
        lastImportedAt = lastImportMs?.let { Instant.ofEpochMilli(it) },
        trashed = trashed,
        archived = archived,
        locked = locked,
        isConflictCopy = conflictCopy
    )

    private fun missing(
        notes: List<Note>,
        presentIds: Set<String> = emptySet(),
        presentBases: Set<String> = emptySet()
    ) = NoteFolderMirror.missingMirrorNoteIds(notes, presentIds, presentBases)

    // --- the loop this exists for ------------------------------------------

    @Test
    fun exportedNoteWhoseFileIsGone_isDeleted() {
        assertEquals(listOf("n1"), missing(listOf(note())))
    }

    @Test
    fun emptyFolder_deletesEveryExportedNote() {
        // The folder really is the source of truth: moving every file out is a
        // legitimate way to empty the library.
        assertEquals(
            listOf("a", "b"),
            missing(listOf(note(id = "a"), note(id = "b")))
        )
    }

    // --- eligibility: a note the folder never held is not a deletion --------

    @Test
    fun noteNeverExported_isKept() {
        // No `lastImportedAt` means Markleaf never wrote this note to the folder,
        // so "no file for it" says nothing at all.
        assertEquals(emptyList<String>(), missing(listOf(note(lastImportMs = null))))
    }

    @Test
    fun trashedNote_isKept() {
        assertEquals(emptyList<String>(), missing(listOf(note(trashed = true))))
    }

    @Test
    fun archivedNote_isKept() {
        assertEquals(emptyList<String>(), missing(listOf(note(archived = true))))
    }

    @Test
    fun lockedNote_isKept() {
        // The Locked space is meant to stay on the device (#155).
        assertEquals(emptyList<String>(), missing(listOf(note(locked = true))))
    }

    @Test
    fun conflictCopy_isKept() {
        // Conflict copies are kept for a hand merge, not taken away by the folder.
        assertEquals(emptyList<String>(), missing(listOf(note(conflictCopy = true))))
    }

    // --- the two ways a file claims its note --------------------------------

    @Test
    fun fileCarryingTheMarkleafId_isKept() {
        assertEquals(emptyList<String>(), missing(listOf(note()), presentIds = setOf("n1")))
    }

    @Test
    fun adoptedFileMatchingTheTitle_isKept() {
        // The id was stripped by another app (#213): the filename is the only
        // link left, and honouring it keeps the note alive — the harmless
        // direction to be wrong in.
        assertEquals(
            emptyList<String>(),
            missing(listOf(note(title = "My Note")), presentBases = setOf("my note"))
        )
    }

    @Test
    fun titleIsSanitisedBeforeTheNameComparison() {
        // A "/" cannot be in a filename: the mirror writes "Q1 Q2.md", so that —
        // not "q1/q2" — is what has to match.
        assertEquals(
            emptyList<String>(),
            missing(listOf(note(title = "Q1/Q2")), presentBases = setOf("q1 q2"))
        )
    }

    @Test
    fun anotherNotesFile_doesNotProtectThisOne() {
        assertEquals(
            listOf("n1"),
            missing(
                listOf(note(id = "n1", title = "Mine")),
                presentIds = setOf("other"),
                presentBases = setOf("someone else")
            )
        )
    }

    // --- a mixed library ----------------------------------------------------

    @Test
    fun onlyTheNotesWhoseFilesAreGone_comeBack() {
        val notes = listOf(
            note(id = "gone", title = "Gone"),
            note(id = "kept-by-id", title = "Kept by id"),
            note(id = "kept-by-name", title = "Kept by name"),
            note(id = "never-exported", title = "Never exported", lastImportMs = null)
        )
        assertEquals(
            listOf("gone"),
            missing(
                notes,
                presentIds = setOf("kept-by-id"),
                presentBases = setOf("kept by name")
            )
        )
    }
}
