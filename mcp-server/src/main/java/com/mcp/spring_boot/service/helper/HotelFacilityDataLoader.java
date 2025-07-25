package com.mcp.spring_boot.service.helper;

import jakarta.annotation.PostConstruct;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class HotelFacilityDataLoader {

    /* ---------- Sabitler ---------- */
    private static final Logger LOG = LoggerFactory.getLogger(HotelFacilityDataLoader.class);
    private static final String HOTELS_FILE     = "hotels_with_facilities.xlsx";
    private static final String FACILITIES_FILE = "facilities.xlsx";

    private static final Pattern DIGITS = Pattern.compile("\\d+");      // sadece rakamları çek
    private static final DataFormatter FMT = new DataFormatter(Locale.US);

    /* ---------- Veri yapıları ---------- */
    // hotelCode  -> [facilityId, …]
    private final Map<String, List<Long>> hotelFacilities = new HashMap<>();

    // facilityId -> facilityName
    private final Map<Long, String> facilityNames = new HashMap<>();

    /* ---------- Yükleme ---------- */
    @PostConstruct
    public void loadData() {
        loadFacilities();
        loadHotels();
        LOG.info("✅  Loaded {} facilities, {} hotels.", facilityNames.size(), hotelFacilities.size());
    }

    /* ---------- Yardımcı metotlar ---------- */
    private static String norm(String s) {                       // kodları küçük-harf / trim
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String cellStr(Row row, int col) {            // null-güvenli hücre okuma
        Cell c = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (c == null) return null;
        String v = FMT.formatCellValue(c);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /* ---------- facilities.xlsx ---------- */
    private void loadFacilities() {
        try (InputStream is = new ClassPathResource(FACILITIES_FILE).getInputStream();
             Workbook wb   = new XSSFWorkbook(is)) {

            Sheet sh = wb.getSheetAt(0);
            for (Row row : sh) {
                if (row.getRowNum() == 0) continue;                 // başlık

                String idStr = cellStr(row, 0);
                String name  = cellStr(row, 1);
                if (idStr == null || name == null) continue;

                try {
                    long id = Long.parseLong(idStr.replace(".0", ""));
                    facilityNames.put(id, name);
                } catch (NumberFormatException ex) {
                    LOG.warn("⚠️  Bad facility id '{}' at row {}", idStr, row.getRowNum());
                }
            }
        } catch (Exception e) {
            LOG.error("❌  Failed to load {}", FACILITIES_FILE, e);
        }
    }

    /* ---------- hotels_with_facilities.xlsx ---------- */
    private void loadHotels() {
        try (InputStream is = new ClassPathResource(HOTELS_FILE).getInputStream();
             Workbook wb   = new XSSFWorkbook(is)) {

            Sheet sh = wb.getSheetAt(0);
            int skippedIds = 0;

            for (Row row : sh) {
                if (row.getRowNum() == 0) continue;                 // başlık

                String code = cellStr(row, 0);                      // hotelId
                String facs = cellStr(row, 2);                      // facilities
                if (code == null) continue;

                List<Long> ids = parseFacilityIds(facs);
                skippedIds += facs == null ? 0 : countBadIds(facs) - ids.size();
                hotelFacilities.put(norm(code), ids);
            }

            if (skippedIds > 0) {
                LOG.warn("⚠️  Skipped {} malformed facility IDs while loading {}", skippedIds, HOTELS_FILE);
            }
        } catch (Exception e) {
            LOG.error("❌  Failed to load {}", HOTELS_FILE, e);
        }
    }

    /* ---------- ID ayrıştırma ---------- */
    private static List<Long> parseFacilityIds(String raw) {
        if (raw == null) return List.of();
        return DIGITS.matcher(raw)
                     .results()
                     .map(m -> Long.valueOf(m.group()))
                     .toList();   // Java 21
    }

    private static int countBadIds(String raw) {
        return Math.max(0, raw.split(",").length - DIGITS.matcher(raw).results().toList().size());
    }

    /* ---------- Dış API ---------- */
    public List<String> getFacilityNamesForHotel(String hotelCode) {
        if (hotelCode == null) return List.of();

        List<Long> ids = hotelFacilities.get(norm(hotelCode));
        if (ids == null || ids.isEmpty()) return List.of();

        List<String> names = new ArrayList<>(ids.size());
        for (Long id : ids) {
            String n = facilityNames.get(id);
            if (n != null) names.add(n);
        }
        return names;
    }

    /* ---------- (Opsiyonel) Benzer kod bulma ---------- */
    public String findClosestHotelCode(String input) {
        String normIn = norm(input);
        return hotelFacilities.keySet().stream()
                .min(Comparator.comparingInt(k -> levenshtein(normIn, k)))
                .orElse("no similar code");
    }

    /** Mini Levenshtein – Apache commons istemeyen hafif sürüm */
    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur  = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }
}
