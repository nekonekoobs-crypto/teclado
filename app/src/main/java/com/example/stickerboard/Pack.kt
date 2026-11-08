package com.example.stickerboard

import java.io.File

data class Pack(val name: String, val dir: File, val stickers: List<Sticker>)
