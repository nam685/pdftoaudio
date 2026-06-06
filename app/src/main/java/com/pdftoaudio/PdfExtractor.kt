package com.pdftoaudio

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

data class PdfResult(val title: String, val text: String, val pageCount: Int)

object PdfExtractor {

    fun extract(context: Context, uri: Uri, startPage: Int, endPage: Int): PdfResult {
        PDFBoxResourceLoader.init(context.applicationContext)
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open file")
        stream.use { input ->
            val doc = PDDocument.load(input)
            doc.use { d ->
                val pageCount = d.numberOfPages
                val start = startPage.coerceIn(1, pageCount)
                val end = endPage.coerceIn(start, pageCount)
                val stripper = PDFTextStripper().apply {
                    this.startPage = start
                    this.endPage = end
                    addMoreFormatting = true
                }
                val text = stripper.getText(d)
                val title = d.documentInformation?.title
                    ?.takeIf { it.isNotBlank() }
                    ?: uri.lastPathSegment?.removeSuffix(".pdf")
                    ?: "PDF"
                return PdfResult(title, text, pageCount)
            }
        }
    }
}
