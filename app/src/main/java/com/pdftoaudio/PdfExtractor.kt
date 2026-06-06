package com.pdftoaudio

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

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

    // --- Cache (preprocessed text stored as a flat .txt in app-private storage) ---

    private fun cacheFile(context: Context, uri: Uri): File {
        val hash = uri.toString().hashCode().toUInt().toString(16)
        return File(context.filesDir, "$hash.txt")
    }

    fun hasCached(context: Context, uri: Uri): Boolean = cacheFile(context, uri).exists()

    fun loadCached(context: Context, uri: Uri): String? =
        cacheFile(context, uri).takeIf { it.exists() }?.readText()

    fun saveCached(context: Context, uri: Uri, text: String) =
        cacheFile(context, uri).writeText(text)

    fun deleteCached(context: Context, uri: Uri) {
        cacheFile(context, uri).delete()
    }

    fun cachedSizeKb(context: Context, uri: Uri): Long =
        cacheFile(context, uri).length() / 1024
}
