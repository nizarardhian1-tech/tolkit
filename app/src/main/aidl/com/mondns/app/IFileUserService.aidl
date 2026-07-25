// Interface ini jalan di proses SHELL (uid 2000) lewat Shizuku, jadi bisa
// baca/tulis/hapus folder yang biasanya diblokir Scoped Storage (Android/data,
// Android/obb milik app lain) tanpa root, di semua Android 11+.
//
// Catatan: AIDL mewajibkan SEMUA method dikasih transaction id secara eksplisit
// kalau ada SATU SAJA yang dikasih id (aturan "all or none"). Method destroy()
// harus pakai 16777114 (konvensi resmi Shizuku), jadi method lain juga dikasih
// id manual, sengaja dibuat kecil dan berurutan supaya jauh dari kode itu.
package com.mondns.app;

interface IFileUserService {
    boolean exists(String path) = 1;
    boolean isDirectory(String path) = 2;
    boolean isFile(String path) = 3;
    long length(String path) = 4;
    long lastModified(String path) = 5;
    String[] list(String path) = 6;

    boolean mkdirs(String path) = 7;
    boolean delete(String path) = 8;
    boolean copy(String fromPath, String toPath) = 9;
    boolean move(String fromPath, String toPath) = 10;
    boolean rename(String fromPath, String toPath) = 11;

    // Wajib ada: cara resmi Shizuku buat matikan proses UserService (non-daemon)
    // dengan bersih. Kode 16777114 adalah transaction code khusus Shizuku.
    void destroy() = 16777114;
}
