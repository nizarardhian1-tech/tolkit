package com.mondns.app

import android.graphics.Color
import android.text.Editable
import android.text.Spannable
import android.text.style.ForegroundColorSpan

/**
 * Syntax highlighter sederhana berbasis regex untuk editor HTML Runner.
 *
 * Ini BUKAN parser HTML/CSS/JS yang sesungguhnya (tidak ada tokenizer/AST) — cuma
 * pencocokan pola per-elemen (tag, atribut, string, komentar, keyword JS, angka) yang
 * ditempel sebagai warna di atas teks. Cukup buat bikin kode lebih gampang dibaca saat
 * ditulis/ditempel, dan ringan dijalankan tiap kali teks berubah tanpa bikin ketikan lag.
 *
 * Urutan penerapan warna sengaja: keyword/angka/tag/atribut dulu, baru string & komentar
 * di atasnya — supaya string/komentar yang "menimpa" kata kunci di dalamnya menang
 * (mendekati perilaku highlighter asli walau tanpa parser sungguhan).
 */
object HtmlSyntaxHighlighter {

    private val TAG_PATTERN = Regex("</?[a-zA-Z][a-zA-Z0-9-]*")
    private val ATTR_PATTERN = Regex("\\b[a-zA-Z-]+(?=\\s*=)")
    private val NUMBER_PATTERN = Regex("\\b\\d+(\\.\\d+)?\\b")
    private val JS_KEYWORD_PATTERN = Regex(
        "\\b(function|var|let|const|if|else|return|for|while|new|this|true|false|null|" +
            "undefined|typeof|break|continue|switch|case|default|try|catch|finally|class|" +
            "extends|import|export|from|async|await|document|window|console)\\b"
    )
    private val STRING_PATTERN = Regex("\"[^\"\\n]*\"|'[^'\\n]*'")
    private val COMMENT_PATTERN = Regex("<!--[\\s\\S]*?-->|/\\*[\\s\\S]*?\\*/|//[^\\n]*")

    private val COLOR_TAG = Color.parseColor("#569CD6")
    private val COLOR_ATTR = Color.parseColor("#9CDCFE")
    private val COLOR_NUMBER = Color.parseColor("#B5CEA8")
    private val COLOR_KEYWORD = Color.parseColor("#C586C0")
    private val COLOR_STRING = Color.parseColor("#CE9178")
    private val COLOR_COMMENT = Color.parseColor("#6A9955")

    fun highlight(editable: Editable) {
        // Buang span warna lama sebelum apply ulang, supaya tidak numpuk tiap ketikan.
        editable.getSpans(0, editable.length, ForegroundColorSpan::class.java).forEach {
            editable.removeSpan(it)
        }

        val text = editable.toString()
        if (text.isEmpty()) return

        applyColor(editable, text, NUMBER_PATTERN, COLOR_NUMBER)
        applyColor(editable, text, JS_KEYWORD_PATTERN, COLOR_KEYWORD)
        applyColor(editable, text, ATTR_PATTERN, COLOR_ATTR)
        applyColor(editable, text, TAG_PATTERN, COLOR_TAG)
        applyColor(editable, text, STRING_PATTERN, COLOR_STRING)
        applyColor(editable, text, COMMENT_PATTERN, COLOR_COMMENT)
    }

    private fun applyColor(editable: Editable, text: String, regex: Regex, color: Int) {
        for (match in regex.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1
            if (start in 0..editable.length && end in start..editable.length) {
                editable.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
