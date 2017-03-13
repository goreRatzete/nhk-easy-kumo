import PropertyReader.getProperty
import data.Article
import data.Headline
import storage.Mongo
import java.io.File

object FileArchive {

    val fileDir = getProperty("dir")

    fun archive(articles: List<Article>) {
        articles.forEach(Article::makeFiles)
        Mongo.saveArticles(articles)
    }

    fun read(): List<Headline> = getFolders().flatMap {
        val date = it.name
        it.listFiles().map {
            val path = it.absolutePath
            val file = File("$path/content.txt")
            val content = file.readLines()
            Headline(id = it.name, title = content[0], url = content[1], date = date)
        }
    }

    fun getFolders(): Array<out File> = File("$fileDir/articles").listFiles()

}

fun Article.makeFiles() {
    println("$date - $id - $title")
    imageFile.writeIfNotExists(image)
    audioFile.writeIfNotExists(audio)
    htmlFile.writeIfNotExists(this.toHtml())
}


fun Article.toHtml() = Application::class.java.classLoader.getResource("template.html")
        .readText()
        .replace("{title}", title)
        .replace("{articleUrl}", url)
        .replace("{audioUrl}", audioUrl)
        .replace("{audioLength}", audioFile.getDuration().toString())
        .replace("{body}", content)

