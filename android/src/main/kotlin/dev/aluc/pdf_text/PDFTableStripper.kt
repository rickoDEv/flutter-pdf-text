package dev.aluc.pdf_text

/*
 * Copyright 2017 Beldaz (https://github.com/beldaz)
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.graphics.drawable.shapes.Shape
import com.tom_roush.harmony.awt.geom.AffineTransform
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.PDFTextStripperByArea
import com.tom_roush.pdfbox.text.TextPosition
import java.awt.Rectangle
import java.awt.geom.Rectangle2D
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.*


/**
 *
 * Class to extract tabular data from a PDF.
 * Works by making a first pass of the page to group all nearby text items
 * together, and then inferring a 2D grid from these regions. Each table cell
 * is then extracted using a PDFTextStripperByArea object.
 *
 * Works best when
 * headers are included in the detected region, to ensure representative text
 * in every column.
 *
 * Based upon DrawPrintTextLocations PDFBox example
 * (https://svn.apache.org/viewvc/pdfbox/trunk/examples/src/main/java/org/apache/pdfbox/examples/util/DrawPrintTextLocations.java)
 *
 * @author Beldaz
 */
class PDFTableStripper : PDFTextStripper() {
    /*
	 *  Used in methods derived from DrawPrintTextLocations
	 */
    private var flipAT: AffineTransform? = null
    private var rotateAT: AffineTransform? = null

    /**
     * Regions updated by calls to writeString
     */
    private var boxes: MutableSet<Rectangle2D>? = null

    // Border to allow when finding intersections
    private val dx = 1.0 // This value works for me, feel free to tweak (or add setter)
    private val dy = 0.000 // Rows of text tend to overlap, so need to extend

    /**
     * Region in which to find table (otherwise whole page)
     */
    private var regionArea: Rectangle2D? = null

    /**
     * Number of rows in inferred table
     */
    var rows = 0
        private set

    /**
     * Number of columns in inferred table
     */
    var columns = 0
        private set

    /**
     * This is the object that does the text extraction
     */
    private val regionStripper: PDFTextStripperByArea

    /**
     * 1D intervals - used for calculateTableRegions()
     * @author Beldaz
     */
    class Interval(var start: Double, var end: Double) {
        fun add(col: Interval) {
            if (col.start < start) start = col.start
            if (col.end > end) end = col.end
        }

        companion object {
            fun addTo(x: Interval, columns: LinkedList<Interval>) {
                var p = 0
                val it = columns.iterator()
                // Find where x should go
                while (it.hasNext()) {
                    val col = it.next()
                    if (x.end >= col.start) {
                        if (x.start <= col.end) { // overlaps
                            x.add(col)
                            it.remove()
                        }
                        break
                    }
                    ++p
                }
                while (it.hasNext()) {
                    val col = it.next()
                    if (x.start > col.end) break
                    x.add(col)
                    it.remove()
                }
                columns.add(p, x)
            }
        }
    }

    /**
     * Instantiate a new PDFTableStripper object.
     *
     * @param document
     * @throws IOException If there is an error loading the properties.
     */
    init {
        super.setShouldSeparateByBeads(false)
        regionStripper = PDFTextStripperByArea()
        regionStripper.sortByPosition = true
    }

    /**
     * Define the region to group text by.
     *
     * @param rect The rectangle area to retrieve the text from.
     */
    fun setRegion(rect: Rectangle2D?) {
        regionArea = rect
    }

    /**
     * Get the text for the region, this should be called after extractTable().
     *
     * @return The text that was identified in that region.
     */
    fun getText(row: Int, col: Int): String {
        return regionStripper.getTextForRegion("el" + col + "x" + row)
    }

    @Throws(IOException::class)
    fun extractTable(pdPage: PDPage) {
        startPage = currentPageNo
        endPage = currentPageNo
        boxes = HashSet<Rectangle2D>()
        // flip y-axis
        flipAT = AffineTransform()
        flipAT!!.translate(0.0, pdPage.bBox.height.toDouble())
        flipAT!!.scale(1.0, -1.0)

        // page may be rotated
        rotateAT = AffineTransform()
        val rotation = pdPage.rotation
        if (rotation != 0) {
            val mediaBox = pdPage.mediaBox
            when (rotation) {
                90 -> rotateAT!!.translate(mediaBox.height.toDouble(), 0.0)
                270 -> rotateAT!!.translate(0.0, mediaBox.width.toDouble())
                180 -> rotateAT!!.translate(mediaBox.width.toDouble(), mediaBox.height.toDouble())
                else -> {}
            }
            rotateAT!!.rotate(Math.toRadians(rotation.toDouble()))
        }
        OutputStreamWriter(ByteArrayOutputStream()).use { dummy ->
            super.output = dummy
            super.processPage(pdPage)
        }
        val regions: Array<Array<Rectangle2D?>> = calculateTableRegions()

//        System.err.println("Drawing " + nCols + "x" + nRows + "="+ nRows*nCols + " regions");
        for (i in 0 until columns) {
            for (j in 0 until rows) {
                val region: Rectangle2D? = regions[i][j]
                regionStripper.addRegion("el" + i + "x" + j, region)
            }
        }
        regionStripper.extractRegions(pdPage)
    }

    /**
     * Infer a rectangular grid of regions from the boxes field.
     *
     * @return 2D array of table regions (as Rectangle2D objects). Note that
     * some of these regions may have no content.
     */
    private fun calculateTableRegions(): Array<Array<Rectangle2D?>> {

        // Build up a list of all table regions, based upon the populated
        // regions of boxes field. Treats the horizontal and vertical extents
        // of each box as distinct
        val columns = LinkedList<Interval>()
        val rows = LinkedList<Interval>()
        for (box in boxes!!) {
            val x = Interval(box.getMinX(), box.getMaxX())
            val y = Interval(box.getMinY(), box.getMaxY())
            Interval.addTo(x, columns)
            Interval.addTo(y, rows)
        }
        this.rows = rows.size
        this.columns = columns.size
        val regions: Array<Array<Rectangle2D?>> = Array<Array<Rectangle2D?>>(
            this.columns
        ) { arrayOfNulls<Rectangle2D>(this.rows) }
        var i = 0
        // Label regions from top left, rather than the transformed orientation
        for (column in columns) {
            var j = 0
            for (row in rows) {
                regions[this.columns - i - 1][this.rows - j - 1] =
                    Double(column.start, row.start, column.end - column.start, row.end - row.start)
                ++j
            }
            ++i
        }
        return regions
    }

    /**
     * Register each character's bounding box, updating boxes field to maintain
     * a list of all distinct groups of characters.
     *
     * Overrides the default functionality of PDFTextStripper.
     * Most of this is taken from DrawPrintTextLocations.java, with extra steps
     * at end of main loop
     */
    @Throws(IOException::class)
    override fun writeString(string: String, textPositions: List<TextPosition>) {
        for (text in textPositions) {
            // glyph space -> user space
            // note: text.getTextMatrix() is *not* the Text Matrix, it's the Text Rendering Matrix
            val at = text.textMatrix.createAffineTransform()
            val font = text.font
            val bbox = font.boundingBox

            // advance width, bbox height (glyph space)
            val xadvance = font.getWidth(text.characterCodes[0]) // todo: should iterate all chars
            val rect = Float(0, bbox.lowerLeftY, xadvance, bbox.height)
            if (font is PDType3Font) {
                // bbox and font matrix are unscaled
                at.concatenate(font.getFontMatrix().createAffineTransform())
            } else {
                // bbox and font matrix are already scaled to 1000
                at.scale((1 / 1000f).toDouble(), (1 / 1000f).toDouble())
            }
            var s: Shape = at.createTransformedShape(rect)
            s = flipAT.createTransformedShape(s)
            s = rotateAT.createTransformedShape(s)


            //
            // Merge character's bounding box with boxes field
            //
            val bounds: Rectangle2D = s.getBounds2D()
            // Pad sides to detect almost touching boxes
            val hitbox: Rectangle2D = bounds.getBounds2D()
            hitbox.add(bounds.getMinX() - dx, bounds.getMinY() - dy)
            hitbox.add(bounds.getMaxX() + dx, bounds.getMaxY() + dy)

            // Find all overlapping boxes
            val intersectList: MutableList<Rectangle2D> = ArrayList<Rectangle2D>()
            for (box in boxes!!) {
                if (box.intersects(hitbox)) {
                    intersectList.add(box)
                }
            }

            // Combine all touching boxes and update
            // (NOTE: Potentially this could leave some overlapping boxes un-merged,
            // but it's sufficient for now and get's fixed up in calculateTableRegions)
            for (box in intersectList) {
                bounds.add(box)
                boxes!!.remove(box)
            }
            boxes!!.add(bounds)
        }
    }

    /**
     * This method does nothing in this derived class, because beads and regions are incompatible. Beads are
     * ignored when stripping by area.
     *
     * @param aShouldSeparateByBeads The new grouping of beads.
     */
    override fun setShouldSeparateByBeads(aShouldSeparateByBeads: Boolean) {}

    /**
     * Adapted from PDFTextStripperByArea
     * {@inheritDoc}
     */
    override fun processTextPosition(text: TextPosition) {
        if (regionArea != null && !regionArea.contains(text.x, text.y)) {
            // skip character
        } else {
            super.processTextPosition(text)
        }
    }

    companion object {
        /**
         * This will print the documents data, for each table cell.
         *
         * @param args The command line arguments.
         *
         * @throws IOException If there is an error parsing the document.
         */
        @Throws(IOException::class)
        @JvmStatic
        fun main(args: Array<String>) {
            PDDocument.load(File(args[0])).use { document ->
                val res = 72.0 // PDF units are at 72 DPI
                val stripper = PDFTableStripper()
                stripper.sortByPosition = true

                // Choose a region in which to extract a table (here a 6"wide, 9" high rectangle offset 1" from top left of page)
                stripper.setRegion(
                    Rectangle(
                        Math.round(1.0 * res).toInt(),
                        Math.round(1 * res).toInt(),
                        Math.round(6 * res).toInt(),
                        Math.round(9.0 * res).toInt()
                    )
                )

                // Repeat for each page of PDF
                for (page in 0 until document.numberOfPages) {
                    println("Page $page")
                    val pdPage = document.getPage(page)
                    stripper.extractTable(pdPage)
                    for (c in 0 until stripper.columns) {
                        println("Column $c")
                        for (r in 0 until stripper.rows) {
                            println("Row $r")
                            println(stripper.getText(r, c))
                        }
                    }
                }
            }
        }
    }
}