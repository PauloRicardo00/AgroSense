package br.agrosense.backend;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/leituras")
public class HistoricoController {
    private final JdbcTemplate jdbc;
    public HistoricoController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Pagina(long total, List<Map<String,Object>> itens) {}

    private Timestamp inicio(String periodo, Instant fim) {
        return Timestamp.from(switch (periodo) {
            case "hour" -> fim.minusSeconds(3600);
            case "day" -> fim.minusSeconds(86400);
            case "all" -> Instant.EPOCH;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        });
    }

    @GetMapping("/historico")
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Pagina historico(@RequestParam String maquina, @RequestParam String periodo,
            @RequestParam Instant fim, @RequestParam String situacao, @RequestParam int pagina) {
        if (pagina < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String filtro = " FROM public.leitura WHERE machine_id=? AND recebida_em BETWEEN ? AND ? " + switch(situacao) {
            case "all" -> "";
            case "ok" -> " AND falha IS FALSE";
            case "crit" -> " AND falha IS TRUE";
            case "warn" -> " AND falha IS NULL";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        };
        var de = inicio(periodo,fim);
        var ate = Timestamp.from(fim);
        long total = jdbc.queryForObject("SELECT count(*)" + filtro, Long.class, maquina,de,ate);
        var linhas = jdbc.queryForList("SELECT id, recebida_em, process_temperature-273.15 AS temp, rotational_speed AS rpm, torque, falha " +
            filtro + " ORDER BY recebida_em DESC,id DESC LIMIT 20 OFFSET ?",maquina,de,ate,(pagina-1L)*20);
        return new Pagina(total,linhas);
    }

    @GetMapping("/resumo")
    public List<Map<String,Object>> resumo(@RequestParam String periodo,@RequestParam Instant fim) {
        return jdbc.queryForList("""
            SELECT machine_id, count(*) AS n, count(*) FILTER (WHERE falha) AS failures,
              min(recebida_em) AS inicio,
              min(process_temperature)-273.15 AS temp_min, avg(process_temperature)-273.15 AS temp_avg, max(process_temperature)-273.15 AS temp_max,
              min(rotational_speed) AS rpm_min, avg(rotational_speed) AS rpm_avg, max(rotational_speed) AS rpm_max,
              min(torque) AS torque_min, avg(torque) AS torque_avg, max(torque) AS torque_max
            FROM public.leitura WHERE recebida_em BETWEEN ? AND ? GROUP BY machine_id
            """,inicio(periodo,fim),Timestamp.from(fim));
    }
}
