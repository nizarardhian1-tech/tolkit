package com.mondns.app

/**
 * Demangler untuk C++ Itanium ABI mangling (dipakai Clang/GCC — termasuk semua
 * toolchain Android NDK). Simbol C++ yang di-compile selalu di-"encode" jadi
 * bentuk seperti `_ZN3Foo3barEii`, dan ini yang menguraikannya balik jadi
 * `Foo::bar(int, int)` — jauh lebih enak dibaca daripada simbol mentahnya.
 *
 * PENTING soal keterbatasan: mangling Itanium ABI SENGAJA TIDAK menyimpan tipe
 * return function biasa (int/bool/void dst) — ini bukan keterbatasan demangler
 * ini, tapi memang begitu desain ABI-nya. C++ tidak bisa overload dua fungsi
 * yang cuma beda return type, jadi compiler gak perlu (dan gak pernah) encode
 * itu ke nama simbol. Return type CUMA bisa diketahui akurat dari debug info
 * (DWARF) yang butuh symbol table lain di luar cakupan tool ini. Yang BISA dan
 * SELALU akurat dari mangled name: nama namespace/class, nama fungsi, tipe tiap
 * parameter, apakah dia constructor/destructor/operator, dan qualifier
 * const/volatile.
 *
 * Implementasi ini adalah subset praktis dari spek Itanium ABI (cukup untuk
 * mayoritas simbol C++ nyata: namespace/class bertingkat, template, built-in
 * types, pointer/reference/const, dan tabel substitusi standar). Kalau ketemu
 * pola yang di luar cakupan, akan mundur teratur (fallback) ke nama mentahnya
 * — TIDAK PERNAH menampilkan hasil tebakan yang salah/ngasal.
 */
object CppDemangler {

    data class DemangleResult(
        val success: Boolean,
        val signature: String,       // hasil akhir siap tampil, contoh: "Foo::bar(int, int) const"
        val className: String?,      // "Namespace::Foo" kalau ada, null kalau fungsi bebas/C
        val functionName: String?,   // "bar" / "Foo" (ctor) / "~Foo" (dtor) / "operator+"
        val parameters: List<String> = emptyList(),
        val isConstructor: Boolean = false,
        val isDestructor: Boolean = false
    )

    private val BUILTIN = mapOf(
        'v' to "void", 'b' to "bool", 'c' to "char", 'a' to "signed char", 'h' to "unsigned char",
        's' to "short", 't' to "unsigned short", 'i' to "int", 'j' to "unsigned int",
        'l' to "long", 'm' to "unsigned long", 'x' to "long long", 'y' to "unsigned long long",
        'n' to "__int128", 'o' to "unsigned __int128", 'f' to "float", 'd' to "double",
        'e' to "long double", 'g' to "__float128", 'z' to "...", 'w' to "wchar_t"
    )

    private val STANDARD_SUBS = mapOf(
        "St" to "std", "Sa" to "std::allocator", "Sb" to "std::basic_string",
        "Ss" to "std::string", "Si" to "std::istream", "So" to "std::ostream",
        "Sd" to "std::iostream"
    )

    private val OPERATORS = mapOf(
        "nw" to "operator new", "na" to "operator new[]", "dl" to "operator delete",
        "da" to "operator delete[]", "pl" to "operator+", "mi" to "operator-",
        "ml" to "operator*", "dv" to "operator/", "rm" to "operator%",
        "eq" to "operator==", "ne" to "operator!=", "lt" to "operator<", "gt" to "operator>",
        "le" to "operator<=", "ge" to "operator>=", "aS" to "operator=", "pL" to "operator+=",
        "mI" to "operator-=", "ix" to "operator[]", "cl" to "operator()", "cv" to "operator cast"
    )

    /** Parser internal — jalan sekali per simbol, murah (biaya sebanding panjang nama, bukan ukuran file). */
    private class Parser(private val s: String) {
        var pos = 0
        val substitutions = ArrayList<String>()

        fun peek(): Char? = if (pos < s.length) s[pos] else null
        fun expect(c: Char) {
            if (peek() != c) throw IllegalStateException("Expected '$c' at $pos in $s")
            pos++
        }

        fun parseNumber(): Int {
            val start = pos
            while (pos < s.length && s[pos].isDigit()) pos++
            if (start == pos) throw IllegalStateException("Expected number at $pos")
            return s.substring(start, pos).toInt()
        }

        fun parseSourceName(): String {
            val len = parseNumber()
            if (pos + len > s.length) throw IllegalStateException("Source name overrun")
            val name = s.substring(pos, pos + len)
            pos += len
            return name
        }

        /** <substitution> ::= S_ | S <seq-id> _ | St | Sa | Sb | Ss | Si | So | Sd */
        fun parseSubstitution(): String {
            expect('S')
            val c = peek()
            if (c != null && c.isLetter()) {
                val code = "S$c"
                if (STANDARD_SUBS.containsKey(code)) {
                    pos++
                    return STANDARD_SUBS.getValue(code)
                }
            }
            if (peek() == '_') {
                pos++
                return substitutions.getOrElse(0) { throw IllegalStateException("Bad substitution S_") }
            }
            val start = pos
            while (pos < s.length && s[pos] != '_') pos++
            val seq = s.substring(start, pos)
            expect('_')
            val idx = (base36(seq)) + 1
            return substitutions.getOrElse(idx) { throw IllegalStateException("Bad substitution S${seq}_") }
        }

        private fun base36(str: String): Int {
            if (str.isEmpty()) return 0
            var result = 0
            for (ch in str) {
                val digit = if (ch.isDigit()) ch - '0' else (ch - 'A') + 10
                result = result * 36 + digit
            }
            return result
        }

        fun addSubstitution(candidate: String) {
            substitutions.add(candidate)
        }

        /** <template-args> ::= I <template-arg>+ E */
        fun parseTemplateArgs(): List<String> {
            expect('I')
            val args = ArrayList<String>()
            while (peek() != 'E') {
                args.add(parseType())
            }
            expect('E')
            return args
        }

        /** <unqualified-name>, dikembalikan sebagai pasangan (nama tampil, kode-mentah-untuk-ctor/dtor) */
        fun parseUnqualifiedName(): Pair<String, String?> {
            val c = peek() ?: throw IllegalStateException("Unexpected end")
            return when {
                c == 'C' -> { // constructor: C1, C2, C3
                    pos++
                    val variant = peek() ?: throw IllegalStateException("Bad ctor code")
                    pos++
                    "#CTOR#" to "C$variant"
                }
                c == 'D' && pos + 1 < s.length && s[pos + 1] in "012" -> { // destructor: D0, D1, D2
                    pos++
                    val variant = s[pos]; pos++
                    "#DTOR#" to "D$variant"
                }
                c.isLetter() && pos + 1 < s.length && s[pos + 1].isLowerCase() && OPERATORS.containsKey(s.substring(pos, pos + 2)) -> {
                    val code = s.substring(pos, pos + 2)
                    pos += 2
                    OPERATORS.getValue(code) to null
                }
                c.isDigit() -> {
                    val name = parseSourceName()
                    name to null
                }
                else -> throw IllegalStateException("Unrecognized unqualified-name at $pos: '${s.substring(pos)}'")
            }
        }

        /** <nested-name> ::= N [CV] <prefix> <unqualified-name> E  (disederhanakan) */
        fun parseNestedName(): Triple<List<String>, Boolean, Boolean> {
            expect('N')
            // CV-qualifiers untuk implicit-this (jarang relevan buat tampilan kita, tapi harus dilewati)
            while (peek() == 'r' || peek() == 'V' || peek() == 'K') pos++
            if (peek() == 'R' || peek() == 'O') pos++ // ref-qualifier (jarang)

            val parts = ArrayList<String>()
            var isCtor = false
            var isDtor = false
            var runningPrefix = ""

            while (true) {
                val c = peek() ?: throw IllegalStateException("Unexpected end in nested-name")
                val (display, ctorDtorCode) = when {
                    c == 'S' -> {
                        val sub = parseSubstitution()
                        sub to null
                    }
                    else -> parseUnqualifiedName()
                }

                var finalDisplay = display
                if (ctorDtorCode != null) {
                    val lastClassName = parts.lastOrNull() ?: "?"
                    if (ctorDtorCode.startsWith("C")) {
                        finalDisplay = lastClassName
                        isCtor = true
                    } else {
                        finalDisplay = "~$lastClassName"
                        isDtor = true
                    }
                }

                // <template-args> opsional nempel di component ini
                if (peek() == 'I') {
                    val args = parseTemplateArgs()
                    finalDisplay = "$finalDisplay<${args.joinToString(", ")}>"
                }

                parts.add(finalDisplay)
                runningPrefix = if (runningPrefix.isEmpty()) finalDisplay else "$runningPrefix::$finalDisplay"
                addSubstitution(runningPrefix)

                if (peek() == 'E') { pos++; break }
            }
            return Triple(parts, isCtor, isDtor)
        }

        /** <type> — built-in, pointer/reference/const, class/enum via name, atau substitusi. */
        fun parseType(): String {
            val c = peek() ?: throw IllegalStateException("Unexpected end parsing type")
            val startPos = pos
            val result = when {
                BUILTIN.containsKey(c) -> { pos++; BUILTIN.getValue(c) }
                c == 'P' -> { pos++; "${parseType()}*" }
                c == 'R' -> { pos++; "${parseType()}&" }
                c == 'O' -> { pos++; "${parseType()}&&" }
                c == 'K' -> { pos++; "${parseType()} const" }
                c == 'V' -> { pos++; "${parseType()} volatile" }
                c == 'r' -> { pos++; "${parseType()} restrict" }
                c == 'A' -> { // array: A<number>_<type>
                    pos++
                    val dimStart = pos
                    while (pos < s.length && s[pos] != '_') pos++
                    val dim = s.substring(dimStart, pos)
                    expect('_')
                    val elem = parseType()
                    "$elem[${dim}]"
                }
                c == 'F' -> { // function type: F <return-type> <param-types> E
                    pos++
                    val ret = parseType()
                    val params = ArrayList<String>()
                    while (peek() != 'E') params.add(parseType())
                    expect('E')
                    "$ret(${params.joinToString(", ")})"
                }
                c == 'N' -> {
                    val (parts, _, _) = parseNestedName()
                    parts.joinToString("::")
                }
                c == 'S' -> parseSubstitution()
                c.isDigit() -> {
                    val name = parseSourceName()
                    var display = name
                    if (peek() == 'I') {
                        val args = parseTemplateArgs()
                        display = "$display<${args.joinToString(", ")}>"
                    }
                    display
                }
                else -> throw IllegalStateException("Unrecognized type char '$c' at $pos")
            }
            // Simpan sebagai kandidat substitusi (kecuali built-in polos, sesuai spek)
            if (!BUILTIN.containsKey(s.getOrNull(startPos))) {
                addSubstitution(result)
            }
            return result
        }
    }

    fun demangle(rawName: String): DemangleResult {
        if (!rawName.startsWith("_Z")) {
            return DemangleResult(false, rawName, null, rawName)
        }
        return try {
            val p = Parser(rawName.substring(2))
            var className: String? = null
            var functionName: String
            var isCtor = false
            var isDtor = false

            if (p.peek() == 'N') {
                val (parts, ctor, dtor) = p.parseNestedName()
                isCtor = ctor
                isDtor = dtor
                functionName = parts.last()
                className = if (parts.size > 1) parts.dropLast(1).joinToString("::") else null
            } else {
                // unscoped-name / unscoped-template-name (fungsi bebas, bukan member)
                val (name, _) = p.parseUnqualifiedName()
                functionName = name
                if (p.peek() == 'I') {
                    val args = p.parseTemplateArgs()
                    functionName = "$functionName<${args.joinToString(", ")}>"
                }
                p.addSubstitution(functionName)
            }

            val params = ArrayList<String>()
            while (p.pos < rawName.length - 2 && p.peek() != null) {
                params.add(p.parseType())
            }
            val paramDisplay = if (params.size == 1 && params[0] == "void") emptyList() else params

            val qualifiedName = if (className != null) "$className::$functionName" else functionName
            val signature = "$qualifiedName(${paramDisplay.joinToString(", ")})"

            DemangleResult(
                success = true,
                signature = signature,
                className = className,
                functionName = functionName,
                parameters = paramDisplay,
                isConstructor = isCtor,
                isDestructor = isDtor
            )
        } catch (e: Exception) {
            // Gagal parsing (pola di luar cakupan subset ini) -> mundur ke nama mentah, JANGAN nebak.
            DemangleResult(false, rawName, null, rawName)
        }
    }
}
