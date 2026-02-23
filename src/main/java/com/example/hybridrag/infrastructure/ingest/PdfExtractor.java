package com.example.hybridrag.infrastructure.ingest;

import java.io.IOException;
import java.io.InputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class PdfExtractor {

    public String extractText(InputStream pdfStream) throws IOException {
        try (PDDocument document = PDDocument.load(pdfStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            if (text == null) {
                return "";
            }
            return text.replace("\u0000", "").trim();
        }
    }
}