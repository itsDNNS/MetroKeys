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

    val SPANISH: Map<String, String> = table(
        "amor ❤️, corazón ❤️, beso 😘, besos 😘, reír 😂, jaja 😂, jajaja 😂, gracioso 😄, alegría 😊, feliz 😊, " +
            "contento 😊, genial 😃, triste 😢, llorar 😢, enfadado 😠, enojado 😠, miedo 😱, guau 😮, wow 😮, cansado 😴, " +
            "dormir 😴, sueño 😴, enfermo 😷, bien 👍, vale 👍, sí 👍, no 👎, ok 👌, gracias 🙏, porfa 🙏, hola 👋, adiós 👋, " +
            "sol ☀️, lluvia 🌧️, nieve ❄️, nube ☁️, tormenta ⛈️, rayo ⚡, arcoíris 🌈, luna 🌙, estrella ⭐, fuego 🔥, calor 🔥, " +
            "flor 🌸, rosa 🌹, árbol 🌳, suerte 🍀, café ☕, té 🍵, cerveza 🍺, vino 🍷, pizza 🍕, hamburguesa 🍔, hambre 🍴, " +
            "comida 🍴, comer 🍴, pastel 🍰, tarta 🎂, cumpleaños 🎂, fiesta 🎉, regalo 🎁, manzana 🍎, plátano 🍌, helado 🍦, " +
            "chocolate 🍫, perro 🐶, gato 🐱, caballo 🐴, pájaro 🐦, coche 🚗, tren 🚆, autobús 🚌, bici 🚲, bicicleta 🚲, " +
            "avión ✈️, vacaciones 🌴, playa 🏖️, casa 🏠, colegio 🏫, escuela 🏫, trabajo 💼, dinero 💰, reloj ⌚, hora ⏰, " +
            "teléfono 📞, móvil 📱, ordenador 💻, correo 📧, carta ✉️, libro 📖, música 🎵, foto 📷, cámara 📷, película 🎬, " +
            "idea 💡, fútbol ⚽, deporte 🏃, bebé 👶, navidad 🎄, pascua 🐣, guitarra 🎸",
    )

    val PORTUGUESE: Map<String, String> = table(
        "amor ❤️, coração ❤️, beijo 😘, beijos 😘, rir 😂, haha 😂, kkkk 😂, rsrs 😂, engraçado 😄, alegria 😊, " +
            "feliz 😊, contente 😊, legal 😃, ótimo 😃, triste 😢, chorar 😢, bravo 😠, irritado 😠, medo 😱, uau 😮, " +
            "cansado 😴, dormir 😴, sono 😴, doente 😷, bem 👍, beleza 👍, sim 👍, não 👎, ok 👌, obrigado 🙏, obrigada 🙏, " +
            "valeu 🙏, oi 👋, olá 👋, tchau 👋, sol ☀️, chuva 🌧️, neve ❄️, nuvem ☁️, tempestade ⛈️, raio ⚡, lua 🌙, " +
            "estrela ⭐, fogo 🔥, calor 🔥, flor 🌸, rosa 🌹, árvore 🌳, sorte 🍀, café ☕, chá 🍵, cerveja 🍺, vinho 🍷, " +
            "pizza 🍕, hambúrguer 🍔, fome 🍴, comida 🍴, comer 🍴, bolo 🍰, aniversário 🎂, festa 🎉, carnaval 🎭, " +
            "presente 🎁, maçã 🍎, banana 🍌, sorvete 🍦, chocolate 🍫, cachorro 🐶, gato 🐱, cavalo 🐴, pássaro 🐦, carro 🚗, " +
            "trem 🚆, ônibus 🚌, bicicleta 🚲, bike 🚲, avião ✈️, férias 🌴, praia 🏖️, casa 🏠, escola 🏫, trabalho 💼, " +
            "dinheiro 💰, relógio ⌚, hora ⏰, telefone 📞, celular 📱, computador 💻, email 📧, carta ✉️, livro 📖, música 🎵, " +
            "foto 📷, câmera 📷, filme 🎬, ideia 💡, futebol ⚽, esporte 🏃, bebê 👶, natal 🎄, páscoa 🐣, violão 🎸",
    )

    val ITALIAN: Map<String, String> = table(
        "amore ❤️, cuore ❤️, bacio 😘, baci 😘, ridere 😂, ahah 😂, ahahah 😂, divertente 😄, gioia 😊, felice 😊, " +
            "contento 😊, fantastico 😃, triste 😢, piangere 😢, arrabbiato 😠, paura 😱, wow 😮, stanco 😴, dormire 😴, " +
            "sonno 😴, malato 😷, bene 👍, sì 👍, no 👎, ok 👌, okay 👌, grazie 🙏, prego 🙏, ciao 👋, sole ☀️, pioggia 🌧️, " +
            "neve ❄️, nuvola ☁️, temporale ⛈️, fulmine ⚡, arcobaleno 🌈, luna 🌙, stella ⭐, fuoco 🔥, caldo 🔥, fiore 🌸, " +
            "rosa 🌹, albero 🌳, fortuna 🍀, caffè ☕, tè 🍵, birra 🍺, vino 🍷, pizza 🍕, pasta 🍝, hamburger 🍔, fame 🍴, " +
            "mangiare 🍴, cibo 🍴, torta 🎂, compleanno 🎂, festa 🎉, regalo 🎁, mela 🍎, banana 🍌, gelato 🍦, cioccolato 🍫, " +
            "cane 🐶, gatto 🐱, cavallo 🐴, uccello 🐦, macchina 🚗, treno 🚆, autobus 🚌, bici 🚲, bicicletta 🚲, aereo ✈️, " +
            "vacanze 🌴, spiaggia 🏖️, mare 🌊, casa 🏠, scuola 🏫, lavoro 💼, soldi 💰, orologio ⌚, telefono 📞, " +
            "cellulare 📱, computer 💻, email 📧, lettera ✉️, libro 📖, musica 🎵, foto 📷, film 🎬, idea 💡, calcio ⚽, " +
            "sport 🏃, bambino 👶, natale 🎄, pasqua 🐣, chitarra 🎸",
    )

    val FRENCH: Map<String, String> = table(
        "amour ❤️, cœur ❤️, bisou 😘, bisous 😘, rire 😂, mdr 😂, ptdr 😂, drôle 😄, joie 😊, heureux 😊, " +
            "heureuse 😊, content 😊, génial 😃, triste 😢, pleurer 😢, fâché 😠, colère 😠, peur 😱, waouh 😮, cool 😎, " +
            "fatigué 😴, dormir 😴, malade 😷, bien 👍, super 👍, oui 👍, non 👎, ok 👌, accord 👌, merci 🙏, bonjour 👋, " +
            "salut 👋, coucou 👋, soleil ☀️, pluie 🌧️, neige ❄️, nuage ☁️, orage ⛈️, éclair ⚡, lune 🌙, étoile ⭐, feu 🔥, " +
            "chaud 🔥, fleur 🌸, rose 🌹, arbre 🌳, chance 🍀, café ☕, thé 🍵, bière 🍺, vin 🍷, pizza 🍕, burger 🍔, faim 🍴, " +
            "manger 🍴, gâteau 🍰, anniversaire 🎂, fête 🎉, cadeau 🎁, pomme 🍎, banane 🍌, glace 🍦, chocolat 🍫, " +
            "croissant 🥐, baguette 🥖, fromage 🧀, chien 🐶, chat 🐱, cheval 🐴, oiseau 🐦, voiture 🚗, train 🚆, bus 🚌, " +
            "vélo 🚲, avion ✈️, vacances 🌴, plage 🏖️, mer 🌊, maison 🏠, école 🏫, travail 💼, boulot 💼, argent 💰, " +
            "montre ⌚, heure ⏰, téléphone 📞, portable 📱, ordinateur 💻, mail 📧, lettre ✉️, livre 📖, musique 🎵, photo 📷, " +
            "film 🎬, idée 💡, foot ⚽, football ⚽, sport 🏃, bébé 👶, noël 🎄, pâques 🐣, guitare 🎸",
    )
}
