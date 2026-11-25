package br.agrosense.backend.seguranca;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

public final class UsuarioAcesso extends User {
    private static final long serialVersionUID = 1L;
    private final long id;
    private final String nome;
    private final String perfil;
    private final String assinatura;

    public UsuarioAcesso(long id, String nome, String email, String hash, String perfil, boolean ativo) {
        super(email, hash, ativo, true, true, true, List.of(new SimpleGrantedAuthority("ROLE_" + perfil)));
        this.id = id;
        this.nome = nome;
        this.perfil = perfil;
        this.assinatura = assinatura(id, email, hash, perfil, ativo);
    }

    public long id() { return id; }
    public String nome() { return nome; }
    public String perfil() { return perfil; }
    public String assinatura() { return assinatura; }

    public static String assinatura(long id, String email, String hash, String perfil, boolean ativo) {
        try {
            byte[] bytes = (id + "\n" + email + "\n" + hash + "\n" + perfil + "\n" + ativo).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
