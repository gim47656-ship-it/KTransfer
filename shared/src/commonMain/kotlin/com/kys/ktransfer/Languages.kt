package com.kys.ktransfer

data class Language(val code: String, val label: String)

val KOREAN = Language("ko", "한국어")

/** Partner languages. Codes follow the Live Translate supported-language table (BCP-47). */
val PARTNER_LANGUAGES = listOf(
    Language("en", "영어"),
    Language("ja", "일본어"),
    Language("zh-Hans", "중국어(간체)"),
    Language("vi", "베트남어"),
    Language("zh-Hant", "중국어(번체)"),
    Language("th", "태국어"),
    Language("id", "인도네시아어"),
    Language("fil", "필리핀어"),
    Language("ms", "말레이어"),
    Language("es", "스페인어"),
    Language("fr", "프랑스어"),
    Language("de", "독일어"),
    Language("it", "이탈리아어"),
    Language("pt-BR", "포르투갈어(브라질)"),
    Language("ru", "러시아어"),
    Language("ar", "아랍어"),
    Language("hi", "힌디어"),
    Language("tr", "튀르키예어"),
    Language("mn", "몽골어"),
    Language("uz", "우즈베크어"),
    Language("km", "크메르어"),
    Language("my", "미얀마어"),
    Language("ne", "네팔어"),
)
