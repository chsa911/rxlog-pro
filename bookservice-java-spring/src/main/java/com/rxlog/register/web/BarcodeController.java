package com.rxlog.register.web;

import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/barcodes")
@RequiredArgsConstructor
public class BarcodeController {
  private final JdbcTemplate jdbc;

  private static BigDecimal cm(Object v, String f) {
    if (v == null) throw new IllegalArgumentException(f + " is required");
    return new BigDecimal(v.toString().trim().replace(',', '.'));
  }

  private String q1(String sql, Object... a) {
    return jdbc.query(sql, rs -> rs.next() ? rs.getString(1) : null, a);
  }

  @PostMapping("/assignForDimensions")
  public ResponseEntity<?> assign(@RequestBody Map<String, Object> body) {
    try {
      var w = cm(body.get("widthCm"), "widthCm");
      var h = cm(body.get("heightCm"), "heightCm");
      String rE = q1("select match_color_code_with_stock_cm(?, ?, 'e')", w, h);
      String rL = q1("select match_color_code_with_stock_cm(?, ?, 'l')", w, h);
      String rO = q1("select match_color_code_with_stock_cm(?, ?, 'o')", w, h);
      if (rE == null && rL == null && rO == null)
        return ResponseEntity.unprocessableEntity()
            .body(Map.of("type", "NO_RULE_APPLIES", "widthCm", w, "heightCm", h));
      String code = q1("select assign_barcode_for_dimensions_auto_cm(?,?)", w, h);
      if (code == null) {
        String rule = rE != null ? rE : (rL != null ? rL : rO);
        return ResponseEntity.status(409).body(Map.of("type", "NO_STOCK", "rule", rule));
      }
      return ResponseEntity.ok(Map.of("barcode", code));
    } catch (org.springframework.dao.DataAccessResourceFailureException e) {
      return ResponseEntity.status(503).body(Map.of("type", "DB_UNAVAILABLE"));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest()
          .body(Map.of("type", "BAD_REQUEST", "message", e.getMessage()));
    } catch (Exception e) {
      return ResponseEntity.status(500).body(Map.of("type", "INTERNAL", "message", e.getMessage()));
    }
  }

  @PostMapping("/release")
  public ResponseEntity<?> release(@RequestBody Map<String, Object> body) {
    String code = body.get("code") == null ? null : String.valueOf(body.get("code")).trim();
    if (code == null || code.isBlank())
      return ResponseEntity.badRequest()
          .body(Map.of("type", "BAD_REQUEST", "message", "code is required"));
    int upd =
        jdbc.update(
            "update public.barcodes set is_available=true where code=? and is_available=false",
            code);
    return ResponseEntity.ok(Map.of("released", upd > 0));
  }
}
