package br.agrosense.backend.usuarios;

import java.time.Instant;

public record UsuarioDados(long id, String nome, String email, String perfil, boolean ativo, Instant criadoEm) { }
