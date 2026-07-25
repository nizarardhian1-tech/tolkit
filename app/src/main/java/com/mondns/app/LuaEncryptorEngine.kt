package com.mondns.app

import org.luaj.vm2.compiler.DumpState
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

object LuaEncryptorEngine {

    class EncryptionException(message: String) : Exception(message)

    fun encrypt(
        inputFile: File,
        outputDir: File,
        outputName: String,
        addExpiry: Boolean,
        expiryDate: String, // YYYY-MM-DD
        addAntiLog: Boolean,
        obfuscateStrings: Boolean,
        compileToBytecode: Boolean,
        corruptHeader: Boolean, // Tetap ada di parameter dari UI
        onProgress: (Int, String) -> Unit
    ): File {
        onProgress(10, "Membaca file asli...")
        var scriptData = inputFile.readText(Charsets.UTF_8)

        // 1. OBFUSCATE STRINGS (Dynamic Key Generation)
        if (obfuscateStrings) {
            onProgress(30, "Mengacak strings (Dynamic Key)...")
            scriptData = applyDynamicStringObfuscation(scriptData)
        }

        // 2. ADD EXPIRY DATE
        if (addExpiry && expiryDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            onProgress(40, "Menyisipkan expired date...")
            scriptData = injectExpiryDate(scriptData, expiryDate)
        }

        // 3. ADD ANTI-LOG & JUNK CODE
        if (addAntiLog) {
            onProgress(50, "Menyisipkan Anti-Log & Decoys...")
            scriptData = injectAntiLog(scriptData)
        }

        // 4. WRAPPER & COMPILE TO BYTECODE
        var finalBytes: ByteArray
        if (compileToBytecode) {
            onProgress(70, "Kompilasi ke Bytecode (Stripped)...")
            val wrapped = """
                collectgarbage("collect")
                local _ = "\n━━━━━━━━━━━━━━━━━━━━\n Protected by MonToolkit\n━━━━━━━━━━━━━━━━━━━━\n"
                local function __MonToolkit_Init()
                $scriptData
                end
                local ___ = __MonToolkit_Init()
            """.trimIndent()

            try {
                val globals = JsePlatform.standardGlobals()
                val chunk = globals.load(wrapped, "MonToolkit")
                val outStream = ByteArrayOutputStream()
                
                // true = strip debug info (Hapus nama variabel lokal, nama fungsi & nomor baris).
                // Ini JAUH lebih aman dan lebih memusingkan decompiler dibanding byte corruption manual.
                DumpState.dump(chunk.checkclosure().p, outStream, true)
                finalBytes = outStream.toByteArray()
            } catch (e: Exception) {
                throw EncryptionException("Syntax error di script Lua! Gagal kompilasi: ${e.message}")
            }
        } else {
            finalBytes = scriptData.toByteArray(Charsets.UTF_8)
        }

        onProgress(95, "Menyimpan file hasil...")
        if (!outputDir.exists()) outputDir.mkdirs()
        
        val ext = if (compileToBytecode) ".lua" else "._enc.lua"
        val outFile = File(outputDir, outputName.removeSuffix(".lua") + ext)
        outFile.writeBytes(finalBytes)

        onProgress(100, "Selesai!")
        return outFile
    }

    private fun applyDynamicStringObfuscation(data: String): String {
        // Generate Random Keys khusus untuk sesi ini!
        val addKey = Random.nextInt(10, 200)
        val multKey = Random.nextInt(2, 9)
        val decryptFuncName = "Dec_" + Random.nextInt(10000, 99999)

        // Bikin fungsi dekripsi dinamis yang ditanam ke dalam file
        val decryptorCode = """
            local function $decryptFuncName(t)
                local res = {}
                for i = 1, #t do
                    local v = t[i]
                    local charCode = (v - $addKey - (i * $multKey)) % 256
                    if charCode < 0 then charCode = charCode + 256 end
                    res[i] = string.char(charCode)
                end
                return table.concat(res)
            end
            
        """.trimIndent()

        // Regex aman untuk string literal Lua (Double & Single Quotes dengan escape)
        val strRegex = Regex("\"(?:\\\\.|[^\\\\\"])*\"|'(?:\\\\.|[^\\\\'])*'")
        
        // PENGAMAN: Hindari mengganti string di dalam require(), karena bisa bikin modul gagal diload
        val requireRegex = Regex("require\\s*\\(\\s*[\"'].*?[\"']\\s*\\)|require\\s+[\"'].*?[\"']")
        val requireMap = mutableMapOf<String, String>()
        var tempData = data
        var reqIndex = 0
        
        tempData = requireRegex.replace(tempData) { match ->
            val placeholder = "___REQ_PLACEHOLDER_${reqIndex++}___"
            requireMap[placeholder] = match.value
            placeholder
        }

        // Ganti gg.getRangesList ke alias agar obfuscator internal GG gak error
        tempData = tempData.replace("gg.getRangesList", "ggetRngesList")

        // Lakukan Obfuscation String
        tempData = strRegex.replace(tempData) { match ->
            val rawString = match.value
            // Hapus quote pembuka dan penutup
            val innerStr = if (rawString.startsWith("\"") || rawString.startsWith("'")) {
                rawString.substring(1, rawString.length - 1)
            } else rawString

            // Parse karakter escape secara manual (misal \n, \t, \\, \")
            val unescaped = unescapeLuaString(innerStr)

            // Encrypt byte per byte berdasarkan rumus random kita
            val bytes = unescaped.toByteArray(Charsets.UTF_8)
            val encodedArray = mutableListOf<Int>()
            for (i in bytes.indices) {
                val luaIndex = i + 1 // Lua selalu mulai dari index 1
                var v = (bytes[i].toInt() + addKey + (luaIndex * multKey)) % 256
                if (v < 0) v += 256
                encodedArray.add(v)
            }

            // Gantikan string asli dengan panggilan fungsi Dekripsi Acak kita
            "$decryptFuncName({${encodedArray.joinToString(",")}})"
        }

        // Kembalikan gg.getRangesList dan modul require
        tempData = tempData.replace("ggetRngesList", "gg.getRangesList")
        for ((placeholder, originalRequire) in requireMap) {
            tempData = tempData.replace(placeholder, originalRequire)
        }

        return decryptorCode + tempData
    }

    private fun unescapeLuaString(str: String): String {
        val sb = java.lang.StringBuilder()
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (c == '\\' && i + 1 < str.length) {
                val next = str[i + 1]
                when (next) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    '\'' -> sb.append('\'')
                    else -> sb.append(next) 
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun injectExpiryDate(data: String, dateStr: String): String {
        try {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd")
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val date = format.parse(dateStr)
            val ts = (date?.time ?: 0) / 1000L

            val eA = ts / 100000
            val eB = ts % 100000

            val expiryCode = """
                do
                local _eA=$eA
                local _eB=$eB
                local _eX=_eA*100000+_eB
                if os.time()>_eX then
                gg.alert("Script Expired!\nProtected by MonToolkit")
                os.exit()
                end
                end
                
            """.trimIndent()

            return expiryCode + data
        } catch (e: Exception) {
            return data
        }
    }

    private fun injectAntiLog(data: String): String {
        val decoys = StringBuilder()
        val fnames = listOf("MemFix", "BypassAnticheat", "WritePointer", "ReadEntity", "HookGG", "OffsetScanner", "ClearLog")
        
        // Inject fake functions with complex dummy math
        for (i in 1..40) {
            val offset = String.format("0x%08X", Random.nextInt(0x04000000, 0x7FFFFFFF))
            val editVal = Random.nextInt(1, 9999)
            val funcName = "${fnames.random()}_${Random.nextInt(1000, 9999)}"
            decoys.append("local function $funcName() if not gg then local _bA=$offset gg.setRanges(131104) gg.searchNumber('1',4) gg.editAll('$editVal',4) gg.clearResults() else local _x = math.sin($editVal) + math.cos($editVal) end end\n")
        }

        // Big Log & function hooking (Aman di dalam pcall agar gak force close)
        val bigLog = """
            local C=string.rep(" MonToolkit ", 1500)
            local Check={}
            for i= 1, 300 do Check[i]=C end 
            for A, B in pairs({gg.alert,gg.bytes,gg.copyText,gg.searchAddress,gg.searchNumber,gg.toast}) do pcall(B,Check) end
            
            local Spam = string.char(239,191,189):rep(40)
            for i = 1,3000 do pcall(function() debug.getinfo(i,nil,Spam) end) end
            
            local hook = gg.searchNumber 
            local hook2  = gg.editAll 
            gg.editAll = function(...) hook2(...) end 
            gg.searchNumber = function(...) hook(...) end
            
        """.trimIndent()

        return decoys.toString() + bigLog + data
    }
}