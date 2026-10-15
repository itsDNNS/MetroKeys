package app.metrokeys.engine.predict

/** Emoji suggestion tables (spec 05 Appendix A); lookup key = lower-cased word. */
internal object EmojiTables {
    private fun table(spec: String): Map<String, String> = buildMap {
        for (entry in spec.split(",")) {
            val parts = entry.trim().split(" ")
            if (parts.size == 2) put(parts[0], parts[1])
        }
    }

    val GERMAN: Map<String, String> = table(
        "liebe ❤️, herz ❤️, kuss 😘, küsse 😘, lachen 😂, haha 😂, lol 😂, lustig 😄, freude 😊, froh 😊, " +
            "glücklich 😊, toll 😃, traurig 😢, weinen 😢, wütend 😠, ärger 😠, angst 😱, wow 😮, cool 😎, müde 😴, schlafen 😴, " +
            "krank 😷, gut 👍, super 👍, ja 👍, nein 👎, ok 👌, okay 👌, danke 🙏, bitte 🙏, hallo 👋, tschüss 👋, sonne ☀️, " +
            "regen 🌧️, schnee ❄️, wolke ☁️, gewitter ⛈️, blitz ⚡, regenbogen 🌈, mond 🌙, stern ⭐, feuer 🔥, heiß 🔥, blume 🌸, " +
            "rose 🌹, baum 🌳, glück 🍀, kaffee ☕, tee 🍵, bier 🍺, wein 🍷, pizza 🍕, burger 🍔, hunger 🍴, essen 🍴, kuchen 🍰, " +
            "torte 🎂, geburtstag 🎂, party 🎉, geschenk 🎁, apfel 🍎, banane 🍌, eis 🍦, schokolade 🍫, hund 🐶, katze 🐱, " +
            "pferd 🐴, vogel 🐦, auto 🚗, zug 🚆, bus 🚌, fahrrad 🚲, flugzeug ✈️, urlaub 🌴, strand 🏖️, haus 🏠, schule 🏫, " +
            "arbeit 💼, geld 💰, uhr ⌚, zeit ⏰, telefon 📞, handy 📱, computer 💻, mail 📧, brief ✉️, buch 📖, musik 🎵, foto 📷, " +
            "kamera 📷, film 🎬, idee 💡, fußball ⚽, sport 🏃, baby 👶, weihnachten 🎄, ostern 🐣, musiker 🎸",
    )

    val ENGLISH: Map<String, String> = table(
        "love ❤️, heart ❤️, kiss 😘, kisses 😘, laugh 😂, haha 😂, lol 😂, funny 😄, joy 😊, happy 😊, great 😃, " +
            "smile 😊, sad 😢, cry 😢, angry 😠, mad 😠, scared 😱, wow 😮, cool 😎, tired 😴, sleep 😴, sick 😷, good 👍, yes 👍, " +
            "no 👎, ok 👌, okay 👌, thanks 🙏, please 🙏, hello 👋, hi 👋, bye 👋, sun ☀️, rain 🌧️, snow ❄️, cloud ☁️, storm ⛈️, " +
            "lightning ⚡, rainbow 🌈, moon 🌙, star ⭐, fire 🔥, hot 🔥, flower 🌸, rose 🌹, tree 🌳, luck 🍀, coffee ☕, tea 🍵, " +
            "beer 🍺, wine 🍷, pizza 🍕, burger 🍔, hungry 🍴, food 🍴, cake 🍰, birthday 🎂, party 🎉, gift 🎁, present 🎁, " +
            "apple 🍎, banana 🍌, chocolate 🍫, dog 🐶, cat 🐱, horse 🐴, bird 🐦, car 🚗, train 🚆, bus 🚌, bike 🚲, plane ✈️, " +
            "vacation 🌴, beach 🏖️, home 🏠, house 🏠, school 🏫, work 💼, money 💰, time ⏰, phone 📱, computer 💻, mail 📧, " +
            "letter ✉️, book 📖, music 🎵, photo 📷, camera 📷, movie 🎬, idea 💡, soccer ⚽, football 🏈, baby 👶, " +
            "christmas 🎄, easter 🐣, guitar 🎸",
    )
}
