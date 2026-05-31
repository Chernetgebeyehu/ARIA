package com.cherinet.aria.ai

object RuleEngine {

    data class RuleResult(val matched: Boolean, val response: String)

    private data class Rule(val triggers: List<String>, val response: String, val priority: Int = 0)

    private val rules = listOf(
        Rule(listOf("hello","hi aria","hey aria","good morning","selam","ሰላም"),
            """{"type":"chat","text":"Hey! I'm here. I'm in offline mode right now — basic commands still work!"}""", 10),
        Rule(listOf("who are you","what are you","your name","introduce yourself"),
            """{"type":"chat","text":"I'm ARIA — your AI assistant. Running in offline mode. I can open apps, set alarms, make calls, and handle basic commands."}""", 10),
        Rule(listOf("open youtube","launch youtube"),
            """{"type":"action","spoken":"Opening YouTube!","actions":[{"tool":"open_app","value":"youtube"}]}""", 8),
        Rule(listOf("open whatsapp","whatsapp"),
            """{"type":"action","spoken":"Opening WhatsApp!","actions":[{"tool":"open_app","value":"whatsapp"}]}""", 8),
        Rule(listOf("open camera","take photo","camera"),
            """{"type":"action","spoken":"Opening camera!","actions":[{"tool":"open_app","value":"camera"}]}""", 8),
        Rule(listOf("open instagram","instagram"),
            """{"type":"action","spoken":"Opening Instagram!","actions":[{"tool":"open_app","value":"instagram"}]}""", 8),
        Rule(listOf("open telegram","telegram"),
            """{"type":"action","spoken":"Opening Telegram!","actions":[{"tool":"open_app","value":"telegram"}]}""", 8),
        Rule(listOf("open chrome","open browser"),
            """{"type":"action","spoken":"Opening browser!","actions":[{"tool":"open_app","value":"chrome"}]}""", 8),
        Rule(listOf("open settings","settings"),
            """{"type":"action","spoken":"Opening settings.","actions":[{"tool":"settings","value":"general"}]}""", 8),
        Rule(listOf("call mom","call my mom"),
            """{"type":"action","spoken":"Calling mom!","actions":[{"tool":"call","value":"mom"}]}""", 9),
        Rule(listOf("call dad","call my dad"),
            """{"type":"action","spoken":"Calling dad!","actions":[{"tool":"call","value":"dad"}]}""", 9),
        Rule(listOf("set alarm","alarm for","wake me up"),
            """{"type":"action","spoken":"Opening alarm app!","actions":[{"tool":"open_app","value":"clock"}]}""", 7),
        Rule(listOf("set timer","timer for"),
            """{"type":"action","spoken":"Opening timer!","actions":[{"tool":"open_app","value":"clock"}]}""", 7),
        Rule(listOf("search for","search ","google ","look up"),
            """{"type":"action","spoken":"Searching now!","actions":[{"tool":"search","value":"query"}]}""", 7),
        Rule(listOf("ሰላም","እንደምን","selam"),
            """{"type":"chat","text":"ሰላም! እዚህ ነኝ። (Hello! I'm here.) Offline mode — basic commands work."}""", 9),
        Rule(listOf("help","what can you do","how do you work"),
            """{"type":"chat","text":"Offline mode: open apps ('open YouTube'), make calls ('call mom'), set alarms, search. Full AI needs internet."}""", 6),
        Rule(listOf("thank you","thanks","great","awesome"),
            """{"type":"chat","text":"Glad I could help! Anything else?"}""", 5),
        Rule(listOf("bye","goodbye","close aria"),
            """{"type":"chat","text":"Goodbye! I'll keep listening in the background."}""", 5)
    ).sortedByDescending { it.priority }

    fun match(input: String): RuleResult {
        val lower = input.lowercase().trim()
        for (rule in rules) {
            for (trigger in rule.triggers) {
                if (lower.contains(trigger.lowercase())) {
                    var response = rule.response
                    if (response.contains("\"value\":\"query\"")) {
                        val q = lower.replace("search for","").replace("search","")
                            .replace("google","").replace("look up","").trim()
                        response = response.replace("\"value\":\"query\"",
                            "\"value\":\"${q.ifBlank{"results"}}\"")
                    }
                    return RuleResult(true, response)
                }
            }
        }
        val safe = input.take(60).replace("\"","'")
        return RuleResult(false,
            """{"type":"chat","text":"I'm offline and couldn't handle: '$safe'. Check internet for full AI, or try a simpler command."}""")
    }

    fun canHandle(input: String) = match(input).matched
}