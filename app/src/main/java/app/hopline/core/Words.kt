package app.hopline.core

import java.security.SecureRandom

/**
 * The group code is three everyday words: "tiger river lamp". Easy to say across a campsite, easy to
 * type with gloves on, and no look-alike letters or sound-alike words (no "key"/"quay", "red"/"read").
 */
object Words {
    val LIST: List<String> = """
        apple banana cherry grape lemon mango melon orange peach plum kiwi olive pumpkin potato tomato onion garlic pepper
        tiger lion zebra panda koala camel sheep goat rabbit monkey otter walrus turtle frog snake lizard eagle parrot owl
        robin duck goose swan penguin dolphin shark crab shrimp squid spider beetle ladybug dragon unicorn puppy kitten pony
        donkey hippo rhino giraffe gorilla badger beaver bison falcon heron pelican salmon trout octopus lobster wasp cricket
        river lake ocean island mountain valley forest jungle meadow garden canyon volcano glacier waterfall pond cave cliff
        hill field swamp moon star cloud storm thunder rainbow snow wind fog frost comet planet rocket galaxy sunrise sunset
        sky breeze green yellow purple pink silver gold brown black white violet indigo crimson scarlet lamp chair table
        window door pillow blanket candle mirror clock basket bucket bottle kettle spoon fork plate cup bowl carpet sofa shelf
        hat sock glove scarf jacket button zipper pocket ribbon crown helmet boot sandal belt car bus train truck boat ship
        bike wagon canoe kayak scooter tractor balloon jet sled drum guitar piano violin trumpet flute whistle banjo harp
        pizza cookie muffin pancake waffle noodle honey butter sugar salt cheese toast soup salad taco burger pretzel popcorn
        jelly candy donut king queen wizard pirate robot ninja giant puppet clown cowboy sailor farmer baker doctor tent rope
        torch compass map trail camp cabin bridge tower castle tunnel ladder fence barn igloo wall kite puzzle marble crayon
        pencil paper book letter stamp coin lock magnet yoyo happy brave quick quiet fuzzy shiny sleepy jolly lucky silly
        tiny mighty gentle sunny windy rainy frosty dusty sparkly jump dance sing swim climb ride hop skip clap spin run
        walk fly pebble sand mud shell feather leaf acorn cactus bamboo maple willow tulip daisy lotus clover fern moss oak
        pine palm cotton wool velvet copper iron crystal ruby amber jade hammer nest hive north south east west
    """.trim().split(Regex("\\s+"))

    private val rng = SecureRandom()

    fun randomCode(n: Int = 3): String = (1..n).joinToString(" ") { LIST[rng.nextInt(LIST.size)] }

    /** "Tiger, River LAMP" -> "tiger-river-lamp". Anything non-alphabetic is a separator. */
    fun normalise(code: String): String =
        code.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.joinToString("-")

    fun pretty(code: String): String = normalise(code).replace('-', ' ')

    fun looksValid(code: String): Boolean = normalise(code).split('-').let { it.size == 3 && it.all { w -> w.length >= 2 } }
}
