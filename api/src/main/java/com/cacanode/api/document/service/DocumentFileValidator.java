package com.cacanode.api.document.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.web.multipart.MultipartFile;

import com.cacanode.api.common.exception.custom.BadRequestException;
import com.cacanode.api.document.enums.DocumentType;

final class DocumentFileValidator {

    private static final int MAX_ARCHIVE_ENTRIES = 10_000;
    private static final long MAX_ARCHIVE_UNCOMPRESSED_BYTES = 200L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 8192;
    private static final Map<DocumentType, Set<String>> MIME_TYPES = mimeTypes();

    private DocumentFileValidator() {
    }

    static void validate(MultipartFile file, DocumentType type) {
        String contentType = normalizeMime(file.getContentType());
        if (!MIME_TYPES.get(type).contains(contentType)) {
            throw new BadRequestException("File extension and content type do not match");
        }

        final byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("Uploaded file could not be read");
        }

        switch (type) {
            case PDF -> validatePdf(data);
            case DOCX -> validateOfficeArchive(data, "word/document.xml");
            case XLSX -> validateOfficeArchive(data, "xl/workbook.xml");
            case TXT, MARKDOWN, HTML, CSV -> validateUtf8(data);
        }
    }

    private static void validatePdf(byte[] data) {
        if (data.length < 5 || data[0] != '%' || data[1] != 'P' || data[2] != 'D'
                || data[3] != 'F' || data[4] != '-') {
            throw new BadRequestException("PDF file signature is invalid");
        }
        String prefix = new String(data, 0, Math.min(data.length, 4096), StandardCharsets.ISO_8859_1);
        if (prefix.contains("/Encrypt")) {
            throw new BadRequestException("Encrypted PDF files are not supported");
        }
    }

    private static void validateOfficeArchive(byte[] data, String requiredEntry) {
        if (data.length < 4 || data[0] != 'P' || data[1] != 'K') {
            throw new BadRequestException("Office file container is invalid");
        }
        Set<String> entries = new HashSet<>();
        long totalBytes = 0;
        int entryCount = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new BadRequestException("Office archive contains too many entries");
                }
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.equals("..")) {
                    throw new BadRequestException("Office archive contains an unsafe path");
                }
                entries.add(name);
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    totalBytes += read;
                    if (totalBytes > MAX_ARCHIVE_UNCOMPRESSED_BYTES) {
                        throw new BadRequestException("Office archive expands beyond the safety limit");
                    }
                }
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (IOException e) {
            throw new BadRequestException("Office file container is malformed");
        }
        if (!entries.contains("[Content_Types].xml") || !entries.contains(requiredEntry)) {
            throw new BadRequestException("Office file container does not match its extension");
        }
    }

    private static void validateUtf8(byte[] data) {
        int offset = data.length >= 3 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB
                && data[2] == (byte) 0xBF ? 3 : 0;
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, data.length - offset));
        } catch (CharacterCodingException e) {
            throw new BadRequestException("Text file must use UTF-8 encoding");
        }
        for (int index = offset; index < data.length; index++) {
            if (data[index] == 0) {
                throw new BadRequestException("Text file contains invalid binary content");
            }
        }
    }

    private static String normalizeMime(String contentType) {
        if (contentType == null) {
            return "";
        }
        return contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
    }

    private static Map<DocumentType, Set<String>> mimeTypes() {
        Map<DocumentType, Set<String>> values = new EnumMap<>(DocumentType.class);
        values.put(DocumentType.PDF, Set.of("application/pdf", "application/octet-stream"));
        values.put(DocumentType.DOCX, Set.of(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/zip", "application/octet-stream"));
        values.put(DocumentType.XLSX, Set.of(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/zip", "application/octet-stream"));
        values.put(DocumentType.TXT, Set.of("text/plain", "application/octet-stream"));
        values.put(DocumentType.MARKDOWN, Set.of("text/markdown", "text/plain", "text/x-markdown",
                "application/octet-stream"));
        values.put(DocumentType.HTML, Set.of("text/html", "application/xhtml+xml",
                "application/octet-stream"));
        values.put(DocumentType.CSV, Set.of("text/csv", "application/csv", "text/plain",
                "application/vnd.ms-excel", "application/octet-stream"));
        return values;
    }
}
