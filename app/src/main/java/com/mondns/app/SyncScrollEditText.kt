package com.mondns.app

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * EditText biasa tidak punya cara gampang buat "dengar" perubahan scroll dari luar
 * (misal buat sinkronisasi gutter nomor baris di sampingnya). Class ini cuma nambahin
 * satu callback [onScrollChangedListener] yang dipanggil setiap kali user scroll teks
 * di editor, supaya nomor baris di sebelahnya bisa ikut discroll dengan posisi yang sama.
 */
class SyncScrollEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs) {

    var onScrollChangedListener: ((scrollY: Int) -> Unit)? = null

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        onScrollChangedListener?.invoke(vert)
    }
}
