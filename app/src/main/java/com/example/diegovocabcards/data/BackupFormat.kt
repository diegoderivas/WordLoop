package com.example.diegovocabcards.data

import com.example.diegovocabcards.review.*
import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.State
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Version 2 omits shared categories. Version 1 remains readable. Integers and IEEE doubles use DataOutput's big-endian encoding. */
internal object BackupFormat {
    private const val MAGIC = "DiegoVocabCards"
    private const val VERSION = 2
    private const val MAX_BYTES = 64 * 1024 * 1024

    private fun DataOutputStream.string(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    // Only used on the bounded, verified in-memory payload.
    private fun DataInputStream.string(): String {
        val size = readInt()
        require(size in 0..available())
        val bytes = ByteArray(size).also(::readFully)
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun <T> DataInputStream.rows(row: () -> T): List<T> {
        val count = readInt()
        require(count in 0..minOf(available() / 4, 1_000_000))
        return List(count) { row() }
    }

    fun write(backup: LibraryBackup, target: OutputStream) {
        backup.validate()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data -> with(data) {
            val settings = backup.settings
            string(settings.theme)
            writeBoolean(settings.automaticSpeech)
            string(settings.voice)
            with(settings.review) {
                writeInt(retention)
                writeInt(firstLearningMinutes)
                writeInt(nextLearningMinutes)
                writeInt(relearningMinutes)
            }
            writeInt(backup.entries.size)
            for (entry in backup.entries) {
                writeLong(entry.entryId)
                string(entry.english)
            }
            writeInt(backup.senses.size)
            for (sense in backup.senses) with(sense) {
                string(id)
                writeLong(entryId)
                listOf(spanish, example, definition, synonyms, senseCategories, source, usageNote,
                    generatedExamples, ipa, ipaSource).forEach { string(it) }
            }
            writeInt(backup.tags.size)
            for (tag in backup.tags) {
                string(tag.cardId)
                string(tag.label)
            }
            writeInt(backup.history.size)
            for (event in backup.history) with(event) {
                string(senseId)
                string(direction.name)
                writeInt(turn)
                string(rating.name)
                string(memory.phase.name)
                writeInt(memory.step ?: -1)
                writeDouble(memory.stability)
                writeDouble(memory.difficulty)
                writeLong(memory.dueAt)
                writeLong(memory.reviewedAt)
            }
        } }
        require(bytes.size() <= MAX_BYTES) { "La copia supera los 64 MiB." }
        val payload = bytes.toByteArray()
        DataOutputStream(target).apply {
            writeUTF(MAGIC)
            writeInt(VERSION)
            writeInt(payload.size)
            write(MessageDigest.getInstance("SHA-256").digest(payload))
            write(payload)
            flush()
        }
    }

    fun read(source: InputStream): LibraryBackup {
        val input = DataInputStream(source.buffered())
        require(input.readUTF() == MAGIC) { "El archivo no es una copia de DiegoVocabCards." }
        val version = input.readInt()
        require(version in 1..VERSION) { "La versión de esta copia es incompatible." }
        val size = input.readInt()
        require(size in 1..MAX_BYTES)
        val checksum = ByteArray(32).also(input::readFully)
        val payload = ByteArray(size).also(input::readFully)
        require(input.read() == -1 && MessageDigest.isEqual(checksum, MessageDigest.getInstance("SHA-256").digest(payload)))
        return DataInputStream(ByteArrayInputStream(payload)).run {
            val settings = LibrarySettings(theme = string(), automaticSpeech = readBoolean(), voice = string(),
                review = ReviewSettings(readInt(), readInt(), readInt(), readInt()))
            val entries = rows {
                val entry = Entry(readLong(), string())
                if (version == 1) string() // Consume and discard the retired category.
                entry
            }
            val senses = rows {
                Sense(id = string(), entryId = readLong(), spanish = string(), example = string(),
                    definition = string(), synonyms = string(), senseCategories = string(), source = string(),
                    usageNote = string(), generatedExamples = string(), ipa = string(), ipaSource = string())
            }
            val tags = rows { CardTag(string(), string()) }
            val events = rows {
                val id = string()
                val direction = Direction.valueOf(string())
                val turn = readInt()
                val rating = Rating.valueOf(string())
                val memory = Memory(State.valueOf(string()), readInt().takeUnless { it == -1 },
                    readDouble(), readDouble(), readLong(), readLong())
                ReviewEvent(id, direction, turn, rating, memory)
            }
            require(available() == 0)
            LibraryBackup(entries, senses, tags, events, settings).also { it.validate() }
        }
    }
}
