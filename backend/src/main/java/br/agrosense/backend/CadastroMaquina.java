package br.agrosense.backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CadastroMaquina {

    private final JdbcTemplate jdbc;

    public CadastroMaquina(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean estaAtiva(String machineId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM public.maquina m
                    JOIN public.setor s ON s.id = m.setor_id
                    WHERE m.id = ? AND m.ativa AND s.ativo
                )
                """, Boolean.class, machineId));
    }
}
