package jlpt

import data.Lesson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import pages.Page
import util.getLogger
import util.loadResource
import java.net.URLDecoder

/**
 * Created by Av on 4/22/2017.
 */
class Jt4y(val fromFile: Boolean = false) : Page<List<Lesson>> {

    override val logger = this::class.getLogger()

    override val name = "JLPT.html"

    override val url = "http://japanesetest4you.com/jlpt-n2-grammar-list/"

    val local = this::class.loadResource("JLPTN2GrammarList–Japanesetest4you.html")

    override fun get(): List<Lesson> = (if (fromFile) Jsoup.parse(local) else Jsoup.connect(url).get())
            .getElementsByClass("entry").first()
            .getElementsByTag("a")
            .dropLast(1)
            .map { it.attr("href") }
            .map {
                val keyword = if (it.contains("grammar-%")) "grammar-" else "grammar-n2-"
                val mid = it.substringAfter(keyword).substringBefore("-")
                val start = it.substringBefore(mid)
                val end = it.substringAfter(mid)
                start + URLDecoder.decode(mid, "UTF-8") + end
            }
            .onEach { println(it) }
            .map { extractLesson(it) }


    fun extractLesson(lessonUrl: String) = Jsoup.connect(lessonUrl).get().let { doc ->
        val title = doc.getElementsByClass("title").first().text()
                .filterNot(Char::encodeable)
        val content = extractContent(doc)

        Lesson(
                language = "ja",
                title = title,
                text = content,
                collection = 274307,
                tags = listOf("JLPT", "Grammar"),
                url = lessonUrl,
                external_audio = "http://www.freesfx.co.uk/rx2/mp3s/6/18660_1464810669.mp3",
                duration = 1
        )
    }

    fun extractContent(document: Document) = document
            .getElementsByClass("entry").first()
            .getElementsByTag("p")
            .map(Element::text)
            .filter(String::isNotBlank) // filter out empty lines
            .joinToString("|")
            .substringAfter("Example sentences:")
            .split("|")
            .map(String::removeIllegalChars) // replace problematic chars
            .filterNot { it.take(5).all(Char::encodeable) } // filter out lines that can be encoded with ANSI
            //.drop(1) // drop the first line (form)
            .map(String::removeNonJap) // remove non Japanese parts from the remaining lines
            .map(String::fixEndOfSentence)
            .joinToString("")


}