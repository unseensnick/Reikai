package tachiyomi.presentation.core.icons

class FlagEmoji {
    companion object {
        /**
         * The regional indicators go from 0x1F1E6 (A) to 0x1F1FF (Z).
         * This is the A regional indicator value minus 65 decimal so
         * that we can just add this to the A-Z char
         */
        private const val REGIONAL_INDICATOR_OFFSET: Int = 0x1F1A5

        private fun getRegionalIndicatorSymbol(character: Char): String {
            require(!(character < 'A' || character > 'Z')) { "Invalid character: you must use A-Z" }
            return String(Character.toChars(REGIONAL_INDICATOR_OFFSET + character.code))
        }

        fun getEmojiCountryFlag(countryCode: String): String {
            val code = countryCode.uppercase()
            return getRegionalIndicatorSymbol(code[0]) + getRegionalIndicatorSymbol(code[1])
        }

        fun getEmojiLangFlag(lang: String): String {
            return lang2Country(lang)?.let { getEmojiCountryFlag(it) } ?: "🌎"
        }

        private fun lang2Country(lang: String): String? {
            return when (lang) {
                "all" -> "un"
                "af" -> "za" // Afrikaans -> South Africa, ZA
                "am" -> "et" // Amharic -> Ethiopia, ET
                "ar" -> "eg" // Arabic -> Egypt, EG - Saudi Arabia, SA
                "az" -> "az" // Azerbaijani -> Azerbaijan, AZ
                "be" -> "by" // Belarusian -> Belarus, BY
                "bg" -> "bg" // Bulgarian -> Bulgaria, BG
                "bn" -> "bd" // Bengali -> Bangladesh, BD
                "br" -> "fr" // Breton -> France, FR
                "bs" -> "ba" // Bosnia and Herzegovina, BA
                "ca" -> "es" // Catalan -> Spain, ES
                "ceb" -> "ph" // Cebuano -> Philippines, PH
                "cn" -> "cn" // Chinese -> China, CN
                "co" -> "es" // Corsican -> Spain, ES
                "cs" -> "cz" // Czech -> Czech Republic, CZ
                "da" -> "dk" // Danish -> Denmark, DK
                "de" -> "de" // German -> Germany, DE
                "el" -> "gr" // Greek -> Greece, GR
                "en" -> "us" // English -> United States, US
                "es-419" -> "mx" // Spanish -> Mexico MX, Latin America
                "es" -> "es" // Spanish -> Spain, ES
                "et" -> "ee" // Estonian -> Estonia, EE
                "eu" -> "es" // Basque -> Spain, ES
                "fa" -> "ir" // Persian -> Iran, IR
                "fi" -> "fi" // Finnish -> Finland, FI
                "fil" -> "ph" // Filipino -> Philippines, PH
                "fo" -> "fo" // Faroese -> Faroe Island, FO
                "fr" -> "fr" // French -> France, FR
                "ga" -> "ie" // Irish -> Ireland, IE
                "gn" -> "py" // Guarani -> Paraguay, PY
                "gu" -> "in_" // Gujarati -> India, IN
                "ha" -> "ng" // Hausa -> Nigeria, NG
                "he" -> "il" // Hebrew -> Israel, IL
                "hi" -> "in_" // Hindi -> India, IN
                "hr" -> "hr" // Croatian -> Croatia, HR
                "ht" -> "ht" // Haitian -> Haiti, HT
                "hu" -> "hu" // Hungarian -> Hungary, HU
                "hy" -> "am" // Armenian -> Armenia, AM
                "id" -> "id" // Indonesian -> Indonesia, ID
                "ig" -> "ng" // Igbo -> Nigeria, NG
                "is" -> "is_" // Icelandic -> Iceland, IS
                "it" -> "it" // Italian -> Italy, IT
                "ja" -> "jp" // Japanese -> Japan, JP
                "jv" -> "id" // Javanese -> Indonesia, ID
                "ka" -> "ge" // Georgian -> Georgia, GE
                "kk" -> "kz" // Kazakh -> Kazakhstan, KZ
                "km" -> "kh" // Khmer -> Cambodia, KH
                "kn" -> "in_" // Kannada -> India, IN
                "ko" -> "kr" // Korean -> South Korea, KR
                "kr" -> "ng" // Kanuri -> Nigeria, NG
                "ku" -> "iq" // Kurdish -> Iraq, IQ
                "ky" -> "kg" // Kyrgyz -> Kyrgyzstan, KG
                "lb" -> "lu" // Luxembourgish -> Luxembourg, LU
                "lmo" -> "it" // Lombard, Italy, IT
                "lo" -> "la" // Lao -> Laos, LA
                "lt" -> "lt" // Lithuanian -> Lithuania, LT
                "lv" -> "lv" // Latvian -> Latvia, LV
                "mg" -> "mg" // Malagasy -> Madagascar, MG
                "mi" -> "nz" // Maori -> New Zealand, NZ
                "mk" -> "mk" // Macedonian -> Macedonia, MK
                "ml" -> "in_" // Malayalam -> India, IN
                "mn" -> "mn" // Mongolian -> Mongolia, MN
                "mo" -> "md" // Moldavian -> Moldova, MD
                "mr" -> "in_" // Marathi -> India, IN
                "ms" -> "my" // Malay -> Malaysia, MY
                "mt" -> "mt" // Maltese -> Malta, MT
                "my" -> "mm" // Myanmar -> Myanmar, MM
                "ne" -> "np" // Nepali -> Nepal, NP
                "nl" -> "nl" // Dutch -> Netherlands, NL
                "no" -> "no" // Norwegian -> Norway, NO
                "ny" -> "mw" // Nyanja -> Malawi, MW
                "pl" -> "pl" // Polish -> Poland, PL
                "ps" -> "af" // Pashto -> Afghanistan, AF - Pakistan, PK
                "pt-BR" -> "br" // Portuguese, Brazil, BR
                "pt-PT" -> "pt" // Portuguese, Portugal, PT
                "pt" -> "pt" // Portuguese -> Portugal, PT
                "rm" -> "ch" // Romansh -> Switzerland, SW
                "ro" -> "ro" // Romanian -> Romania, RO
                "ru" -> "ru" // Russian -> Russia, RU
                "sd" -> "pk" // Sindhi -> Pakistan, PK
                "sh" -> "hr" // Serbo-Croatian -> Serbia, HR
                "si" -> "lk" // Sinhalese -> Sri Lanka, LK
                "sk" -> "sk" // Slovak -> Slovakia, SK
                "sl" -> "si" // Slovenian -> Slovenia, SI
                "sm" -> "ws" // Samoan -> Samoa, WS
                "sn" -> "zw" // Shona -> Zimbabwe, ZW
                "so" -> "so" // Somali -> Somalia, SO
                "sq" -> "al" // Albanian -> Albania, AL
                "sr" -> "hr" // Serbian -> Serbia, HR
                "st" -> "ls" // Sesotho -> South Africa, ZA - Lesotho, LS
                "sv" -> "se" // Swedish -> Sweden, SE
                "sw" -> "tz" // Swahili -> Tanzania, TZ - Kenya, KE
                "ta" -> "in_" // Tamil -> India, IN
                "te" -> "in_" // Telugu -> India, IN
                "tg" -> "tj" // Tajik -> Tajikistan, TJ
                "th" -> "th" // Thai -> Thailand, TH
                "ti" -> "er" // Tigrinya -> Eritrea, ER
                "tk" -> "tm" // Turkmen -> Turkmenistan, TM
                "tl" -> "ph" // Filipino -> Philippines, PH
                "to" -> "to" // Tonga -> Tonga, TO
                "tr" -> "tr" // Turkish -> Turkey, TR
                "uk" -> "ua" // Ukrainian -> Ukraine, UA
                "ur" -> "pk" // Urdu -> Pakistan, PK
                "uz" -> "uz" // Uzbek -> Uzbekistan, UZ
                "vec" -> "it" // Venetian -> Italy, IT
                "vi" -> "vn" // Vietnamese -> Vietnam, VN
                "yo" -> "ng" // Yoruba -> Nigeria, NG
                "zh-Hans" -> "cn" // Chinese, simplified -> China, CN
                "zh-Hant" -> "tw" // Chinese, traditional -> Taiwan, TW
                "zh" -> "cn" // Chinese -> China, CN
                "zu" -> "za" // Zulu -> South Africa, ZA
                // Additional from App language
                "gl" -> "es" // Galician -> Spain, ES
                "in" -> "id" // Indonesian -> Indonesia, ID
                "nb-NO" -> "no" // Norwegian Bokmal -> Norway, NO
                "nn" -> "no" // Norwegian Nynorsk -> Norway, NO
                "sc" -> "it" // Sardinian -> Italy, IT
                "sdh" -> "ir" // Southern Kurdish -> Iran, IR
                "sah" -> "ru" // Sakha -> Russia, RU
                "cv" -> "ru" // Chuvash -> Russia, RU
                "sa" -> "in_" // Sanskrit -> India, IN
                "ka-GE" -> "ge" // Georgian -> Georgia, GE
                "zh-CN" -> "cn" // Chinese, simplified -> China, CN
                "zh-TW" -> "tw" // Chinese, traditional -> Taiwan, TW
                else -> null
            }
        }
    }
}
