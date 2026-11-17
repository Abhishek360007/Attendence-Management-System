package com.abhishek.attendance.web;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Minimal CSV writer: every cell quoted; a leading quote stops spreadsheets running text as a formula. */
public final class Csv {

    public static String of(List<String> header, List<List<String>> rows) {
        return Stream.concat(Stream.of(header), rows.stream())
                .map(r -> r.stream().map(Csv::cell).collect(Collectors.joining(",")))
                .collect(Collectors.joining("\r\n"));
    }

    public static String cell(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private Csv() {
    }
}
