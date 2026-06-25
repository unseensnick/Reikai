package exh.source

import tachiyomi.domain.manga.model.Manga

// RK: true for galleries from a built-in E-Hentai / ExHentai source (favorites backup gating).
fun Manga.isEhBasedManga(): Boolean = source in eHentaiSourceIds
