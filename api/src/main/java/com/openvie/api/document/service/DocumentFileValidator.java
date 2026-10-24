// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.openvie.api.document.service;

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

import com.openvie.api.common.exception.custom.BadRequestException;
import com.openvie.api.document.enums.DocumentType;

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
            case IMAGE -> validateImage(data);
            case AUDIO -> validateAudio(data);
            case VIDEO -> validateVideo(data);
        }
    }

    private static void validateImage(byte[] data) {
        if (data.length < 12) {
            throw new BadRequestException("Image file signature is invalid");
        }
        boolean png = (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N'
                && data[3] == 'G' && data[4] == '\r' && data[5] == '\n'
                && data[6] == 0x1A && data[7] == '\n';
        boolean jpeg = (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8
                && (data[2] & 0xFF) == 0xFF;
        boolean webp = data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
        if (!png && !jpeg && !webp) {
            throw new BadRequestException("Image file signature is invalid");
        }
    }

    private static void validateAudio(byte[] data) {
        if (data.length < 4) {
            throw new BadRequestException("Audio file signature is invalid");
        }
        boolean mp3Id3 = data[0] == 'I' && data[1] == 'D' && data[2] == '3';
        int b0 = data[0] & 0xFF;
        int b1 = data[1] & 0xFF;
        boolean mp3Sync = b0 == 0xFF && (b1 == 0xFB || b1 == 0xF3 || b1 == 0xF2);
        boolean wav = data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E';
        boolean flac = data[0] == 'f' && data[1] == 'L' && data[2] == 'a' && data[3] == 'C';
        boolean ogg = data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S';
        boolean ftyp = data.length >= 8 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p';
        if (!mp3Id3 && !mp3Sync && !wav && !flac && !ogg && !ftyp) {
            throw new BadRequestException("Audio file signature is invalid");
        }
    }

    private static void validateVideo(byte[] data) {
        if (data.length < 4) {
            throw new BadRequestException("Video file signature is invalid");
        }
        boolean ebml = (data[0] & 0xFF) == 0x1A && (data[1] & 0xFF) == 0x45
                && (data[2] & 0xFF) == 0xDF && (data[3] & 0xFF) == 0xA3;
        boolean ftyp = data.length >= 8 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p';
        if (!ebml && !ftyp) {
            throw new BadRequestException("Video file signature is invalid");
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
        values.put(DocumentType.IMAGE, Set.of("image/png", "image/jpeg", "image/webp",
                "application/octet-stream"));
        values.put(DocumentType.AUDIO, Set.of(
                "audio/mpeg",
                "audio/wav",
                "audio/x-wav",
                "audio/mp4",
                "audio/x-m4a",
                "audio/ogg",
                "audio/vorbis",
                "audio/opus",
                "audio/flac",
                "audio/x-flac",
                "application/octet-stream"));
        values.put(DocumentType.VIDEO, Set.of(
                "video/mp4",
                "video/x-matroska",
                "video/quicktime",
                "video/webm",
                "application/octet-stream"));
        return values;
    }
}
