package dev.vox.audio.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class Pick(val title: String, val author: String, val blurb: String)
data class Collection(val id: String, val title: String, val subtitle: String, val picks: List<Pick>)
data class Shelf(val id: String, val title: String, val subtitle: String = "", val books: List<Book> = emptyList(), val loading: Boolean = true)
data class Featured(val book: Book, val blurb: String, val label: String)

/** Hand-picked public-domain classics, rotated daily so Home never looks the same two days in a row. */
object Discover {
    val collections = listOf(
        Collection("dark", "Edge of your seat", "Mysteries, ghosts and monsters that still hold up", listOf(
            Pick("The Hound of the Baskervilles", "Arthur Conan Doyle", "Holmes, a cursed family and something howling on the moor."),
            Pick("Dracula", "Bram Stoker", "The vampire novel that started it all, told through letters and diaries."),
            Pick("Frankenstein", "Mary Shelley", "A young scientist builds life and cannot bear what he made."),
            Pick("The Strange Case of Dr. Jekyll and Mr. Hyde", "Robert Louis Stevenson", "One London doctor, two faces. Short and unsettling."),
            Pick("The Turn of the Screw", "Henry James", "A governess, two children and ghosts nobody else will admit to."),
            Pick("The Moonstone", "Wilkie Collins", "The first great detective novel: a stolen diamond and a dozen liars."),
            Pick("The Phantom of the Opera", "Gaston Leroux", "A ghost beneath the Paris Opera and a voice that obsesses."),
            Pick("The Mysterious Affair at Styles", "Agatha Christie", "Hercule Poirot's first case, a country house and a poisoning."))),
        Collection("ideas", "Big ideas", "Short, sharp books that changed how people think", listOf(
            Pick("The Art of War", "Sun Tzu", "Two thousand years of strategy in a couple of hours."),
            Pick("Meditations", "Marcus Aurelius", "An emperor's private notes on staying calm when everything burns."),
            Pick("Walden", "Henry David Thoreau", "Two years in a cabin and a case for living deliberately."),
            Pick("The Prince", "Niccolo Machiavelli", "Cold, clever and still quoted: how power really works."),
            Pick("Thus Spake Zarathustra", "Friedrich Nietzsche", "A prophet comes down the mountain with uncomfortable news."),
            Pick("A Modest Proposal", "Jonathan Swift", "The most famous satire in English, and it is very short."),
            Pick("The Republic", "Plato", "What is justice, and who should rule? The conversation that began philosophy."))),
        Collection("adventure", "Escape into adventure", "Pirates, castaways and revenge on a grand scale", listOf(
            Pick("Treasure Island", "Robert Louis Stevenson", "Long John Silver, a map and the best pirate story ever told."),
            Pick("The Count of Monte Cristo", "Alexandre Dumas", "Betrayed, imprisoned, and then a very patient revenge."),
            Pick("Twenty Thousand Leagues Under the Sea", "Jules Verne", "Captain Nemo, a submarine and the deep."),
            Pick("The Call of the Wild", "Jack London", "A sled dog and the Klondike. Brutal and beautiful."),
            Pick("Around the World in Eighty Days", "Jules Verne", "A bet, a gentleman and a race against the calendar."),
            Pick("Robinson Crusoe", "Daniel Defoe", "Shipwrecked for decades. The original survival story."),
            Pick("The Three Musketeers", "Alexandre Dumas", "Swords, queens and four friends: all for one."),
            Pick("Moby Dick", "Herman Melville", "A whale, an obsession and an American epic."))),
        Collection("love", "Love and society", "Wit, longing and very bad matchmaking", listOf(
            Pick("Pride and Prejudice", "Jane Austen", "Elizabeth, Mr Darcy and the sharpest first impressions in fiction."),
            Pick("Jane Eyre", "Charlotte Bronte", "An orphan, a brooding house and a secret in the attic."),
            Pick("Emma", "Jane Austen", "A matchmaker who is wrong about almost everything."),
            Pick("Wuthering Heights", "Emily Bronte", "A love that outlives everyone on the Yorkshire moors."),
            Pick("Persuasion", "Jane Austen", "A second chance, eight years late. Austen's most tender book."),
            Pick("Little Women", "Louisa May Alcott", "Four sisters growing up. Warm, funny, and it hurts."),
            Pick("The Great Gatsby", "F. Scott Fitzgerald", "Parties, longing and the green light across the bay."))),
        Collection("scifi", "Where sci-fi began", "The books that invented the future", listOf(
            Pick("The Time Machine", "H. G. Wells", "A Victorian inventor travels 800,000 years ahead."),
            Pick("The War of the Worlds", "H. G. Wells", "Martians land in England. Nobody is ready."),
            Pick("The Invisible Man", "H. G. Wells", "A brilliant scientist who should not have succeeded."),
            Pick("The Island of Doctor Moreau", "H. G. Wells", "A shipwreck, an island and an experiment gone wrong."),
            Pick("A Princess of Mars", "Edgar Rice Burroughs", "A Confederate soldier wakes up on Mars. Pure pulp joy."),
            Pick("Flatland", "Edwin Abbott", "A square discovers the third dimension. Weirdly moving."),
            Pick("From the Earth to the Moon", "Jules Verne", "Gun club members decide to shoot a cannon at the Moon."))),
        Collection("short", "Quick listens", "Great stories you can finish in an evening", listOf(
            Pick("The Metamorphosis", "Franz Kafka", "A man wakes up as an insect. It only gets stranger."),
            Pick("A Christmas Carol", "Charles Dickens", "Scrooge and three ghosts, the perfect short novel."),
            Pick("The Yellow Wallpaper", "Charlotte Perkins Gilman", "A woman, a room and wallpaper that watches back."),
            Pick("Heart of Darkness", "Joseph Conrad", "Up the Congo into something worse than the jungle."),
            Pick("Bartleby, the Scrivener", "Herman Melville", "\"I would prefer not to.\" A clerk who quietly stops."),
            Pick("The Picture of Dorian Gray", "Oscar Wilde", "A portrait ages while its owner stays beautiful."))),
    )

    private val resolved = ConcurrentHashMap<String, Book>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(4)

    suspend fun resolve(p: Pick, lv: LibriVoxAddon): Book? {
        resolved[p.title]?.let { return it }
        if (p.title in missing) return null
        val first = p.title.substringBefore(":")
        val book = gate.withPermit {
            runCatching { lv.catalog("latest", first, 0) }.getOrNull()?.let { l ->
                val norm = { t: String -> t.substringBefore(" (").substringBefore(",").trim() }
                val ok = l.filter { it.totalSec > 0 && it.title.startsWith(first, true) }
                ok.firstOrNull { norm(it.title).equals(first, true) }                 // exact title wins (not "...for Children")
                    ?: ok.minByOrNull { it.title.length } ?: l.firstOrNull { it.title.contains(first.take(18), true) }
            }
        }
        if (book != null) resolved[p.title] = book else missing.add(p.title)
        return book
    }

    fun blurbOf(b: Book): String? = collections.flatMap { it.picks }.firstOrNull { b.title.startsWith(it.title.substringBefore(":"), true) }?.blurb

    /** Stable for a whole day, different tomorrow. */
    fun seed(): Long = LocalDate.now().toEpochDay()

    /** The shelves Home shows today (order and content rotate), plus the pick of the day. */
    fun today(): Pair<List<Shelf>, Pick> {
        val rnd = Random(seed())
        val order = collections.shuffled(rnd)
        val shelves = order.take(4).map { c -> Shelf(c.id, c.title, c.subtitle) }
        val all = collections.flatMap { it.picks }
        return shelves to all.random(rnd)
    }

    fun editorsPicks(): List<Pick> = collections.flatMap { it.picks }.shuffled(Random(seed() * 31 + 7)).take(10)

    fun picksFor(shelfId: String): List<Pick> =
        if (shelfId == "editors") editorsPicks() else collections.first { it.id == shelfId }.picks.shuffled(Random(seed() + shelfId.hashCode()))
}
