package me.avo.kumo.nhk.processing.audio

import com.iheartradio.m3u8.Encoding
import com.iheartradio.m3u8.Format
import com.iheartradio.m3u8.PlaylistParser
import com.iheartradio.m3u8.data.*
import me.avo.kumo.util.getAudioInputStream
import me.avo.kumo.util.getLogger
import me.avo.kumo.util.joinAudioStreams
import me.avo.kumo.util.writeAudio
import org.jcodec.api.transcode.SinkImpl
import org.jcodec.api.transcode.SourceImpl
import org.jcodec.api.transcode.Transcoder
import org.jcodec.common.Codec
import org.jcodec.common.JCodecUtil
import org.jcodec.common.TrackType
import sun.plugin.dom.exception.InvalidStateException
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sound.sampled.AudioFileFormat

class AudioParser(private val workingDir: File, private val destinationDir: File, ffmpegPath: String) {

    /**
     * Parses the audio for the given [id] and returns a single wav [File]
     */
    fun run(id: String): File = logger.info("Parsing audio for article $id")
        .let { getAudioUrl(id) }
        .let(::getPlaylist)
        .masterPlaylist
        .let(::handleMasterList)
        .mediaPlaylist
        .let(::handleMediaList)
        //.onEach(File::deleteOnExit)
        .let(::demuxSegments)
        .onEach(File::deleteOnExit)
        .let(::mergeAudio)
        .let(::convertWavToMp3)

    private fun getAudioUrl(id: String) = "https://nhks-vh.akamaihd.net/i/news/easy/$id.mp4/master.m3u8"

    fun mergeAudio(files: Collection<File>): File = files
        .map(File::getAudioInputStream)
        .reduce { one, two -> joinAudioStreams(one, two) }
        .let { writeAudio(it, AudioFileFormat.Type.WAVE, File(destinationDir, "audio.wav")) }
        .also(File::deleteOnExit)

    private fun getPlaylist(audioUrl: String): Playlist = URL(audioUrl)
        .openStream()
        .use { PlaylistParser(it, Format.EXT_M3U, Encoding.UTF_8).parse() }

    private fun handleMasterList(playlist: MasterPlaylist) = playlist
        .playlists
        .firstOrNull()
        ?.uri
        ?.let(this::getPlaylist) ?: throw InvalidStateException("Master playlist does not contain any playlists")

    private fun handleMediaList(playlist: MediaPlaylist): Collection<File> = playlist.tracks.let { tracks ->
        logger.trace("Found ${tracks.size} tracks")
        val cipher = tracks.first().encryptionData.let(::getCipher)
        return downloadTracks(tracks, cipher)
    }

    private fun downloadTracks(tracks: List<TrackData>, cipher: Cipher): Collection<File> = tracks
        .associate(this::getSegment)
        .mapValues { (_, bytes) -> cipher.doFinal(bytes) }
        .mapKeys { (name, _) -> File(workingDir, name) }
        .onEach { (file, bytes) -> file.writeBytes(bytes) }
        .keys

    fun getSegment(track: TrackData): Pair<String, ByteArray> {
        val filename = track.uri.substringAfter(".mp4/").substringBefore("?null=0&id=")
        val bytes = URL(track.uri).openStream().use { it.readBytes() }
        return filename to bytes
    }

    fun getCipher(data: EncryptionData): Cipher {
        val bytes = URL(data.uri).readBytes()
        val chainmode = "CBC"
        val method = when (data.method) {
            EncryptionMethod.AES -> "AES/$chainmode/NoPadding"
            else -> data.method.name
        }
        val keySpec = SecretKeySpec(bytes, data.method.name)
        logger.trace("Decrypting using method ${data.method} ($method)")
        return Cipher
            .getInstance(method)
            .apply { init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(ByteArray(16))) }
    }

    fun demuxSegments(files: Collection<File>): List<File> = files.map(::demuxFfmpeg)

    fun demuxFfmpeg(source: File): File {
        val target = getDestinationFile(source, destinationDir, "wav")
        val fullCommand = "$ffmpeg -i ${source.absolutePath} ${target.absolutePath}"
        logger.debug(fullCommand)
        executeCommand(fullCommand).let {
            //it.errorStream.bufferedReader().lines().forEach(::println)
            val result = it.waitFor(10, TimeUnit.SECONDS)
            if (!result) throw IllegalStateException("Process returned result $result, not 0")
        }
        return target
    }

    fun convertWavToMp3(file: File): File {
        val target = getDestinationFile(file, destinationDir, "mp3")
        executeCommand("$ffmpeg -i ${file.absolutePath} -acodec libmp3lame ${target.absolutePath}")
            .waitFor(10, TimeUnit.SECONDS)
        return target
    }

    private val ffmpeg = "$ffmpegPath/bin/ffmpeg.exe"

    private fun executeCommand(cmd: String) = Runtime.getRuntime().exec(cmd)

    private fun getDestinationFile(source: File, destinationDir: File, extension: String) =
        File(destinationDir, source.nameWithoutExtension + ".$extension").also {
            if (it.exists()) {
                // TODO check if exists already
            }
        }

    private val logger = this::class.getLogger()

    fun demux(file: File, destination: File) = JCodecUtil.createM2TSDemuxer(file, TrackType.AUDIO).let { muxer ->
        muxer.v1.audioTracks
            .onEach(::println)
            .forEachIndexed { index, track ->
                Transcoder
                    .newTranscoder()
                    .addSource(
                        SourceImpl(
                            file.absolutePath,
                            org.jcodec.common.Format.WAV,
                            null,
                            CodecFinder.getCodec(muxer.v0, index)
                        )
                    )
                    .addSink(
                        SinkImpl(
                            File(destination, file.nameWithoutExtension + ".aac").absolutePath,
                            org.jcodec.common.Format.MOV, null, Codec.AAC
                        )
                    )
                    .create()
                    .transcode()
            }
    }

}